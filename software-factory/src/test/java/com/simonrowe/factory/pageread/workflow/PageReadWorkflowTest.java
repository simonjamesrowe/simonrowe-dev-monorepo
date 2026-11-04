package com.simonrowe.factory.pageread.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.pageread.config.PageReadTaskQueues;
import com.simonrowe.factory.pageread.domain.PageReadInput;
import com.simonrowe.factory.pageread.domain.PageReadOutcome;
import com.simonrowe.factory.pageread.domain.PageReadPhase;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The workflow on its own queue, the activity on the page-reader's, as in production. A Java
 * stand-in plays the Node worker; what it cannot check is the activity's registered name, which
 * {@code PageReadActivitiesContractTest} pins against the Node source.
 */
class PageReadWorkflowTest {

  private static final PageReadInput INPUT =
      new PageReadInput("https://openai.com/index/scaling-git-at-openai/");

  private TestWorkflowEnvironment environment;

  @BeforeEach
  void startEnvironment() {
    environment = TestWorkflowEnvironment.newInstance();
  }

  @AfterEach
  void closeEnvironment() {
    environment.close();
  }

  private PageReadWorkflow workflow(final PageReadActivities reader) {
    environment.newWorker(PageReadTaskQueues.PAGE_READ)
        .registerWorkflowImplementationTypes(PageReadWorkflowImpl.class);
    Worker pageReader = environment.newWorker(PageReadTaskQueues.PAGE_READER);
    pageReader.registerActivitiesImplementations(reader);
    environment.start();
    return environment.getWorkflowClient().newWorkflowStub(
        PageReadWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(PageReadTaskQueues.PAGE_READ)
            .setWorkflowId("page-read-test")
            .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
            .build());
  }

  @Test
  void returnsTheArticleAndReportsCompleted() {
    PageReadWorkflow workflow = workflow(input -> new PageReadResult(
        PageReadOutcome.READ, 200, input.url(), "Scaling Git at OpenAI", "Over the past year..."));

    PageReadResult result = workflow.read(INPUT);

    assertThat(result.text()).isEqualTo("Over the past year...");
    assertThat(workflow.progress().phase()).isEqualTo(PageReadPhase.COMPLETED);
    assertThat(workflow.progress().outcome()).isEqualTo(PageReadOutcome.READ);
  }

  /** A challenge that never cleared is an answer: the same page would answer the same way. */
  @Test
  void blockedPageIsResultAndIsNotRetried() {
    AtomicInteger attempts = new AtomicInteger();
    PageReadWorkflow workflow = workflow(input -> {
      attempts.incrementAndGet();
      return new PageReadResult(
          PageReadOutcome.BLOCKED, 403, input.url(), "Just a moment...", null);
    });

    assertThat(workflow.read(INPUT).outcome()).isEqualTo(PageReadOutcome.BLOCKED);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void retriesCrashOnceThenFails() {
    AtomicInteger attempts = new AtomicInteger();
    PageReadWorkflow workflow = workflow(input -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("browser crashed");
    });

    assertThatThrownBy(() -> workflow.read(INPUT)).isInstanceOf(WorkflowFailedException.class);
    assertThat(attempts).hasValue(2);
    assertThat(workflow.progress().phase()).isEqualTo(PageReadPhase.FAILED);
  }

  /** The Node worker throws this type for an address that is not an article URL. */
  @Test
  void neverRetriesAnUnreadableUrl() {
    AtomicInteger attempts = new AtomicInteger();
    PageReadWorkflow workflow = workflow(input -> {
      attempts.incrementAndGet();
      throw ApplicationFailure.newFailure("not an http(s) URL", "UnreadableUrl");
    });

    assertThatThrownBy(() -> workflow.read(INPUT)).isInstanceOf(WorkflowFailedException.class);
    assertThat(attempts).hasValue(1);
  }
}
