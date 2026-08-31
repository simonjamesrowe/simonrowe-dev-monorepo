package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One implementation follows every module's runs, because every factory workflow answers the same
 * {@code progress} query. These tests pin the two facts that must stay independent: Temporal's
 * execution status, which is the only thing that can say a run stopped, and the workflow's
 * self-reported phase, which is the only thing that can say where it got to.
 */
class FactoryRunStatusServiceTest {

  private static final String WORKFLOW_ID = "cve-scan-manual-1";
  private static final String RUN_ID = "run-1";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void reportsPhaseAndStatusForRunningWorkflow() {
    FactoryRunStatusService service =
        service(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
            "{\"phase\":\"FILING\",\"detail\":\"Filing in Linear\",\"count\":3}");

    FactoryRunProgress progress = service.progress(WORKFLOW_ID);

    assertThat(progress.workflowId()).isEqualTo(WORKFLOW_ID);
    assertThat(progress.runId()).isEqualTo(RUN_ID);
    assertThat(progress.executionStatus()).isEqualTo("WORKFLOW_EXECUTION_STATUS_RUNNING");
    assertThat(progress.phase()).isEqualTo("FILING");
    assertThat(progress.detail()).isEqualTo("Filing in Linear");
    assertThat(progress.terminal()).isFalse();
  }

  @Test
  void ignoresTheExtraFieldsEachModuleAddsToItsOwnProgress() {
    // Every module's progress record carries a third field of its own — count, lessonCount, sha,
    // dryRun — so the shared reader must not be a narrow typed deserialisation of any one of them.
    FactoryRunStatusService service =
        service(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
            "{\"phase\":\"running\",\"detail\":\"Capturing\",\"dryRun\":true}");

    assertThat(service.progress(WORKFLOW_ID).phase()).isEqualTo("running");
  }

  @Test
  void marksEveryClosedStatusTerminalSoThePageStopsPolling() {
    for (WorkflowExecutionStatus status : new WorkflowExecutionStatus[] {
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW}) {
      assertThat(service(status, "{\"phase\":\"COMPLETED\"}").progress(WORKFLOW_ID).terminal())
          .as("%s", status)
          .isTrue();
    }
  }

  @Test
  void stillReportsTheStatusOfRunThatCannotAnswerQuery() {
    // A failed or terminated workflow cannot be queried. That is a partial answer, not an error:
    // "it failed" is the single most useful thing the page can say, so losing it to an exception
    // would be the worst possible trade.
    FactoryRunStatusService service =
        serviceWithFailingQuery(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

    FactoryRunProgress progress = service.progress(WORKFLOW_ID);

    assertThat(progress.executionStatus()).isEqualTo("WORKFLOW_EXECUTION_STATUS_FAILED");
    assertThat(progress.phase()).isNull();
    assertThat(progress.detail()).isNull();
    assertThat(progress.terminal()).isTrue();
  }

  @Test
  void reportsAnAbsentRunAsNotFound() {
    assertThatThrownBy(() -> unknownRun().progress("no-such-run"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static FactoryRunStatusService service(
      final WorkflowExecutionStatus status, final String progressJson) {
    WorkflowClient client = describing(status);
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);
    when(stub.query(eq("progress"), eq(JsonNode.class))).thenReturn(read(progressJson));
    return new FactoryRunStatusService(client);
  }

  private static FactoryRunStatusService serviceWithFailingQuery(
      final WorkflowExecutionStatus status) {
    WorkflowClient client = describing(status);
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(WORKFLOW_ID)).thenReturn(stub);
    when(stub.query(eq("progress"), eq(JsonNode.class)))
        .thenThrow(new IllegalStateException("workflow is not running"));
    return new FactoryRunStatusService(client);
  }

  private static FactoryRunStatusService unknownRun() {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceBlockingStub blockingStub = mock(WorkflowServiceBlockingStub.class);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeWorkflowExecution(any()))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
    return new FactoryRunStatusService(client);
  }

  private static WorkflowClient describing(final WorkflowExecutionStatus status) {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceBlockingStub blockingStub = mock(WorkflowServiceBlockingStub.class);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeWorkflowExecution(any()))
        .thenReturn(
            DescribeWorkflowExecutionResponse.newBuilder()
                .setWorkflowExecutionInfo(
                    WorkflowExecutionInfo.newBuilder()
                        .setStatus(status)
                        .setExecution(
                            WorkflowExecution.newBuilder()
                                .setWorkflowId(WORKFLOW_ID)
                                .setRunId(RUN_ID)
                                .build())
                        .build())
                .build());
    return client;
  }

  private static JsonNode read(final String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
