package com.simonrowe.coparent.assistant;

import com.simonrowe.coparent.calendar.CalendarService;
import com.simonrowe.coparent.messaging.MessagingService;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.EventCategory;
import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.ConversationRepository;
import com.simonrowe.coparent.persistence.EventCategoryRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.ScheduleChangeRepository;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Revalidates and applies one human-approved proposal through existing domain services. */
@Service
public class AssistantActionExecutor {

  private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(2);

  private final AssistantProposalService proposals;
  private final CalendarService calendar;
  private final MessagingService messaging;
  private final CoparentAuditService audits;
  private final EventRepository events;
  private final EventCategoryRepository categories;
  private final ScheduleChangeRepository changes;
  private final ConversationRepository conversations;

  public AssistantActionExecutor(
      final AssistantProposalService proposals,
      final CalendarService calendar,
      final MessagingService messaging,
      final CoparentAuditService audits,
      final EventRepository events,
      final EventCategoryRepository categories,
      final ScheduleChangeRepository changes,
      final ConversationRepository conversations) {
    this.proposals = proposals;
    this.calendar = calendar;
    this.messaging = messaging;
    this.audits = audits;
    this.events = events;
    this.categories = categories;
    this.changes = changes;
    this.conversations = conversations;
  }

  /** Atomically claims, executes, and records one action decision. */
  public AssistantDtos.Action approve(
      final ObjectId familyId,
      final ObjectId batchId,
      final ObjectId actionId,
      final long version) {
    final AssistantProposalBatch batch = proposals.requireOwned(familyId, batchId);
    final AssistantProposalBatch.Action current = proposals.findAction(batch, actionId);
    if (current.status() == AssistantProposalBatch.ActionStatus.APPLIED) {
      recordReceipt(familyId, batchId, current);
      return AssistantDtos.Action.from(current);
    }
    if (current.status() != AssistantProposalBatch.ActionStatus.APPLYING
        && current.revision() != version) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The action was changed in another request");
    }
    if (current.status() == AssistantProposalBatch.ActionStatus.BLOCKED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "Resolve the blocked fields before approval");
    }
    if (current.status() == AssistantProposalBatch.ActionStatus.REJECTED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This action was rejected");
    }
    final AssistantProposalBatch.Action applying;
    if (current.status() == AssistantProposalBatch.ActionStatus.APPLYING) {
      final Instant staleBefore = Instant.now().minus(STALE_CLAIM_AFTER);
      if (current.claimedAt() == null || current.claimedAt().isAfter(staleBefore)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "This action is currently being applied");
      }
      final AssistantProposalBatch reclaimed = proposals.reclaim(familyId, batchId, actionId,
          current.revision(), staleBefore);
      if (reclaimed == null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "This action is currently being applied");
      }
      applying = proposals.findAction(reclaimed, actionId);
    } else {
      final AssistantProposalBatch claimed = proposals.claim(familyId, batchId, actionId, version);
      if (claimed == null) {
        final AssistantProposalBatch refreshed = proposals.requireOwned(familyId, batchId);
        final AssistantProposalBatch.Action concurrent = proposals.findAction(refreshed, actionId);
        if (concurrent.status() == AssistantProposalBatch.ActionStatus.APPLIED) {
          recordReceipt(familyId, batchId, concurrent);
          return AssistantDtos.Action.from(concurrent);
        }
        if (concurrent.status() != AssistantProposalBatch.ActionStatus.APPLYING) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "The action was changed in another request");
        }
        applying = concurrent;
      } else {
        applying = proposals.findAction(claimed, actionId);
      }
    }
    try {
      final AssistantProposalBatch.ResultReference result = execute(familyId, applying);
      final AssistantProposalBatch.Action applied = copy(applying,
          AssistantProposalBatch.ActionStatus.APPLIED, applying.revision() + 1,
          List.of(), result, null, null, Instant.now());
      final AssistantProposalBatch.Action saved = proposals.finalizeAction(
          proposals.requireOwned(familyId, batchId), applying, applied);
      recordReceipt(familyId, batchId, saved);
      return AssistantDtos.Action.from(saved);
    } catch (StaleTargetException exception) {
      final AssistantProposalBatch.Action blocked = copy(applying,
          AssistantProposalBatch.ActionStatus.BLOCKED, applying.revision() + 1,
          List.of(new AssistantProposalBatch.FieldError("target",
              "The target changed since analysis. Review it again before approval.")),
          null, null, null, null);
      return AssistantDtos.Action.from(proposals.finalizeAction(
          proposals.requireOwned(familyId, batchId), applying, blocked));
    } catch (ResponseStatusException exception) {
      final String message = exception.getReason() == null
          ? "The action no longer passes domain validation" : exception.getReason();
      final AssistantProposalBatch.Action blocked = copy(applying,
          AssistantProposalBatch.ActionStatus.BLOCKED, applying.revision() + 1,
          List.of(new AssistantProposalBatch.FieldError("action", message)),
          null, null, null, null);
      return AssistantDtos.Action.from(proposals.finalizeAction(
          proposals.requireOwned(familyId, batchId), applying, blocked));
    } catch (RuntimeException exception) {
      final AssistantProposalBatch.Action failed = copy(applying,
          AssistantProposalBatch.ActionStatus.FAILED, applying.revision() + 1,
          applying.fieldErrors(), null, "The action could not be applied. Try again.",
          null, null);
      return AssistantDtos.Action.from(proposals.finalizeAction(
          proposals.requireOwned(familyId, batchId), applying, failed));
    }
  }

  private void recordReceipt(
      final ObjectId familyId,
      final ObjectId batchId,
      final AssistantProposalBatch.Action action) {
    if (action.result() == null) {
      return;
    }
    audits.recordAssistantReceipt(familyId, action.id(), Map.of(
        "batchId", batchId.toHexString(),
        "actionType", action.actionType().name(),
        "resultType", action.result().entityType(),
        "resultId", action.result().entityId().toHexString()));
  }

  private AssistantProposalBatch.ResultReference execute(
      final ObjectId familyId, final AssistantProposalBatch.Action action) {
    final Map<String, Object> payload = action.payload();
    return switch (action.actionType()) {
      case CREATE_EVENT -> eventResult(events.findByAssistantActionId(action.id())
          .orElseGet(() -> calendar.createEvent(familyId, eventValues(payload, null),
              action.operationId(), action.id())));
      case UPDATE_EVENT -> {
        final ObjectId eventId = id(payload, "eventId");
        final CalendarEvent reconciled = events.findByAssistantActionId(action.id()).orElse(null);
        if (reconciled != null) {
          yield eventResult(reconciled);
        }
        final CalendarEvent current = calendar.getEvent(familyId, eventId);
        requireUnchanged(action, current.updatedAt());
        yield eventResult(calendar.updateEvent(familyId, eventId,
            eventValues(payload, current), action.id()));
      }
      case DELETE_EVENT -> {
        final ObjectId eventId = id(payload, "eventId");
        if (events.findByAssistantActionId(action.id()).isPresent()) {
          yield new AssistantProposalBatch.ResultReference("event", eventId, "/calendar");
        }
        requireUnchanged(action, calendar.getEvent(familyId, eventId).updatedAt());
        calendar.deleteEvent(familyId, eventId, action.id());
        yield new AssistantProposalBatch.ResultReference(
            "event", eventId, "/calendar");
      }
      case CREATE_CATEGORY -> categoryResult(categories.findByAssistantActionId(action.id())
          .orElseGet(() -> calendar.createCategory(familyId, categoryValues(payload, null),
              action.operationId(), action.id())));
      case UPDATE_CATEGORY -> {
        final ObjectId categoryId = id(payload, "categoryId");
        final EventCategory reconciled = categories
            .findByAssistantActionId(action.id()).orElse(null);
        if (reconciled != null) {
          yield categoryResult(reconciled);
        }
        final EventCategory current = calendar.getCategory(familyId, categoryId);
        requireUnchanged(action, current.updatedAt());
        yield categoryResult(calendar.updateCategory(familyId, categoryId,
            categoryValues(payload, current), action.id()));
      }
      case DELETE_CATEGORY -> {
        final ObjectId categoryId = id(payload, "categoryId");
        if (categories.findByAssistantActionId(action.id()).isPresent()) {
          yield new AssistantProposalBatch.ResultReference(
              "event_category", categoryId, "/calendar");
        }
        requireUnchanged(action, calendar.getCategory(familyId, categoryId).updatedAt());
        calendar.deleteCategory(familyId, categoryId, action.id());
        yield new AssistantProposalBatch.ResultReference(
            "event_category", categoryId, "/calendar");
      }
      case CREATE_SCHEDULE_CHANGE -> {
        final ScheduleChangeRequest created = changes.findByAssistantActionId(action.id())
            .orElseGet(() -> calendar.createChange(familyId,
                optionalId(payload, "originalEventId"),
                new ScheduleChangeRequest.ProposedChange(
                    string(payload, "type", null), string(payload, "originalStartDate", null),
                    string(payload, "originalEndDate", null),
                    string(payload, "newStartDate", null),
                    string(payload, "newEndDate", null)), string(payload, "reason", null),
                action.operationId(), action.id()));
        yield new AssistantProposalBatch.ResultReference("schedule_change_request", created.id(),
            "/calendar?request=" + created.id().toHexString());
      }
      case WITHDRAW_SCHEDULE_CHANGE -> {
        final ObjectId requestId = id(payload, "requestId");
        if (changes.findByAssistantActionId(action.id()).isPresent()) {
          yield new AssistantProposalBatch.ResultReference(
              "schedule_change_request", requestId, "/calendar");
        }
        requireUnchanged(action, calendar.getChange(familyId, requestId).updatedAt());
        calendar.withdrawChange(familyId, requestId, action.id());
        yield new AssistantProposalBatch.ResultReference(
            "schedule_change_request", requestId, "/calendar");
      }
      case START_MESSAGE_CONVERSATION -> {
        final var existing = conversations.findByAssistantActionId(action.id()).orElse(null);
        final ObjectId conversationId;
        if (existing != null) {
          conversationId = existing.id();
        } else {
          final MessagingService.ConversationView conversation = messaging.createMessage(familyId,
              optionalId(payload, "recipientId"), string(payload, "subject", null),
              string(payload, "message", null), action.operationId(), action.id(), action.id());
          conversationId = new ObjectId(conversation.id());
        }
        yield new AssistantProposalBatch.ResultReference("conversation", conversationId,
            "/messages?conversation=" + conversationId.toHexString());
      }
      case SEND_MESSAGE -> {
        final ObjectId conversationId = id(payload, "conversationId");
        if (conversations.findByMessagesId(action.operationId()).isEmpty()) {
          final var current = conversations
              .findByIdAndFamilyIdAndDeletedAtIsNull(conversationId, familyId)
              .orElseThrow(() -> new ResponseStatusException(
                  HttpStatus.NOT_FOUND, "Conversation not found"));
          requireUnchanged(action, current.updatedAt());
          messaging.sendMessage(conversationId, string(payload, "message", null),
              action.operationId(), action.id());
        }
        yield new AssistantProposalBatch.ResultReference("conversation", conversationId,
            "/messages?conversation=" + conversationId.toHexString());
      }
      case CREATE_PERMISSION_REQUEST -> {
        final var existing = conversations.findByAssistantActionId(action.id()).orElse(null);
        final ObjectId conversationId;
        if (existing != null) {
          conversationId = existing.id();
        } else {
          final MessagingService.ConversationView conversation = messaging.createPermission(
              familyId, string(payload, "subject", null), string(payload, "type", null),
              id(payload, "childId"), string(payload, "description", null),
              action.operationId(), action.id(), action.id());
          conversationId = new ObjectId(conversation.id());
        }
        yield new AssistantProposalBatch.ResultReference("conversation", conversationId,
            "/messages?conversation=" + conversationId.toHexString());
      }
    };
  }

  private static CalendarService.EventValues eventValues(
      final Map<String, Object> payload, final CalendarEvent current) {
    final String recurrence = string(payload, "recurringFrequency",
        current == null || current.recurring() == null ? null : current.recurring().frequency());
    final List<String> recurringDays = strings(payload, "recurringDays",
        current == null || current.recurring() == null ? List.of() : current.recurring().days());
    return new CalendarService.EventValues(
        string(payload, "type", current == null ? null : current.type()),
        string(payload, "title", current == null ? null : current.title()),
        instant(payload, "startDate", current == null ? null : current.startDate()),
        instant(payload, "endDate", current == null ? null : current.endDate()),
        string(payload, "startTime", current == null ? null : current.startTime()),
        string(payload, "endTime", current == null ? null : current.endTime()),
        bool(payload, "allDay", current != null && current.allDay()),
        optionalId(payload, "parentId", current == null ? null : current.parentId()),
        ids(payload, "parentIds", current == null ? List.of() : current.parentIds()),
        ids(payload, "childIds", current == null ? List.of() : current.childIds()),
        string(payload, "location", current == null ? null : current.location()),
        string(payload, "notes", current == null ? null : current.notes()),
        recurrence == null ? null : new CalendarEvent.Recurring(recurrence, recurringDays));
  }

  private static CalendarService.CategoryValues categoryValues(
      final Map<String, Object> payload, final EventCategory current) {
    return new CalendarService.CategoryValues(
        string(payload, "name", current == null ? null : current.name()),
        string(payload, "icon", current == null ? "calendar" : current.icon()),
        string(payload, "color", current == null ? "#0f766e" : current.color()),
        current != null && current.defaultCategory());
  }

  private static void requireUnchanged(
      final AssistantProposalBatch.Action action, final Instant currentUpdatedAt) {
    if (action.targetSnapshot() == null
        || !Objects.equals(action.targetSnapshot().observedUpdatedAt(), currentUpdatedAt)) {
      throw new StaleTargetException();
    }
  }

  private static AssistantProposalBatch.ResultReference eventResult(final CalendarEvent event) {
    return new AssistantProposalBatch.ResultReference(
        "event", event.id(), "/calendar?event=" + event.id().toHexString());
  }

  private static AssistantProposalBatch.ResultReference categoryResult(
      final EventCategory category) {
    return new AssistantProposalBatch.ResultReference(
        "event_category", category.id(), "/calendar");
  }

  private static ObjectId id(final Map<String, Object> payload, final String field) {
    final String value = string(payload, field, null);
    if (value == null || !ObjectId.isValid(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return new ObjectId(value);
  }

  private static ObjectId optionalId(final Map<String, Object> payload, final String field) {
    return optionalId(payload, field, null);
  }

  private static ObjectId optionalId(
      final Map<String, Object> payload, final String field, final ObjectId fallback) {
    final String value = string(payload, field, null);
    return value == null ? fallback : new ObjectId(value);
  }

  private static List<ObjectId> ids(
      final Map<String, Object> payload, final String field, final List<ObjectId> fallback) {
    if (!(payload.get(field) instanceof List<?> values)) {
      return fallback;
    }
    return values.stream().filter(String.class::isInstance).map(String.class::cast)
        .map(ObjectId::new).toList();
  }

  private static List<String> strings(
      final Map<String, Object> payload, final String field, final List<String> fallback) {
    if (!(payload.get(field) instanceof List<?> values)) {
      return fallback;
    }
    return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }

  private static String string(
      final Map<String, Object> payload, final String field, final String fallback) {
    final Object value = payload.get(field);
    return value instanceof String string && !string.isBlank() ? string.trim() : fallback;
  }

  private static Instant instant(
      final Map<String, Object> payload, final String field, final Instant fallback) {
    final String value = string(payload, field, null);
    if (value == null) {
      return fallback;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      try {
        return java.time.LocalDate.parse(value)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
      } catch (java.time.format.DateTimeParseException dateException) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            field + " must use YYYY-MM-DD or an ISO-8601 date and time", dateException);
      }
    }
  }

  private static boolean bool(
      final Map<String, Object> payload, final String field, final boolean fallback) {
    return payload.get(field) instanceof Boolean value ? value : fallback;
  }

  private static AssistantProposalBatch.Action copy(
      final AssistantProposalBatch.Action current,
      final AssistantProposalBatch.ActionStatus status,
      final long revision,
      final List<AssistantProposalBatch.FieldError> errors,
      final AssistantProposalBatch.ResultReference result,
      final String failure,
      final Instant claimedAt,
      final Instant decidedAt) {
    return new AssistantProposalBatch.Action(current.id(), current.actionType(), status,
        current.payload(), errors, revision, current.targetSnapshot(), result,
        current.operationId(), failure, claimedAt, decidedAt);
  }

  private static final class StaleTargetException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
