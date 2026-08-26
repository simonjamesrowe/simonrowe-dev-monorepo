package com.simonrowe.factory.deploy.config;

/** Temporal task queue names for the deploy workflow. */
public final class DeployTaskQueues {

  /**
   * Task queue polled by the deploy workflow and its activities.
   *
   * <p>Both {@code software-factory} and {@code deployer} run the same image, so both register a
   * workflow-task poller on this queue — {@code @WorkflowImpl} classpath scanning is
   * unconditional. Only the {@code deployer} holds {@code DeployActivitiesImpl}, which is gated
   * on {@code factory.deploy.enabled}, so only the {@code deployer} can execute a deploy step.
   * See specs/036-auto-deploy-on-merge/research.md §11.
   */
  public static final String DEPLOY = "deploy";

  private DeployTaskQueues() {
  }
}
