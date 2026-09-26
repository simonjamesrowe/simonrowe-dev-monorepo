package com.simonrowe.coparent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class AssistantProposalBatchTest {

  @Test
  void retainsStrictSchemaNullsWithoutExposingExecutionMarkers() {
    final ObjectId operationId = new ObjectId();
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", "School fair");
    payload.put("location", null);
    final AssistantProposalBatch.Action stored = new AssistantProposalBatch.Action(
        new ObjectId(), AssistantProposalBatch.ActionType.CREATE_EVENT,
        AssistantProposalBatch.ActionStatus.PENDING,
        payload,
        List.of(), 0, null, null, operationId, null, null, null);

    final AssistantDtos.Action response = AssistantDtos.Action.from(stored);

    assertThat(stored.payload()).containsEntry("location", null);
    assertThat(response).hasNoNullFieldsOrPropertiesExcept(
        "targetSnapshot", "result", "failureMessage");
    assertThat(response.toString()).doesNotContain(operationId.toHexString());
  }
}
