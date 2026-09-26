package com.simonrowe.coparent.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Browser-facing assistant contract that keeps internal operation markers private. */
public final class AssistantDtos {

  private AssistantDtos() {
  }

  public record BatchSummary(
      String id,
      String familyId,
      AssistantProposalBatch.BatchStatus status,
      int actionCount,
      Instant createdAt,
      Instant expiresAt
  ) {
    static BatchSummary from(final AssistantProposalBatch batch) {
      return new BatchSummary(batch.id().toHexString(), batch.familyId().toHexString(),
          batch.status(), batch.actions().size(), batch.createdAt(), batch.expiresAt());
    }
  }

  public record Batch(
      String id,
      String familyId,
      AssistantProposalBatch.BatchStatus status,
      int actionCount,
      String model,
      List<AssistantProposalBatch.InputKind> inputKinds,
      List<Action> actions,
      Instant createdAt,
      Instant updatedAt,
      Instant expiresAt
  ) {
    static Batch from(final AssistantProposalBatch batch) {
      return new Batch(batch.id().toHexString(), batch.familyId().toHexString(), batch.status(),
          batch.actions().size(), batch.model(), List.copyOf(batch.inputKinds()),
          batch.actions().stream().map(Action::from).toList(), batch.createdAt(),
          batch.updatedAt(), batch.expiresAt());
    }
  }

  public record Action(
      String id,
      AssistantProposalBatch.ActionType actionType,
      AssistantProposalBatch.ActionStatus status,
      Map<String, Object> payload,
      List<AssistantProposalBatch.FieldError> fieldErrors,
      long revision,
      TargetSnapshot targetSnapshot,
      ResultReference result,
      String failureMessage
  ) {
    static Action from(final AssistantProposalBatch.Action action) {
      return new Action(action.id().toHexString(), action.actionType(), action.status(),
          action.payload(), action.fieldErrors(), action.revision(),
          TargetSnapshot.from(action.targetSnapshot()), ResultReference.from(action.result()),
          action.failureMessage());
    }
  }

  public record TargetSnapshot(
      String entityType,
      String entityId,
      Instant observedUpdatedAt,
      String hint
  ) {
    static TargetSnapshot from(final AssistantProposalBatch.TargetSnapshot target) {
      return target == null ? null : new TargetSnapshot(target.entityType(),
          target.entityId() == null ? null : target.entityId().toHexString(),
          target.observedUpdatedAt(), target.hint());
    }
  }

  public record ResultReference(String entityType, String entityId, String route) {
    static ResultReference from(final AssistantProposalBatch.ResultReference result) {
      return result == null ? null : new ResultReference(result.entityType(),
          result.entityId().toHexString(), result.route());
    }
  }

  public record EditAction(
      long version,
      AssistantProposalBatch.ActionType actionType,
      Map<String, Object> payload
  ) {
  }

  public record VersionRequest(long version) {
  }
}
