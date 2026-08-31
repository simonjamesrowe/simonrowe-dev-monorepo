package com.simonrowe.factory.logwatch.api;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts and queries manually requested log-watch scans. */
@Service
public class LogWatchWorkflowService {

  private final WorkflowClient client;
  private final LinearProperties linear;
  private final LogWatchProperties properties;

  /**
   * Creates the service.
   *
   * @param client the Temporal client
   * @param linear the Linear sink's configuration, whose enabled flag travels on the request
   * @param properties the module's configuration
   */
  public LogWatchWorkflowService(
      final WorkflowClient client,
      final LinearProperties linear,
      final LogWatchProperties properties) {
    this.client = client;
    this.linear = linear;
    this.properties = properties;
  }

  /**
   * Starts a scan.
   *
   * <p>The workflow id is always unique, so a manual scan of a window that has already been
   * scanned is never rejected as a duplicate. An operator asking for a re-scan means it, and a
   * {@code 409} there is indistinguishable from the module being wedged — the same reasoning that
   * makes the console's manual code-review trigger omit {@code expectedHeadSha}.
   *
   * @param windowStart window start, or null for the configured default window
   * @param windowEnd window end, or null for now
   * @param dryRun when true, nothing is created or commented on in Linear
   * @return the acknowledgement, carrying the ids needed to follow the run
   */
  public LogWatchScanAccepted start(
      final Instant windowStart, final Instant windowEnd, final boolean dryRun) {
    String workflowId = "logwatch-manual-" + UUID.randomUUID();
    LogWatchWorkflow workflow =
        client.newWorkflowStub(
            LogWatchWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(LogWatchTaskQueues.LOG_WATCH)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    Instant end = windowEnd == null ? Instant.now() : windowEnd;
    Instant start =
        windowStart == null ? end.minus(properties.defaultWindow()) : windowStart;
    WorkflowExecution execution =
        WorkflowClient.start(
            workflow::run,
            new LogWatchRequest(
                start,
                end,
                dryRun ? Trigger.DRY_RUN : Trigger.MANUAL,
                dryRun,
                linear.enabled()));
    return new LogWatchScanAccepted(
        execution.getWorkflowId(),
        execution.getRunId(),
        dryRun ? "Dry-run log scan accepted; nothing will be filed" : "Log scan accepted");
  }

  /**
   * Reads a running scan's progress.
   *
   * @param workflowId the workflow to query
   * @return the current progress snapshot
   */
  public LogWatchProgress progress(final String workflowId) {
    return client.newWorkflowStub(LogWatchWorkflow.class, workflowId).progress();
  }
}
