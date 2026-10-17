package com.simonrowe.factory.writing.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.writing.config.WritingTaskQueues;
import com.simonrowe.factory.writing.domain.WritingPhase;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContentWritingWorkflowTest {

  private static final WritingRequest REQUEST = new WritingRequest("job-1", "Write a note.");

  private TestWorkflowEnvironment environment;

  @BeforeEach
  void startEnvironment() {
    environment = TestWorkflowEnvironment.newInstance();
  }

  @AfterEach
  void closeEnvironment() {
    environment.close();
  }

  private ContentWritingWorkflow workflow(final ContentWritingActivities activities) {
    Worker worker = environment.newWorker(WritingTaskQueues.CONTENT_WRITING);
    worker.registerWorkflowImplementationTypes(ContentWritingWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    environment.start();
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            ContentWritingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WritingTaskQueues.CONTENT_WRITING)
                .setWorkflowId("writing-job-1")
                // A backstop so a regression here fails the test instead of hanging it.
                .setWorkflowExecutionTimeout(Duration.ofMinutes(30))
                .build());
  }

  @Test
  void returnsTheWrittenTextAndReportsCompleted() {
    ContentWritingWorkflow workflow = workflow(request -> new WritingResult("A note."));

    WritingResult result = workflow.write(REQUEST);

    assertThat(result.text()).isEqualTo("A note.");
    assertThat(workflow.progress().phase()).isEqualTo(WritingPhase.COMPLETED);
    assertThat(workflow.progress().characters()).isEqualTo(7);
  }

  @Test
  void triesTwiceThenFailsTheWorkflow() {
    // The caller has a fallback, so this gives up quickly rather than hammering a rate-limited
    // subscription: exactly two attempts, then a failed workflow the caller can see.
    AtomicInteger attempts = new AtomicInteger();
    ContentWritingWorkflow workflow =
        workflow(
            request -> {
              attempts.incrementAndGet();
              throw new IllegalStateException("Claude exited with 1");
            });

    assertThatThrownBy(() -> workflow.write(REQUEST)).isInstanceOf(WorkflowFailedException.class);
    assertThat(attempts).hasValue(2);
    assertThat(workflow.progress().phase()).isEqualTo(WritingPhase.FAILED);
  }

  @Test
  void succeedsOnTheSecondAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    ContentWritingWorkflow workflow =
        workflow(
            request -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
              }
              return new WritingResult("Second time.");
            });

    assertThat(workflow.write(REQUEST).text()).isEqualTo("Second time.");
    assertThat(attempts).hasValue(2);
  }
}
