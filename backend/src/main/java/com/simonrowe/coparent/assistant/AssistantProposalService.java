package com.simonrowe.coparent.assistant;

import com.simonrowe.coparent.assistant.AssistantInferenceService.ProposedCall;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.EventCategory;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.AssistantProposalRepository;
import com.simonrowe.coparent.persistence.ConversationRepository;
import com.simonrowe.coparent.persistence.EventCategoryRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.persistence.ScheduleChangeRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Owns private proposal lifecycle operations before any approved domain execution. */
@Service
public class AssistantProposalService {

  private static final Duration RETENTION = Duration.ofDays(7);

  private static final Map<String, AssistantProposalBatch.ActionType> TOOL_TYPES = toolTypes();
  private static final Map<AssistantProposalBatch.ActionType, Set<String>> PAYLOAD_FIELDS =
      payloadFields();

  private final CoparentProperties properties;
  private final CoparentAccessPolicy access;
  private final AssistantInputValidator inputValidator;
  private final AssistantContextFactory contextFactory;
  private final AssistantInferenceService inference;
  private final AssistantProposalRepository batches;
  private final EventRepository events;
  private final EventCategoryRepository categories;
  private final ScheduleChangeRepository changes;
  private final ConversationRepository conversations;
  private final ParentRepository parents;
  private final ChildRepository children;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  /** Creates the proposal lifecycle service. */
  @Autowired
  public AssistantProposalService(
      final CoparentProperties properties,
      final CoparentAccessPolicy access,
      final AssistantInputValidator inputValidator,
      final AssistantContextFactory contextFactory,
      final AssistantInferenceService inference,
      final AssistantProposalRepository batches,
      final EventRepository events,
      final EventCategoryRepository categories,
      final ScheduleChangeRepository changes,
      final ConversationRepository conversations,
      final ParentRepository parents,
      final ChildRepository children,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(properties, access, inputValidator, contextFactory, inference, batches, events,
        categories, changes, conversations, parents, children, mongoTemplate, Clock.systemUTC());
  }

  AssistantProposalService(
      final CoparentProperties properties,
      final CoparentAccessPolicy access,
      final AssistantInputValidator inputValidator,
      final AssistantContextFactory contextFactory,
      final AssistantInferenceService inference,
      final AssistantProposalRepository batches,
      final EventRepository events,
      final EventCategoryRepository categories,
      final ScheduleChangeRepository changes,
      final ConversationRepository conversations,
      final ParentRepository parents,
      final ChildRepository children,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.properties = properties;
    this.access = access;
    this.inputValidator = inputValidator;
    this.contextFactory = contextFactory;
    this.inference = inference;
    this.batches = batches;
    this.events = events;
    this.categories = categories;
    this.changes = changes;
    this.conversations = conversations;
    this.parents = parents;
    this.children = children;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  public boolean enabled() {
    return properties.assistant() != null && properties.assistant().enabled();
  }

  /** Analyses transient input and persists normalized proposals only. */
  public AssistantDtos.Batch analyse(
      final ObjectId familyId, final String text, final MultipartFile image) {
    requireEnabled();
    final Parent actor = access.requireMember(familyId);
    final AssistantInputValidator.ValidatedInput input = inputValidator.validate(text, image);
    final AssistantContextFactory.Context context = contextFactory.build(familyId);
    final List<ProposedCall> calls = inference.propose(context, input);
    final List<AssistantProposalBatch.Action> actions = calls.stream()
        .filter(call -> !"no_action".equals(call.name()))
        .map(call -> normalize(familyId, actor, call)).toList();
    final Instant now = clock.instant();
    final Set<AssistantProposalBatch.InputKind> kinds = input.imageBytes() == null
        ? Set.of(AssistantProposalBatch.InputKind.TEXT)
        : input.text() == null
            ? Set.of(AssistantProposalBatch.InputKind.IMAGE)
            : Set.of(AssistantProposalBatch.InputKind.TEXT, AssistantProposalBatch.InputKind.IMAGE);
    final AssistantProposalBatch batch = batches.save(new AssistantProposalBatch(null, familyId,
        access.identity().subject(), actor.id(), actions.isEmpty()
            ? AssistantProposalBatch.BatchStatus.NO_ACTION
            : AssistantProposalBatch.BatchStatus.READY,
        properties.assistant().model(), kinds, actions, now, now, now.plus(RETENTION)));
    return AssistantDtos.Batch.from(batch);
  }

  public List<AssistantDtos.BatchSummary> list(final ObjectId familyId) {
    requireEnabled();
    access.requireMember(familyId);
    return batches.findTop20ByFamilyIdAndSubmittedBySubjectAndExpiresAtAfterOrderByCreatedAtDesc(
        familyId, access.identity().subject(), clock.instant()).stream()
        .map(AssistantDtos.BatchSummary::from).toList();
  }

  public AssistantDtos.Batch get(final ObjectId familyId, final ObjectId batchId) {
    return AssistantDtos.Batch.from(requireOwned(familyId, batchId));
  }

  /** Replaces one editable payload using an optimistic action revision. */
  public synchronized AssistantDtos.Action edit(
      final ObjectId familyId,
      final ObjectId batchId,
      final ObjectId actionId,
      final AssistantDtos.EditAction request) {
    final AssistantProposalBatch batch = requireOwned(familyId, batchId);
    final AssistantProposalBatch.Action current = findAction(batch, actionId);
    requireRevision(current, request.version());
    if (current.status() == AssistantProposalBatch.ActionStatus.APPLIED
        || current.status() == AssistantProposalBatch.ActionStatus.REJECTED
        || current.status() == AssistantProposalBatch.ActionStatus.APPLYING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This action can no longer be edited");
    }
    if (request.actionType() != current.actionType()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action type cannot be changed");
    }
    final ProposedCall replacement = new ProposedCall(toolName(current.actionType()),
        request.payload() == null ? Map.of() : request.payload());
    final AssistantProposalBatch.Action normalized = normalize(familyId,
        access.requireMember(familyId), replacement);
    final AssistantProposalBatch.Action updated = new AssistantProposalBatch.Action(current.id(),
        current.actionType(), normalized.status(), normalized.payload(), normalized.fieldErrors(),
        current.revision() + 1, normalized.targetSnapshot(), null, current.operationId(), null,
        null, null);
    return AssistantDtos.Action.from(replaceAtRevision(batch, current, updated));
  }

  /** Rejects one non-terminal action without touching domain data. */
  public synchronized AssistantDtos.Action reject(
      final ObjectId familyId,
      final ObjectId batchId,
      final ObjectId actionId,
      final long version) {
    final AssistantProposalBatch batch = requireOwned(familyId, batchId);
    final AssistantProposalBatch.Action current = findAction(batch, actionId);
    requireRevision(current, version);
    if (current.status() == AssistantProposalBatch.ActionStatus.APPLIED
        || current.status() == AssistantProposalBatch.ActionStatus.REJECTED
        || current.status() == AssistantProposalBatch.ActionStatus.APPLYING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This action can no longer be rejected");
    }
    final AssistantProposalBatch.Action rejected = new AssistantProposalBatch.Action(current.id(),
        current.actionType(), AssistantProposalBatch.ActionStatus.REJECTED, current.payload(),
        current.fieldErrors(), current.revision() + 1, current.targetSnapshot(), null,
        current.operationId(), null, null, clock.instant());
    return AssistantDtos.Action.from(replaceAtRevision(batch, current, rejected));
  }

  AssistantProposalBatch requireOwned(final ObjectId familyId, final ObjectId batchId) {
    requireEnabled();
    access.requireMember(familyId);
    return batches.findByIdAndFamilyIdAndSubmittedBySubjectAndExpiresAtAfter(batchId, familyId,
        access.identity().subject(), clock.instant())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Assistant batch not found"));
  }

  AssistantProposalBatch.Action findAction(
      final AssistantProposalBatch batch, final ObjectId actionId) {
    return batch.actions().stream().filter(action -> action.id().equals(actionId)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Assistant action not found"));
  }

  AssistantProposalBatch.Action finalizeAction(
      final AssistantProposalBatch batch,
      final AssistantProposalBatch.Action expected,
      final AssistantProposalBatch.Action replacement) {
    final Query query = Query.query(Criteria.where("_id").is(batch.id())
        .and("familyId").is(batch.familyId())
        .and("submittedBySubject").is(access.identity().subject())
        .and("expiresAt").gt(clock.instant())
        .and("actions").elemMatch(Criteria.where("_id").is(expected.id())
            .and("revision").is(expected.revision())
            .and("status").is(expected.status())));
    final AssistantProposalBatch saved = mongoTemplate.findAndModify(query,
        new Update().set("actions.$", replacement).set("updatedAt", clock.instant()),
        FindAndModifyOptions.options().returnNew(true), AssistantProposalBatch.class);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The action was changed in another request");
    }
    return findAction(saved, replacement.id());
  }

  private AssistantProposalBatch.Action replaceAtRevision(
      final AssistantProposalBatch batch,
      final AssistantProposalBatch.Action current,
      final AssistantProposalBatch.Action replacement) {
    final Query query = Query.query(Criteria.where("_id").is(batch.id())
        .and("familyId").is(batch.familyId())
        .and("submittedBySubject").is(access.identity().subject())
        .and("expiresAt").gt(clock.instant())
        .and("actions").elemMatch(Criteria.where("_id").is(current.id())
            .and("revision").is(current.revision())
            .and("status").is(current.status())));
    final AssistantProposalBatch saved = mongoTemplate.findAndModify(query,
        new Update().set("actions.$", replacement).set("updatedAt", clock.instant()),
        FindAndModifyOptions.options().returnNew(true), AssistantProposalBatch.class);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The action was changed in another request");
    }
    return findAction(saved, replacement.id());
  }

  /** Claims a pending or failed action with a database-level revision comparison. */
  AssistantProposalBatch claim(
      final ObjectId familyId,
      final ObjectId batchId,
      final ObjectId actionId,
      final long version) {
    requireEnabled();
    access.requireMember(familyId);
    final Criteria action = Criteria.where("_id").is(actionId)
        .and("revision").is(version)
        .and("status").in(AssistantProposalBatch.ActionStatus.PENDING,
            AssistantProposalBatch.ActionStatus.FAILED);
    final Query query = Query.query(Criteria.where("_id").is(batchId)
        .and("familyId").is(familyId)
        .and("submittedBySubject").is(access.identity().subject())
        .and("expiresAt").gt(clock.instant())
        .and("actions").elemMatch(action));
    final Update update = new Update()
        .set("actions.$.status", AssistantProposalBatch.ActionStatus.APPLYING)
        .set("actions.$.claimedAt", clock.instant())
        .unset("actions.$.failureMessage")
        .inc("actions.$.revision", 1)
        .set("updatedAt", clock.instant());
    return mongoTemplate.findAndModify(query, update,
        FindAndModifyOptions.options().returnNew(true), AssistantProposalBatch.class);
  }

  /** Reclaims only an abandoned applying action so one recovery request owns reconciliation. */
  AssistantProposalBatch reclaim(
      final ObjectId familyId,
      final ObjectId batchId,
      final ObjectId actionId,
      final long currentRevision,
      final Instant staleBefore) {
    requireEnabled();
    access.requireMember(familyId);
    final Criteria action = Criteria.where("_id").is(actionId)
        .and("revision").is(currentRevision)
        .and("status").is(AssistantProposalBatch.ActionStatus.APPLYING)
        .and("claimedAt").lte(staleBefore);
    final Query query = Query.query(Criteria.where("_id").is(batchId)
        .and("familyId").is(familyId)
        .and("submittedBySubject").is(access.identity().subject())
        .and("expiresAt").gt(clock.instant())
        .and("actions").elemMatch(action));
    final Update update = new Update()
        .set("actions.$.claimedAt", clock.instant())
        .inc("actions.$.revision", 1)
        .set("updatedAt", clock.instant());
    return mongoTemplate.findAndModify(query, update,
        FindAndModifyOptions.options().returnNew(true), AssistantProposalBatch.class);
  }

  private AssistantProposalBatch.Action normalize(
      final ObjectId familyId, final Parent actor, final ProposedCall call) {
    final AssistantProposalBatch.ActionType type = TOOL_TYPES.get(call.name());
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "The assistant returned an unknown action type");
    }
    final Map<String, Object> payload = new LinkedHashMap<>(call.arguments());
    if (!PAYLOAD_FIELDS.get(type).containsAll(payload.keySet())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The action contains unsupported fields");
    }
    final List<AssistantProposalBatch.FieldError> errors = new ArrayList<>();
    AssistantProposalBatch.TargetSnapshot target = null;
    switch (type) {
      case CREATE_EVENT -> {
        validateEventReferences(familyId, payload, errors);
        validateEventValues(payload, true, errors);
      }
      case UPDATE_EVENT -> {
        validateEventUpdateReferences(familyId, payload, errors);
        validateEventValues(payload, false, errors);
        target = eventTarget(familyId, payload, errors);
      }
      case DELETE_EVENT -> target = eventTarget(familyId, payload, errors);
      case CREATE_CATEGORY -> requireText(payload, "name", errors);
      case UPDATE_CATEGORY, DELETE_CATEGORY -> target = categoryTarget(familyId, payload, errors);
      case CREATE_SCHEDULE_CHANGE -> {
        requireText(payload, "type", errors);
        requireText(payload, "newStartDate", errors);
        requireText(payload, "newEndDate", errors);
        requireText(payload, "reason", errors);
        validateScheduleValues(payload, errors);
        optionalFamilyId(payload, "originalEventId", events
            .findByFamilyIdAndDeletedAtIsNullOrderByStartDateAsc(familyId).stream()
            .map(CalendarEvent::id).collect(java.util.stream.Collectors.toSet()), errors);
      }
      case WITHDRAW_SCHEDULE_CHANGE -> target = changeTarget(familyId, actor, payload, errors);
      case START_MESSAGE_CONVERSATION -> {
        requireText(payload, "message", errors);
        optionalFamilyId(payload, "recipientId", parents
            .findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE).stream()
            .map(Parent::id).filter(id -> !id.equals(actor.id()))
            .collect(java.util.stream.Collectors.toSet()), errors);
      }
      case SEND_MESSAGE -> {
        requireText(payload, "message", errors);
        target = conversationTarget(familyId, payload, errors);
      }
      case CREATE_PERMISSION_REQUEST -> {
        requireText(payload, "type", errors);
        requireText(payload, "description", errors);
        if (text(payload.get("type")) != null && !Set.of(
            "medical", "travel", "schedule", "extracurricular")
            .contains(text(payload.get("type")))) {
          errors.add(new AssistantProposalBatch.FieldError("type",
              "Choose a supported permission request type"));
        }
        requiredFamilyId(payload, "childId",
            children.findByFamilyIdAndDeletedAtIsNull(familyId).stream()
                .map(child -> child.id()).collect(java.util.stream.Collectors.toSet()), errors);
      }
    }
    final AssistantProposalBatch.ActionStatus status = errors.isEmpty()
        ? AssistantProposalBatch.ActionStatus.PENDING
        : AssistantProposalBatch.ActionStatus.BLOCKED;
    return new AssistantProposalBatch.Action(new ObjectId(), type, status, payload, errors, 0,
        target, null, new ObjectId(), null, null, null);
  }

  private void validateEventReferences(
      final ObjectId familyId,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    requireText(payload, "title", errors);
    requireText(payload, "startDate", errors);
    final Set<ObjectId> allowedChildren = children.findByFamilyIdAndDeletedAtIsNull(familyId)
        .stream().map(child -> child.id()).collect(java.util.stream.Collectors.toSet());
    requiredFamilyIds(payload, "childIds", allowedChildren, errors);
    final Set<ObjectId> allowedParents = parents
        .findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE).stream()
        .map(Parent::id).collect(java.util.stream.Collectors.toSet());
    optionalFamilyId(payload, "parentId", allowedParents, errors);
    optionalFamilyIds(payload, "parentIds", allowedParents, errors);
  }

  private void validateEventUpdateReferences(
      final ObjectId familyId,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final Set<ObjectId> allowedChildren = children.findByFamilyIdAndDeletedAtIsNull(familyId)
        .stream().map(child -> child.id()).collect(java.util.stream.Collectors.toSet());
    if (payload.get("childIds") instanceof List<?> suppliedChildren
        && suppliedChildren.isEmpty()) {
      errors.add(new AssistantProposalBatch.FieldError("childIds",
          "Select at least one child"));
    } else {
      optionalFamilyIds(payload, "childIds", allowedChildren, errors);
    }
    final Set<ObjectId> allowedParents = parents
        .findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE).stream()
        .map(Parent::id).collect(java.util.stream.Collectors.toSet());
    optionalFamilyId(payload, "parentId", allowedParents, errors);
    optionalFamilyIds(payload, "parentIds", allowedParents, errors);
  }

  private static void validateEventValues(
      final Map<String, Object> payload,
      final boolean create,
      final List<AssistantProposalBatch.FieldError> errors) {
    if (create) {
      requireText(payload, "type", errors);
    }
    final Instant start = parsedInstant(payload, "startDate", errors);
    final Instant end = parsedInstant(payload, "endDate", errors);
    if (start != null && end != null && end.isBefore(start)) {
      errors.add(new AssistantProposalBatch.FieldError("endDate",
          "Event end cannot precede its start"));
    }
    final String frequency = text(payload.get("recurringFrequency"));
    if (frequency != null && !Set.of("daily", "weekly").contains(frequency)) {
      errors.add(new AssistantProposalBatch.FieldError("recurringFrequency",
          "Choose daily or weekly recurrence"));
    }
  }

  private static Instant parsedInstant(
      final Map<String, Object> payload,
      final String field,
      final List<AssistantProposalBatch.FieldError> errors) {
    final String value = text(payload.get(field));
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      try {
        return java.time.LocalDate.parse(value)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
      } catch (java.time.format.DateTimeParseException dateException) {
        errors.add(new AssistantProposalBatch.FieldError(field,
            "Use YYYY-MM-DD or a complete ISO-8601 date and time"));
        return null;
      }
    }
  }

  private static void validateScheduleValues(
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final String type = text(payload.get("type"));
    if (type != null && !Set.of("swap", "extend", "add", "remove").contains(type)) {
      errors.add(new AssistantProposalBatch.FieldError("type",
          "Choose swap, extend, add, or remove"));
    }
    final java.time.LocalDate start = parsedDate(payload, "newStartDate", errors);
    final java.time.LocalDate end = parsedDate(payload, "newEndDate", errors);
    if (start != null && end != null && end.isBefore(start)) {
      errors.add(new AssistantProposalBatch.FieldError("newEndDate",
          "The proposed end cannot precede its start"));
    }
  }

  private static java.time.LocalDate parsedDate(
      final Map<String, Object> payload,
      final String field,
      final List<AssistantProposalBatch.FieldError> errors) {
    final String value = text(payload.get(field));
    if (value == null) {
      return null;
    }
    try {
      return java.time.LocalDate.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      errors.add(new AssistantProposalBatch.FieldError(field, "Use YYYY-MM-DD"));
      return null;
    }
  }

  private AssistantProposalBatch.TargetSnapshot eventTarget(
      final ObjectId familyId,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final ObjectId id = exactTarget(payload, "eventId", errors);
    if (id == null) {
      return hintTarget("event", payload);
    }
    final CalendarEvent event = events.findByIdAndFamilyIdAndDeletedAtIsNull(id, familyId)
        .orElse(null);
    if (event == null) {
      payload.put("eventId", null);
      errors.add(new AssistantProposalBatch.FieldError("eventId",
          "Select an event from this family"));
      return hintTarget("event", payload);
    }
    return new AssistantProposalBatch.TargetSnapshot("event", id, event.updatedAt(), null);
  }

  private AssistantProposalBatch.TargetSnapshot categoryTarget(
      final ObjectId familyId,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final ObjectId id = exactTarget(payload, "categoryId", errors);
    if (id == null) {
      return hintTarget("event_category", payload);
    }
    final EventCategory category = categories
        .findByIdAndFamilyIdAndDeletedAtIsNull(id, familyId).orElse(null);
    if (category == null || category.system()) {
      payload.put("categoryId", null);
      errors.add(new AssistantProposalBatch.FieldError("categoryId",
          "Select a non-system category from this family"));
      return hintTarget("event_category", payload);
    }
    return new AssistantProposalBatch.TargetSnapshot(
        "event_category", id, category.updatedAt(), null);
  }

  private AssistantProposalBatch.TargetSnapshot changeTarget(
      final ObjectId familyId,
      final Parent actor,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final ObjectId id = exactTarget(payload, "requestId", errors);
    if (id == null) {
      return hintTarget("schedule_change_request", payload);
    }
    final ScheduleChangeRequest change = changes
        .findByIdAndFamilyIdAndDeletedAtIsNull(id, familyId).orElse(null);
    if (change == null || !actor.id().equals(change.requestedBy())
        || !"pending".equals(change.status())) {
      payload.put("requestId", null);
      errors.add(new AssistantProposalBatch.FieldError("requestId",
          "Select one of your pending schedule requests"));
      return hintTarget("schedule_change_request", payload);
    }
    return new AssistantProposalBatch.TargetSnapshot(
        "schedule_change_request", id, change.updatedAt(), null);
  }

  private AssistantProposalBatch.TargetSnapshot conversationTarget(
      final ObjectId familyId,
      final Map<String, Object> payload,
      final List<AssistantProposalBatch.FieldError> errors) {
    final ObjectId id = exactTarget(payload, "conversationId", errors);
    if (id == null) {
      return hintTarget("conversation", payload);
    }
    final var conversation = conversations
        .findByIdAndFamilyIdAndDeletedAtIsNull(id, familyId).orElse(null);
    if (conversation == null) {
      payload.put("conversationId", null);
      errors.add(new AssistantProposalBatch.FieldError("conversationId",
          "Select a conversation from this family"));
      return hintTarget("conversation", payload);
    }
    return new AssistantProposalBatch.TargetSnapshot(
        "conversation", id, conversation.updatedAt(), null);
  }

  private static AssistantProposalBatch.TargetSnapshot hintTarget(
      final String type, final Map<String, Object> payload) {
    return new AssistantProposalBatch.TargetSnapshot(type, null, null,
        text(payload.get("targetHint")));
  }

  private static ObjectId exactTarget(
      final Map<String, Object> payload,
      final String field,
      final List<AssistantProposalBatch.FieldError> errors) {
    final String value = text(payload.get(field));
    if (value == null || !ObjectId.isValid(value)) {
      payload.put(field, null);
      errors.add(new AssistantProposalBatch.FieldError(field, "Select an exact target"));
      return null;
    }
    return new ObjectId(value);
  }

  private static void requireText(
      final Map<String, Object> payload,
      final String field,
      final List<AssistantProposalBatch.FieldError> errors) {
    if (text(payload.get(field)) == null) {
      errors.add(new AssistantProposalBatch.FieldError(field, "This field is required"));
    }
  }

  private static void requiredFamilyId(
      final Map<String, Object> payload,
      final String field,
      final Set<ObjectId> allowed,
      final List<AssistantProposalBatch.FieldError> errors) {
    final String value = text(payload.get(field));
    if (value == null || !ObjectId.isValid(value) || !allowed.contains(new ObjectId(value))) {
      payload.put(field, null);
      errors.add(new AssistantProposalBatch.FieldError(field,
          "Select a record from this family"));
    }
  }

  private static void optionalFamilyId(
      final Map<String, Object> payload,
      final String field,
      final Set<ObjectId> allowed,
      final List<AssistantProposalBatch.FieldError> errors) {
    if (text(payload.get(field)) != null) {
      requiredFamilyId(payload, field, allowed, errors);
    }
  }

  private static void requiredFamilyIds(
      final Map<String, Object> payload,
      final String field,
      final Set<ObjectId> allowed,
      final List<AssistantProposalBatch.FieldError> errors) {
    final List<String> values = stringList(payload.get(field));
    if (values.isEmpty() || values.stream().anyMatch(value -> !ObjectId.isValid(value)
        || !allowed.contains(new ObjectId(value)))) {
      payload.put(field, List.of());
      errors.add(new AssistantProposalBatch.FieldError(field,
          "Select at least one record from this family"));
    }
  }

  private static void optionalFamilyIds(
      final Map<String, Object> payload,
      final String field,
      final Set<ObjectId> allowed,
      final List<AssistantProposalBatch.FieldError> errors) {
    final List<String> values = stringList(payload.get(field));
    if (!values.isEmpty() && values.stream().anyMatch(value -> !ObjectId.isValid(value)
        || !allowed.contains(new ObjectId(value)))) {
      payload.put(field, List.of());
      errors.add(new AssistantProposalBatch.FieldError(field,
          "Select records from this family only"));
    }
  }

  private static List<String> stringList(final Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }

  private static String text(final Object value) {
    return value instanceof String string && !string.isBlank() ? string.trim() : null;
  }

  private static void requireRevision(
      final AssistantProposalBatch.Action action, final long version) {
    if (action.revision() != version) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The action was changed in another request");
    }
  }

  private void requireEnabled() {
    if (!enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assistant is unavailable");
    }
  }

  private static Map<String, AssistantProposalBatch.ActionType> toolTypes() {
    final Map<String, AssistantProposalBatch.ActionType> result = new HashMap<>();
    for (final AssistantProposalBatch.ActionType type
        : AssistantProposalBatch.ActionType.values()) {
      result.put(toolName(type), type);
    }
    return Map.copyOf(result);
  }

  private static Map<AssistantProposalBatch.ActionType, Set<String>> payloadFields() {
    final Map<AssistantProposalBatch.ActionType, Set<String>> result = new EnumMap<>(
        AssistantProposalBatch.ActionType.class);
    final Set<String> event = Set.of("type", "title", "startDate", "endDate", "startTime",
        "endTime", "allDay", "parentId", "parentIds", "childIds", "location", "notes",
        "recurringFrequency", "recurringDays");
    result.put(AssistantProposalBatch.ActionType.CREATE_EVENT, event);
    final Set<String> updateEvent = new java.util.HashSet<>(event);
    updateEvent.add("eventId");
    updateEvent.add("targetHint");
    result.put(AssistantProposalBatch.ActionType.UPDATE_EVENT, Set.copyOf(updateEvent));
    result.put(AssistantProposalBatch.ActionType.DELETE_EVENT, Set.of("eventId", "targetHint"));
    result.put(AssistantProposalBatch.ActionType.CREATE_CATEGORY,
        Set.of("name", "icon", "color"));
    result.put(AssistantProposalBatch.ActionType.UPDATE_CATEGORY,
        Set.of("categoryId", "targetHint", "name", "icon", "color"));
    result.put(AssistantProposalBatch.ActionType.DELETE_CATEGORY,
        Set.of("categoryId", "targetHint"));
    result.put(AssistantProposalBatch.ActionType.CREATE_SCHEDULE_CHANGE,
        Set.of("originalEventId", "type", "originalStartDate", "originalEndDate",
            "newStartDate", "newEndDate", "reason"));
    result.put(AssistantProposalBatch.ActionType.WITHDRAW_SCHEDULE_CHANGE,
        Set.of("requestId", "targetHint"));
    result.put(AssistantProposalBatch.ActionType.START_MESSAGE_CONVERSATION,
        Set.of("recipientId", "subject", "message"));
    result.put(AssistantProposalBatch.ActionType.SEND_MESSAGE,
        Set.of("conversationId", "targetHint", "message"));
    result.put(AssistantProposalBatch.ActionType.CREATE_PERMISSION_REQUEST,
        Set.of("subject", "type", "childId", "description"));
    return Map.copyOf(result);
  }

  private static String toolName(final AssistantProposalBatch.ActionType type) {
    return "propose_" + type.name().toLowerCase(java.util.Locale.ROOT);
  }
}
