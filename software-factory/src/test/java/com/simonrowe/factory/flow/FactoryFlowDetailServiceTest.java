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
  void neverSendsAnOrderByClauseTemporalStandardVisibilityRejects() {
    // Verified against a real local Temporal server: standard (SQL) visibility answers
    // "operation is not supported: 'ORDER BY' clause" to this exact clause, and production runs
    // the same Postgres-backed standard visibility. Standard visibility already returns
    // executions newest-first with no ORDER BY at all, so the clause was never needed - it just
    // broke every drawer silently. This test would have caught that: it inspects the actual
    // query string rather than only mocking a response.
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenAnswer(invocation -> {
      ListWorkflowExecutionsRequest request = invocation.getArgument(0);
      assertThat(request.getQuery()).doesNotContain("ORDER BY");
      assertThat(request.getQuery()).isEqualTo("WorkflowType = 'DeployWorkflow'");
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
  void returnsNullItemsWhenTemporalCannotBeReached() {
    // Distinct from returnsAnEmptyDetailForAnUnknownNodeKey: an unreadable Temporal must render
    // as "could not read this", never as "nothing running" - the exact self-contradicting drawer
    // already fixed once for the deployer, now on the module path.
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.listWorkflowExecutions(any())).thenThrow(new RuntimeException("unavailable"));

    assertThat(service(stub).detail("logwatch").items()).isNull();
  }

  @Test
  void routesTheLinearNodeToArtifactCountsReader() {
    ArtifactCountsReader counts = mock(ArtifactCountsReader.class);
    when(counts.linearItems()).thenReturn(List.of(
        new FlowDetail.Item("SIM-1", "openssl", "TRIAGE", null, "https://linear.app/sim-1")));

    FlowDetail detail = service(mock(WorkflowServiceBlockingStub.class), counts).detail("linear");

    assertThat(detail.items()).extracting(FlowDetail.Item::id).containsExactly("SIM-1");
  }

  @Test
  void routesPullRequestMainAndAgentSetupToTheirOwnReaders() {
    ArtifactCountsReader counts = mock(ArtifactCountsReader.class);
    when(counts.pullRequestItems()).thenReturn(List.of(
        new FlowDetail.Item("#7", "Fix flake", "open", null, "https://github.com/x/pull/7")));
    when(counts.mainItems()).thenReturn(List.of(
        new FlowDetail.Item("abcdef1", "fix: thing", "merged", null, "https://github.com/x")));
    when(counts.agentSetupItems()).thenReturn(List.of());

    FactoryFlowDetailService service = service(mock(WorkflowServiceBlockingStub.class), counts);

    assertThat(service.detail("pull-request").items())
        .extracting(FlowDetail.Item::id).containsExactly("#7");
    assertThat(service.detail("main").items())
        .extracting(FlowDetail.Item::id).containsExactly("abcdef1");
    assertThat(service.detail("agent-setup").items()).isEmpty();
  }

  @Test
  void surfacesNullArtifactReaderResultsAsNotAvailable() {
    // The whole distinction this feature rests on: null means "could not read this source",
    // empty means "read it and found nothing open". Collapsing the two here would misreport a
    // broken Linear read as a quiet one.
    ArtifactCountsReader counts = mock(ArtifactCountsReader.class);
    when(counts.linearItems()).thenReturn(null);

    FlowDetail detail = service(mock(WorkflowServiceBlockingStub.class), counts).detail("linear");

    assertThat(detail.items()).isNull();
  }

  @Test
  void reportsGenuinelyEmptyArtifactListsAsEmptyNotNull() {
    ArtifactCountsReader counts = mock(ArtifactCountsReader.class);
    when(counts.pullRequestItems()).thenReturn(List.of());

    FlowDetail detail =
        service(mock(WorkflowServiceBlockingStub.class), counts).detail("pull-request");

    assertThat(detail.items()).isNotNull().isEmpty();
  }

  private FactoryFlowDetailService service(final WorkflowServiceBlockingStub stub) {
    return service(stub, mock(ArtifactCountsReader.class));
  }

  private FactoryFlowDetailService service(
      final WorkflowServiceBlockingStub stub, final ArtifactCountsReader counts) {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    when(stubs.blockingStub()).thenReturn(stub);
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    return new FactoryFlowDetailService(client, counts);
  }

  private static WorkflowExecutionInfo execution(
      final String id, final WorkflowExecutionStatus status) {
    return WorkflowExecutionInfo.newBuilder()
        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(id).build())
        .setStatus(status)
        .build();
  }
}
