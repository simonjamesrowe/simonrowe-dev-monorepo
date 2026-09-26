package com.simonrowe.coparent.assistant;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** A short-lived, private set of model-proposed actions awaiting human decisions. */
@Document(AssistantProposalBatch.COLLECTION)
public record AssistantProposalBatch(
    @Id ObjectId id,
    ObjectId familyId,
    String submittedBySubject,
    ObjectId submittedByParentId,
    BatchStatus status,
    String model,
    Set<InputKind> inputKinds,
    List<Action> actions,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt
) {
  public static final String COLLECTION = "assistantproposalbatches";

  public AssistantProposalBatch {
    inputKinds = inputKinds == null ? Set.of() : Set.copyOf(inputKinds);
    actions = actions == null ? List.of() : List.copyOf(actions);
  }

  public enum BatchStatus {
    READY,
    NO_ACTION,
    FAILED
  }

  public enum InputKind {
    TEXT,
    IMAGE
  }

  public enum ActionType {
    CREATE_EVENT,
    UPDATE_EVENT,
    DELETE_EVENT,
    CREATE_CATEGORY,
    UPDATE_CATEGORY,
    DELETE_CATEGORY,
    CREATE_SCHEDULE_CHANGE,
    WITHDRAW_SCHEDULE_CHANGE,
    START_MESSAGE_CONVERSATION,
    SEND_MESSAGE,
    CREATE_PERMISSION_REQUEST
  }

  public enum ActionStatus {
    BLOCKED,
    PENDING,
    APPLYING,
    APPLIED,
    REJECTED,
    FAILED
  }

  /** One typed proposal. The payload contains only normalized editable fields. */
  public record Action(
      @Field("_id") ObjectId id,
      ActionType actionType,
      ActionStatus status,
      Map<String, Object> payload,
      List<FieldError> fieldErrors,
      long revision,
      TargetSnapshot targetSnapshot,
      ResultReference result,
      ObjectId operationId,
      String failureMessage,
      Instant claimedAt,
      Instant decidedAt
  ) {
    public Action {
      payload = payload == null ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
      fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
  }

  public record FieldError(String field, String message) {
  }

  public record TargetSnapshot(
      String entityType,
      ObjectId entityId,
      Instant observedUpdatedAt,
      String hint
  ) {
  }

  public record ResultReference(String entityType, ObjectId entityId, String route) {
  }
}
