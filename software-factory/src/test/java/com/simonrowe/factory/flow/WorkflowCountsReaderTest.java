package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.flow.domain.NodeCounts;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
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

  /** The regression: Temporal throttles a burst of counts, and one refusal hid a whole node. */
  @Test
  void retriesThrottledCountsAfterPausing() {
    WorkflowServiceBlockingStub stub = mock(WorkflowServiceBlockingStub.class);
    StatusRuntimeException throttled = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
    when(stub.countWorkflowExecutions(any()))
        .thenThrow(throttled)
        .thenReturn(CountWorkflowExecutionsResponse.newBuilder().setCount(2).build());
    List<Duration> pauses = new ArrayList<>();

    NodeCounts counts =
        new WorkflowCountsReader(clientWith(stub), pauses::add).countsFor("TicketAgentWorkflow");

    assertThat(counts).isEqualTo(new NodeCounts(2, 2, 2));
    assertThat(pauses).containsExactly(Duration.ofMillis(300));
  }

  @Test
  void givesUpOnPersistentThrottlingAndOnOtherErrorsAtOnce() {
    WorkflowServiceBlockingStub throttledStub = mock(WorkflowServiceBlockingStub.class);
    when(throttledStub.countWorkflowExecutions(any()))
        .thenThrow(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED));
    List<Duration> pauses = new ArrayList<>();
    assertThat(new WorkflowCountsReader(clientWith(throttledStub), pauses::add)
        .countsFor("TicketAgentWorkflow")).isNull();
    assertThat(pauses).hasSize(WorkflowCountsReader.THROTTLE_RETRIES);

    WorkflowServiceBlockingStub downStub = mock(WorkflowServiceBlockingStub.class);
    when(downStub.countWorkflowExecutions(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    List<Duration> none = new ArrayList<>();
    assertThat(new WorkflowCountsReader(clientWith(downStub), none::add)
        .countsFor("TicketAgentWorkflow")).isNull();
    assertThat(none).isEmpty();
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
