package com.simonrowe.factory.deploy.api;

import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.workflow.DeployWorkflow;
import com.simonrowe.factory.linear.config.LinearProperties;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Service;

/**
 * Starts deploys, keeping the webhook controller Temporal-agnostic.
 *
 * <p>The webhook caller checks {@code factory.deploy.trigger-enabled}; the internal admin API is
 * separately token protected and intentionally remains available for confirmed manual redeploys.
 */
@Service
public class DeployWorkflowService {

  private final WorkflowClient workflowClient;
  private final DeployProperties properties;
  private final LinearProperties linearProperties;

  /**
   * Creates the trigger.
   *
   * @param workflowClient the client used to signal-with-start
   * @param properties the deploy policy carried onto every request
   * @param linearProperties read here, on the trigger side, because the workflow itself cannot
   *     inject configuration — and because scheduling a filing on a queue nothing polls would
   *     stall the deploy
   */
  public DeployWorkflowService(
      final WorkflowClient workflowClient,
      final DeployProperties properties,
      final LinearProperties linearProperties) {
    this.workflowClient = workflowClient;
    this.properties = properties;
    this.linearProperties = linearProperties;
  }

  /**
   * Requests a deploy of {@code sha}.
   *
   * <p><b>Signal-with-start on the fixed workflow id {@code deploy-prod}</b>, which buys both
   * required properties at once:
   *
   * <ul>
   *   <li>A duplicate webhook delivery is inherently idempotent — the second call signals the
   *       running workflow instead of starting a second one. No already-started catch is needed,
   *       unlike {@code ReviewWorkflowService}: signal-with-start is idempotent by construction.
   *   <li>Two merges a few minutes apart produce <b>one</b> deploy, of the newer commit, rather
   *       than two overlapping {@code docker compose up -d} runs recreating the same containers.
   * </ul>
   *
   * <p>A per-sha workflow id was rejected: it makes duplicate deliveries free but does nothing
   * about concurrency, which is the failure that actually matters on a single-node host.
   *
   * @param sha the commit to deploy
   * @param trigger what asked for it
   * @param installationId the GitHub App installation, or null to resolve it at run time
   * @return the workflow and run the request reached
   */
  public DeployAccepted start(final String sha, final String trigger, final Long installationId) {
    DeployWorkflow workflow =
        workflowClient.newWorkflowStub(
            DeployWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(DeployTaskQueues.DEPLOY)
                .setWorkflowId(DeployWorkflow.WORKFLOW_ID)
                // A later merge, after this run has finished, must be able to start a new run on
                // the same id. REJECT_DUPLICATE would refuse every deploy after the first.
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build());

    DeployRequest request =
        new DeployRequest(
            sha,
            trigger,
            installationId,
            properties.syncConfig(),
            properties.rollbackEnabled(),
            properties.services(),
            false,
            linearProperties.enabled());

    BatchRequest batch = workflowClient.newSignalWithStartRequest();
    batch.add(workflow::run, request);
    // The signal is what a *second* delivery lands on. On the first it is redundant, and
    // deliberately so: the workflow reads this field after each attempt, so sending it here means
    // the start path and the coalesce path are the same code.
    batch.add(workflow::deployRequested, sha);

    WorkflowExecution execution = workflowClient.signalWithStart(batch);
    return new DeployAccepted(execution.getWorkflowId(), execution.getRunId(), sha);
  }

  /**
   * Reports where the current deploy has got to.
   *
   * @return the progress snapshot
   */
  public DeployProgress progress() {
    return workflowClient
        .newWorkflowStub(DeployWorkflow.class, DeployWorkflow.WORKFLOW_ID)
        .progress();
  }
}
