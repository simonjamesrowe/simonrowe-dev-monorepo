package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowCountsReaderTest {

  @Test
  void countsRunningSucceededAndFailedSeparately() {
    List<String> queries = new ArrayList<>();
    WorkflowCountsReader reader = readerReturning(queries, 3L, 7L, 1L);

    NodeCounts counts = reader.countsFor("LogWatchWorkflow");

    assertThat(counts).isEqualTo(new NodeCounts(3, 7, 1));
    assertThat(queries).hasSize(3);
    assertThat(queries).allMatch(query -> query.contains("WorkflowType = 'LogWatchWorkflow'"));
    assertThat(queries.get(0)).contains("ExecutionStatus = 'Running'");
    assertThat(queries.get(1)).contains("ExecutionStatus = 'Completed'").contains("StartTime >");
    assertThat(queries.get(2)).contains("ExecutionStatus = 'Failed'").contains("StartTime >");
  }

  @Test
  void doesNotBoundRunningQueryByStartTime() {
    // A deploy started 26 hours ago and still running is in flight NOW. Applying the 24-hour
    // window to the running query would hide exactly the run an operator is looking for.
    List<String> queries = new ArrayList<>();
    readerReturning(queries, 1L, 0L, 0L).countsFor("DeployWorkflow");

    assertThat(queries.get(0)).doesNotContain("StartTime");
  }

  @Test
  void returnsNullWhenTemporalCannotBeReached() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    when(stub.countWorkflowExecutions(any())).thenThrow(new RuntimeException("unavailable"));

    assertThat(new WorkflowCountsReader(clientWith(stub)).countsFor("LogWatchWorkflow")).isNull();
  }

  private WorkflowCountsReader readerReturning(
      final List<String> capturedQueries, final long running, final long ok, final long failed) {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    List<Long> answers = List.of(running, ok, failed);
    when(stub.countWorkflowExecutions(any())).thenAnswer(invocation -> {
      CountWorkflowExecutionsRequest request = invocation.getArgument(0);
      capturedQueries.add(request.getQuery());
      return CountWorkflowExecutionsResponse.newBuilder()
          .setCount(answers.get(capturedQueries.size() - 1))
          .build();
    });
    return new WorkflowCountsReader(clientWith(stub));
  }

  private WorkflowClient clientWith(final WorkflowServiceBlockingStub stub) {
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    when(stubs.blockingStub()).thenReturn(stub);
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    return client;
  }
}
