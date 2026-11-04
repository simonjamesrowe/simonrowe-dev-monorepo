package com.simonrowe.factory.pageread.api;

import com.simonrowe.factory.pageread.config.PageReadTaskQueues;
import com.simonrowe.factory.pageread.domain.PageReadInput;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import com.simonrowe.factory.pageread.workflow.PageReadWorkflow;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts a read and waits for it, keyed on the address.
 *
 * <p>The workflow id is {@code page-read-<sha256 of the url>}, under
 * {@code ALLOW_DUPLICATE_FAILED_ONLY}: a second request for the same address while the first is
 * running waits on that same run, and one after it completed gets its result without launching
 * a browser again, for as long as Temporal retains the run. A failed read may be started again.
 */
@Service
public class PageReadService {

  /** Bounds the whole run: two attempts of two minutes, plus a worker to pick them up. */
  static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(5);

  private static final String WORKFLOW_PREFIX = "page-read-";

  private final WorkflowClient client;
  private final Supplier<Integer> readerPollers;

  @Autowired
  public PageReadService(final WorkflowClient client) {
    this(client, () -> activityPollers(client, PageReadTaskQueues.PAGE_READER));
  }

  PageReadService(final WorkflowClient client, final Supplier<Integer> readerPollers) {
    this.client = client;
    this.readerPollers = readerPollers;
  }

  /**
   * Reads the page, waiting up to {@code wait} for it.
   *
   * @param url the validated address
   * @param wait how long to wait before answering {@code RUNNING}
   * @return the read's state, with its result once completed
   * @throws ResponseStatusException 503 when Temporal cannot be asked
   */
  public PageReadResponse read(final String url, final Duration wait) {
    // With no page-reader polling, a read would sit on its queue for the whole wait and the
    // backend would fall back only after it: refuse at once instead. Unknown (Temporal could not
    // say) is not refused, because the wait still bounds it.
    Integer pollers = readerPollers.get();
    if (pollers != null && pollers == 0) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "No page-reader is polling the page-reader queue");
    }
    String workflowId = workflowId(url);
    PageReadWorkflow workflow =
        client.newWorkflowStub(
            PageReadWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(PageReadTaskQueues.PAGE_READ)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowExecutionTimeout(EXECUTION_TIMEOUT)
                .build());
    try {
      WorkflowClient.start(workflow::read, new PageReadInput(url));
    } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
      // Running or completed under this address already: wait on that run instead.
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
    try {
      PageReadResult result =
          stub.getResult(wait.toMillis(), TimeUnit.MILLISECONDS, PageReadResult.class);
      return new PageReadResponse(PageReadState.COMPLETED, result);
    } catch (TimeoutException stillRunning) {
      return PageReadResponse.of(PageReadState.RUNNING);
    } catch (WorkflowFailedException failed) {
      return PageReadResponse.of(PageReadState.FAILED);
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  /** Activity pollers on a queue, or null when Temporal cannot say. */
  private static Integer activityPollers(final WorkflowClient client, final String queue) {
    try {
      return client.getWorkflowServiceStubs().blockingStub().describeTaskQueue(
              DescribeTaskQueueRequest.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .setTaskQueue(TaskQueue.newBuilder().setName(queue).build())
                  .setTaskQueueType(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY)
                  .build())
          .getPollersCount();
    } catch (RuntimeException unknown) {
      return null;
    }
  }

  private static ResponseStatusException unavailable(final Exception cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Temporal could not be asked about the read", cause);
  }

  static String workflowId(final String url) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
      return WORKFLOW_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }
}
