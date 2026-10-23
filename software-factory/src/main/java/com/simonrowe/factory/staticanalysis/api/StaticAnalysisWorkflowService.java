package com.simonrowe.factory.staticanalysis.api;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisTaskQueues;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisProgress;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.Trigger;
import com.simonrowe.factory.staticanalysis.workflow.StaticAnalysisWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts and queries manually requested static-analysis scans. */
@Service
public class StaticAnalysisWorkflowService {

  private final WorkflowClient client;
  private final LinearProperties linear;
  private final StaticAnalysisProperties properties;

  /**
   * Creates the service.
   *
   * @param client the Temporal client
   * @param linear the Linear sink's configuration, whose enabled flag travels on the request
   * @param properties the module's configuration
   */
  public StaticAnalysisWorkflowService(
      final WorkflowClient client,
      final LinearProperties linear,
      final StaticAnalysisProperties properties) {
    this.client = client;
    this.linear = linear;
    this.properties = properties;
  }

  /**
   * Starts a scan under a fresh workflow id, so a re-scan is never rejected as a duplicate.
   *
   * @param dryRun when true, nothing is created, commented on or closed in Linear
   * @return the acknowledgement, carrying the ids needed to follow the run
   */
  public StaticAnalysisScanAccepted start(final boolean dryRun) {
    String workflowId = "static-analysis-manual-" + UUID.randomUUID();
    StaticAnalysisWorkflow workflow =
        client.newWorkflowStub(
            StaticAnalysisWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(StaticAnalysisTaskQueues.STATIC_ANALYSIS)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    WorkflowExecution execution =
        WorkflowClient.start(
            workflow::run,
            new StaticAnalysisRequest(
                dryRun ? Trigger.DRY_RUN : Trigger.MANUAL,
                dryRun,
                linear.enabled(),
                properties.resolveWhenClear(),
                properties.resolveAfter()));
    return new StaticAnalysisScanAccepted(
        execution.getWorkflowId(),
        execution.getRunId(),
        dryRun
            ? "Dry-run static-analysis scan accepted; nothing will be filed"
            : "Static-analysis scan accepted");
  }

  /**
   * Reads a running scan's progress.
   *
   * @param workflowId the workflow to query
   * @return the current progress snapshot
   */
  public StaticAnalysisProgress progress(final String workflowId) {
    return client.newWorkflowStub(StaticAnalysisWorkflow.class, workflowId).progress();
  }
}
