package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FactoryFlowDetailService} is the one place a node's own recent runs are read, never
 * every module's — see {@link #asksTemporalForTheNodesOwnWorkflowTypeOnly()}.
 */
class FactoryFlowDetailServiceTest {

  @Test
  void listsTheModulesRecentRunsNewestFirst() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenReturn(
        ListWorkflowExecutionsResponse.newBuilder()
            .addExecutions(execution(
                "logwatch-2", WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED))
            .addExecutions(execution(
                "logwatch-1", WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED))
            .build());

    FlowDetail detail = service(stub).detail("logwatch");

    assertThat(detail.items()).extracting(FlowDetail.Item::id)
        .containsExactly("logwatch-2", "logwatch-1");
    assertThat(detail.items().get(1).status()).contains("FAILED");
  }

  @Test
  void asksTemporalForTheNodesOwnWorkflowTypeOnly() {
    // A drawer that listed every module's runs would be worse than useless: the operator opened
    // one node.
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenAnswer(invocation -> {
      ListWorkflowExecutionsRequest request = invocation.getArgument(0);
      assertThat(request.getQuery()).contains("WorkflowType = 'DeployWorkflow'");
      return ListWorkflowExecutionsResponse.getDefaultInstance();
    });

    service(stub).detail("deploy");
  }

  @Test
  void returnsAnEmptyDetailForTheNodeWithNoWorkflowType() {
    // Artifact nodes are handled by their own branch; an unknown key must not throw and take the
    // whole drawer down.
    assertThat(service(mock(WorkflowServiceBlockingStub.class)).detail("production").items())
        .isEmpty();
  }

  @Test
  void returnsAnEmptyDetailForAnUnknownNodeKey() {
    assertThat(service(mock(WorkflowServiceBlockingStub.class)).detail("not-a-node").items())
        .isEmpty();
  }

  @Test
  void returnsAnEmptyDetailWhenTemporalCannotBeReached() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenThrow(new RuntimeException("unavailable"));

    assertThat(service(stub).detail("logwatch").items()).isEmpty();
  }

  private FactoryFlowDetailService service(final WorkflowServiceBlockingStub stub) {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    when(stubs.blockingStub()).thenReturn(stub);
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    return new FactoryFlowDetailService(client);
  }

  private static WorkflowExecutionInfo execution(
      final String id, final WorkflowExecutionStatus status) {
    return WorkflowExecutionInfo.newBuilder()
        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
        .setStatus(status)
        .build();
  }
}
