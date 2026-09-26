package com.simonrowe.coparent.messaging;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Conversation;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.ConversationRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Atomic family messaging, unread-state and formal permission decisions. */
@Service
public class MessagingService {

  private static final Set<String> PERMISSION_TYPES =
      Set.of("medical", "travel", "schedule", "extracurricular");

  private final ConversationRepository conversations;
  private final ParentRepository parents;
  private final ChildRepository children;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final MongoTemplate mongoTemplate;

  /** Creates a messaging service with family-scoped persistence and policy collaborators. */
  public MessagingService(
      final ConversationRepository conversations,
      final ParentRepository parents,
      final ChildRepository children,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this.conversations = conversations;
    this.parents = parents;
    this.children = children;
    this.access = access;
    this.audits = audits;
    this.mongoTemplate = mongoTemplate;
  }

  /** Lists conversation views with unread state relative to the caller. */
  public List<ConversationView> list(final ObjectId familyId) {
    final Parent actor = access.requireMember(familyId);
    final Map<ObjectId, Parent> parentMap = parentMap(familyId);
    return conversations.findByFamilyIdAndDeletedAtIsNullOrderByLastMessageAtDesc(familyId).stream()
        .map(conversation -> view(conversation, actor.id(), parentMap))
        .toList();
  }

  /** Starts a message thread and increments only the recipient's unread count. */
  public ConversationView createMessage(
      final ObjectId familyId,
      final ObjectId recipientId,
      final String subject,
      final String rawMessage) {
    return createMessage(familyId, recipientId, subject, rawMessage,
        null, null, null);
  }

  /** Starts a message thread with preallocated assistant identifiers. */
  public ConversationView createMessage(
      final ObjectId familyId,
      final ObjectId recipientId,
      final String subject,
      final String rawMessage,
      final ObjectId conversationId,
      final ObjectId messageId,
      final ObjectId assistantActionId) {
    final Parent actor = access.requireMember(familyId);
    final Map<ObjectId, Parent> familyParents = parentMap(familyId);
    final Parent recipient = recipientId == null
        ? familyParents.values().stream().filter(parent -> !parent.id().equals(actor.id()))
            .findFirst().orElse(null)
        : familyParents.get(recipientId);
    if (recipient == null || recipient.id().equals(actor.id())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A co-parent recipient is required");
    }
    final String content = requireText(rawMessage, "Message content is required");
    final Instant now = Instant.now();
    final Conversation.Message message = new Conversation.Message(
        messageId == null ? new ObjectId() : messageId,
        actor.id(), content, now, List.of(actor.id()));
    final Conversation saved = conversations.save(new Conversation(
        conversationId, familyId, "message",
        subject == null || subject.isBlank() ? "New conversation" : subject.trim(), actor.id(),
        recipient.id(), List.of(message), null,
        Map.of(actor.id().toHexString(), 0, recipient.id().toHexString(), 1),
        now, assistantActionId, null, now, now));
    audits.record(familyId, "conversation", saved.id(), "create-message-thread",
        Map.of("subject", saved.subject(), "recipientId", recipient.id().toHexString()));
    return view(saved, actor.id(), familyParents);
  }

  /** Starts a formal child permission request addressed to the other parent. */
  public ConversationView createPermission(
      final ObjectId familyId,
      final String subject,
      final String type,
      final ObjectId childId,
      final String rawDescription) {
    return createPermission(familyId, subject, type, childId, rawDescription,
        null, null, null);
  }

  /** Starts a permission thread with preallocated assistant identifiers. */
  public ConversationView createPermission(
      final ObjectId familyId,
      final String subject,
      final String type,
      final ObjectId childId,
      final String rawDescription,
      final ObjectId conversationId,
      final ObjectId permissionId,
      final ObjectId assistantActionId) {
    final Parent actor = access.requireMember(familyId);
    final Map<ObjectId, Parent> familyParents = parentMap(familyId);
    final Parent recipient = familyParents.values().stream()
        .filter(parent -> !parent.id().equals(actor.id())).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "A co-parent recipient is required"));
    if (!PERMISSION_TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invalid permission request type");
    }
    final Child child = children.findByIdAndFamilyIdAndDeletedAtIsNull(childId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Child not found"));
    final String description = requireText(rawDescription,
        "Permission request description is required");
    final Instant now = Instant.now();
    final Conversation.PermissionRequest permission = new Conversation.PermissionRequest(
        permissionId == null ? new ObjectId() : permissionId,
        type, child.id(), child.fullName(), description, actor.id(), "pending",
        now, null, null);
    final Conversation saved = conversations.save(new Conversation(
        conversationId, familyId, "permission",
        subject == null || subject.isBlank() ? "Permission request" : subject.trim(), actor.id(),
        recipient.id(), List.of(), permission,
        Map.of(actor.id().toHexString(), 0, recipient.id().toHexString(), 1),
        now, assistantActionId, null, now, now));
    audits.record(familyId, "permission-request", permission.id(), "create",
        Map.of("type", type, "childId", child.id().toHexString()));
    return view(saved, actor.id(), familyParents);
  }

  /** Appends a message and updates both unread counters in the owning document atomically. */
  public ConversationView sendMessage(final ObjectId conversationId, final String rawContent) {
    return sendMessage(conversationId, rawContent, null, null);
  }

  /** Appends an assistant message once using its preallocated embedded identifier. */
  public ConversationView sendMessage(
      final ObjectId conversationId,
      final String rawContent,
      final ObjectId suppliedMessageId,
      final ObjectId assistantActionId) {
    final String content = requireText(rawContent, "Message content is required");
    final Conversation current = requireConversation(conversationId);
    final Parent actor = access.requireMember(current.familyId());
    requireParticipant(current, actor.id());
    final ObjectId recipientId = otherParent(current, actor.id());
    final Instant now = Instant.now();
    final ObjectId messageId = suppliedMessageId == null ? new ObjectId() : suppliedMessageId;
    if (suppliedMessageId != null && current.messages().stream()
        .anyMatch(existing -> messageId.equals(existing.id()))) {
      return view(current, actor.id(), parentMap(current.familyId()));
    }
    final Conversation.Message message = new Conversation.Message(
        messageId, actor.id(), content, now, List.of(actor.id()));
    final Update update = new Update()
        .push("messages", message)
        .set("lastMessageAt", now)
        .set("updatedAt", now)
        .set("unreadCounts." + actor.id().toHexString(), 0)
        .inc("unreadCounts." + recipientId.toHexString(), 1);
    if (assistantActionId != null) {
      update.set("assistantActionId", assistantActionId);
    }
    final Conversation saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(conversationId).and("familyId").is(current.familyId())
            .and("deletedAt").is(null).and("messages._id").ne(messageId)),
        update,
        FindAndModifyOptions.options().returnNew(true), Conversation.class);
    if (saved == null) {
      final Conversation reconciled = requireConversation(conversationId);
      if (reconciled.messages().stream().anyMatch(existing -> messageId.equals(existing.id()))) {
        return view(reconciled, actor.id(), parentMap(current.familyId()));
      }
      throw notFound();
    }
    audits.record(current.familyId(), "message", saved.id(), "send",
        Map.of("contentLength", content.length()));
    return view(saved, actor.id(), parentMap(current.familyId()));
  }

  /** Marks every embedded message read for the caller in one conversation update. */
  public ConversationView markRead(final ObjectId conversationId) {
    return mutateReadState(conversationId, conversation -> {
      final Parent actor = access.requireMember(conversation.familyId());
      final List<Conversation.Message> messages = conversation.messages().stream()
          .map(message -> {
            if (message.readBy().contains(actor.id())) {
              return message;
            }
            final List<ObjectId> readers = new ArrayList<>(message.readBy());
            readers.add(actor.id());
            return new Conversation.Message(message.id(), message.senderId(), message.content(),
                message.timestamp(), readers);
          }).toList();
      return withMessagesAndUnread(conversation, messages, actor.id(), 0);
    });
  }

  /** Restores the source contract's single unread marker on the last message. */
  public ConversationView markUnread(final ObjectId conversationId) {
    return mutateReadState(conversationId, conversation -> {
      final Parent actor = access.requireMember(conversation.familyId());
      final List<Conversation.Message> messages = new ArrayList<>(conversation.messages());
      if (!messages.isEmpty()) {
        final Conversation.Message last = messages.getLast();
        messages.set(messages.size() - 1, new Conversation.Message(last.id(), last.senderId(),
            last.content(), last.timestamp(), last.readBy().stream()
                .filter(id -> !id.equals(actor.id())).toList()));
      }
      return withMessagesAndUnread(conversation, messages, actor.id(), 1);
    });
  }

  /** Atomically approves or denies a pending permission as the non-requesting parent. */
  public ConversationView resolvePermission(
      final ObjectId permissionId,
      final String status,
      final String response) {
    if (!List.of("approved", "denied").contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permission decision");
    }
    final Conversation current = conversations.findByPermissionRequestIdAndDeletedAtIsNull(
        permissionId).orElseThrow(MessagingService::notFound);
    final Parent actor = access.requireMember(current.familyId());
    requireParticipant(current, actor.id());
    if (current.permissionRequest().requestedBy().equals(actor.id())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "You cannot resolve your own permission request");
    }
    final Instant now = Instant.now();
    final String requesterKey = current.permissionRequest().requestedBy().toHexString();
    final Conversation saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(current.id())
            .and("permissionRequest._id").is(permissionId)
            .and("permissionRequest.status").is("pending")
            .and("permissionRequest.requestedBy").ne(actor.id())
            .and("deletedAt").is(null)),
        new Update().set("permissionRequest.status", status)
            .set("permissionRequest.response", response == null ? null : response.trim())
            .set("permissionRequest.resolvedAt", now).set("lastMessageAt", now)
            .set("updatedAt", now).set("unreadCounts." + actor.id().toHexString(), 0)
            .inc("unreadCounts." + requesterKey, 1),
        FindAndModifyOptions.options().returnNew(true), Conversation.class);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Permission request is no longer pending");
    }
    audits.record(saved.familyId(), "permission-request", permissionId, status,
        Map.of("responseProvided", response != null && !response.isBlank()));
    return view(saved, actor.id(), parentMap(saved.familyId()));
  }

  private ConversationView mutateReadState(
      final ObjectId conversationId,
      final UnaryOperator<Conversation> mutation) {
    final Conversation current = requireConversation(conversationId);
    final Parent actor = access.requireMember(current.familyId());
    requireParticipant(current, actor.id());
    final Conversation updated = mutation.apply(current);
    final Conversation saved = conversations.save(updated);
    return view(saved, actor.id(), parentMap(saved.familyId()));
  }

  private Conversation requireConversation(final ObjectId conversationId) {
    return conversations.findById(conversationId)
        .filter(conversation -> conversation.deletedAt() == null)
        .orElseThrow(MessagingService::notFound);
  }

  private Map<ObjectId, Parent> parentMap(final ObjectId familyId) {
    final Map<ObjectId, Parent> result = new HashMap<>();
    parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE)
        .forEach(parent -> result.put(parent.id(), parent));
    return result;
  }

  private static Conversation withMessagesAndUnread(
      final Conversation current,
      final List<Conversation.Message> messages,
      final ObjectId actorId,
      final int unreadCount) {
    final Map<String, Integer> unread = new HashMap<>(current.unreadCounts());
    unread.put(actorId.toHexString(), unreadCount);
    return new Conversation(current.id(), current.familyId(), current.type(), current.subject(),
        current.parent1Id(), current.parent2Id(), messages, current.permissionRequest(), unread,
        current.lastMessageAt(), current.assistantActionId(), current.deletedAt(),
        current.createdAt(), Instant.now());
  }

  private static void requireParticipant(
      final Conversation conversation,
      final ObjectId parentId) {
    if (!conversation.parent1Id().equals(parentId) && !conversation.parent2Id().equals(parentId)) {
      throw notFound();
    }
  }

  private static ObjectId otherParent(
      final Conversation conversation,
      final ObjectId parentId) {
    return conversation.parent1Id().equals(parentId)
        ? conversation.parent2Id() : conversation.parent1Id();
  }

  private static String requireText(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    return value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
  }

  private static ConversationView view(
      final Conversation conversation,
      final ObjectId currentParentId,
      final Map<ObjectId, Parent> parentMap) {
    final List<MessageView> messages = conversation.messages().stream()
        .map(message -> new MessageView(message.id().toHexString(),
            message.senderId().toHexString(), message.content(), message.timestamp(),
            message.readBy().contains(currentParentId),
            deliveryStatus(conversation, message, parentMap)))
        .toList();
    final PermissionView permission = conversation.permissionRequest() == null ? null
        : PermissionView.from(conversation.permissionRequest());
    return new ConversationView(conversation.id().toHexString(), conversation.type(),
        conversation.subject(), conversation.lastMessageAt(),
        conversation.unreadCounts().getOrDefault(currentParentId.toHexString(), 0),
        new Participants(participant(conversation.parent1Id(), parentMap),
            participant(conversation.parent2Id(), parentMap)), messages, permission);
  }

  private static Participant participant(
      final ObjectId id,
      final Map<ObjectId, Parent> parentMap) {
    final Parent parent = parentMap.get(id);
    return new Participant(id.toHexString(), parent == null || parent.fullName().isBlank()
        ? "Parent" : parent.fullName(), parent == null ? null : parent.avatarUrl());
  }

  private static String deliveryStatus(
      final Conversation conversation,
      final Conversation.Message message,
      final Map<ObjectId, Parent> parentMap) {
    final ObjectId recipientId = otherParent(conversation, message.senderId());
    if (message.readBy().contains(recipientId)) {
      return "read";
    }
    return parentMap.containsKey(recipientId) ? "delivered" : "sent";
  }

  /** Browser-facing conversation with caller-relative unread state. */
  public record ConversationView(
      String id,
      String type,
      String subject,
      Instant lastMessageAt,
      int unreadCount,
      Participants participants,
      List<MessageView> messages,
      PermissionView permissionRequest
  ) {
  }

  public record Participants(Participant parent1, Participant parent2) {
  }

  public record Participant(String id, String name, String avatarUrl) {
  }

  public record MessageView(
      String id,
      String senderId,
      String content,
      Instant timestamp,
      boolean isRead,
      String deliveryStatus
  ) {
  }

  public record PermissionView(
      String id,
      String type,
      String childId,
      String childName,
      String description,
      String requestedBy,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      String response
  ) {
    static PermissionView from(final Conversation.PermissionRequest permission) {
      return new PermissionView(permission.id().toHexString(), permission.type(),
          permission.childId().toHexString(), permission.childName(), permission.description(),
          permission.requestedBy().toHexString(), permission.status(), permission.createdAt(),
          permission.resolvedAt(), permission.response());
    }
  }
}
