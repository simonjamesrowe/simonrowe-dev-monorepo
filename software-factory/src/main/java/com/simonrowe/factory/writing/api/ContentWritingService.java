package com.simonrowe.factory.writing.api;

import com.simonrowe.factory.writing.config.WritingTaskQueues;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import com.simonrowe.factory.writing.workflow.ContentWritingWorkflow;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts and reads writing jobs, keyed on the caller's job id.
 *
 * <p>The job id is the idempotency key, so a caller can submit the same job on every tick of its
 * own schedule without persisting anything: the workflow id is {@code writing-<jobId>}, and
 * {@code ALLOW_DUPLICATE_FAILED_ONLY} means a running or completed job is never started twice
 * while a failed, timed-out or terminated one may be. A caller that wants its fallback to win
 * after a failure must therefore read the status before submitting again, or it will restart the
 * failed job on every tick and never observe the failure.
 */
@Service
public class ContentWritingService {

  /**
   * Bounds the whole job. The backend falls back to OpenAI after the same interval, so a run
   * nobody is waiting for any more does not linger past it.
   */
  static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(30);

  private static final String WORKFLOW_PREFIX = "writing-";

  private final WorkflowClient client;

  public ContentWritingService(final WorkflowClient client) {
    this.client = client;
  }

  /**
   * Starts the job unless it is already running or has already completed.
   *
   * @param jobId the validated job id
   * @param prompt the validated prompt
   * @return whether a run was started, and the job's state afterwards
   */
  public Submission submit(final String jobId, final String prompt) {
    ContentWritingWorkflow workflow =
        client.newWorkflowStub(
            ContentWritingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(WritingTaskQueues.CONTENT_WRITING)
                .setWorkflowId(workflowId(jobId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowExecutionTimeout(EXECUTION_TIMEOUT)
                .build());
    try {
      WorkflowClient.start(workflow::write, new WritingRequest(jobId, prompt));
      return new Submission(true, WritingJobStatus.of(jobId, WritingJobState.RUNNING));
    } catch (WorkflowExecutionAlreadyStarted exception) {
      return new Submission(false, status(jobId));
    }
  }

  /**
   * Reads a job's state, and its text once it has completed.
   *
   * @param jobId the validated job id
   * @return the job's state
   * @throws ResponseStatusException 503 when Temporal cannot be asked
   */
  public WritingJobStatus status(final String jobId) {
    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId(jobId));
    WorkflowExecutionStatus status;
    try {
      status = stub.describe().getStatus();
    } catch (WorkflowNotFoundException exception) {
      return WritingJobStatus.of(jobId, WritingJobState.NOT_FOUND);
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
    return switch (status) {
      case WORKFLOW_EXECUTION_STATUS_RUNNING ->
          WritingJobStatus.of(jobId, WritingJobState.RUNNING);
      case WORKFLOW_EXECUTION_STATUS_COMPLETED ->
          new WritingJobStatus(jobId, WritingJobState.COMPLETED, resultOf(stub).text());
      default -> WritingJobStatus.of(jobId, WritingJobState.FAILED);
    };
  }

  private static WritingResult resultOf(final WorkflowStub stub) {
    try {
      // Closed already, so this returns at once; the bound only guards a misread status.
      return stub.getResult(5, TimeUnit.SECONDS, WritingResult.class);
    } catch (TimeoutException | RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private static ResponseStatusException unavailable(final Exception cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Temporal could not be asked about the job", cause);
  }

  static String workflowId(final String jobId) {
    return WORKFLOW_PREFIX + jobId;
  }

  /**
   * The outcome of a submit.
   *
   * @param started true when this call started a run
   * @param status the job's state afterwards
   */
  public record Submission(boolean started, WritingJobStatus status) {
  }
}
