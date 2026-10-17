package com.simonrowe.factory.writing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.writing.config.WritingTaskQueues;
import com.simonrowe.factory.writing.domain.WritingResult;
import com.simonrowe.factory.writing.workflow.ContentWritingActivities;
import com.simonrowe.factory.writing.workflow.ContentWritingWorkflowImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the service against Temporal's in-process test server, because idempotency here is
 * entirely the server's workflow-id reuse policy and a mock would only restate the assumption.
 */
class ContentWritingServiceTest {

  private TestWorkflowEnvironment environment;
  private ContentWritingService service;
  private final AtomicInteger runs = new AtomicInteger();
  private final AtomicBoolean failing = new AtomicBoolean();
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicBoolean holding = new AtomicBoolean();

  @BeforeEach
  void startEnvironment() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(WritingTaskQueues.CONTENT_WRITING);
    worker.registerWorkflowImplementationTypes(ContentWritingWorkflowImpl.class);
    ContentWritingActivities activities =
        request -> {
          runs.incrementAndGet();
          if (holding.get()) {
            awaitRelease();
          }
          if (failing.get()) {
            throw new IllegalStateException("rate limited");
          }
          return new WritingResult("Text for " + request.jobId());
        };
    worker.registerActivitiesImplementations(activities);
    environment.start();
    service = new ContentWritingService(environment.getWorkflowClient());
  }

  @AfterEach
  void closeEnvironment() {
    release.countDown();
    environment.close();
  }

  private void awaitRelease() {
    try {
      // Bounded so a broken test cannot hang the suite.
      release.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /** Blocks until the job's run closes, letting the test server skip retry back-off. */
  private void awaitClose(final String jobId) {
    WorkflowStub stub =
        environment
            .getWorkflowClient()
            .newUntypedWorkflowStub(ContentWritingService.workflowId(jobId));
    try {
      stub.getResult(WritingResult.class);
    } catch (WorkflowFailedException expectedForFailingRuns) {
      // The status read below is what is under test.
    }
  }

  @Test
  void unknownJobIsNotFound() {
    assertThat(service.status("never-submitted").status()).isEqualTo(WritingJobState.NOT_FOUND);
  }

  @Test
  void startsJobAndThenReportsItsText() {
    ContentWritingService.Submission first = service.submit("job-1", "prompt");

    assertThat(first.started()).isTrue();
    assertThat(first.status().status()).isEqualTo(WritingJobState.RUNNING);
    awaitClose("job-1");
    WritingJobStatus done = service.status("job-1");
    assertThat(done.status()).isEqualTo(WritingJobState.COMPLETED);
    assertThat(done.text()).isEqualTo("Text for job-1");
  }

  @Test
  void resubmittingCompletedJobReturnsItsTextWithoutRunningAgain() {
    service.submit("job-1", "prompt");
    awaitClose("job-1");

    ContentWritingService.Submission again = service.submit("job-1", "prompt");

    assertThat(again.started()).isFalse();
    assertThat(again.status().status()).isEqualTo(WritingJobState.COMPLETED);
    assertThat(again.status().text()).isEqualTo("Text for job-1");
    assertThat(runs).hasValue(1);
  }

  @Test
  void resubmittingRunningJobReportsItRunning() {
    holding.set(true);
    service.submit("job-1", "prompt");

    ContentWritingService.Submission again = service.submit("job-1", "prompt");

    assertThat(again.started()).isFalse();
    assertThat(again.status().status()).isEqualTo(WritingJobState.RUNNING);
    assertThat(again.status().text()).isNull();
  }

  @Test
  void failedJobReadsAsFailedAndMayBeRestarted() {
    failing.set(true);
    service.submit("job-1", "prompt");
    awaitClose("job-1");

    assertThat(service.status("job-1").status()).isEqualTo(WritingJobState.FAILED);
    assertThat(service.status("job-1").text()).isNull();

    failing.set(false);
    ContentWritingService.Submission retried = service.submit("job-1", "prompt");
    assertThat(retried.started()).isTrue();
    awaitClose("job-1");
    assertThat(service.status("job-1").status()).isEqualTo(WritingJobState.COMPLETED);
  }
}
