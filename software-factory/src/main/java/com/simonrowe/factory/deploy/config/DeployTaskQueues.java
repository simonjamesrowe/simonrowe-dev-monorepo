package com.simonrowe.factory.deploy.config;

/** Temporal task queue names for the deploy workflow. */
public final class DeployTaskQueues {

  /**
   * The site's deploy queue, polled by the {@code deployer} on the Raspberry Pi.
   *
   * <p>Only that container holds {@code DeployActivitiesImpl} on this queue, which is gated on
   * {@code factory.deploy.enabled}, so only it can execute a site deploy step. See
   * specs/036-auto-deploy-on-merge/research.md §11.
   */
  public static final String DEPLOY = "deploy";

  /**
   * The factory host's deploy queue, polled by the {@code deployer} in
   * docker-compose.factory.yml (role {@code factory-deployer}).
   *
   * <p>A queue of its own, not a second poller on {@link #DEPLOY}: every activity on a queue goes
   * to whichever poller asks first, and the two deployers hold different hosts' Docker sockets. One
   * shared queue would recreate the site's containers on the NUC's daemon, or the factory's on the
   * Pi's, at random.
   */
  public static final String DEPLOY_FACTORY = "deploy-factory";

  /**
   * The queue this JVM's deploy workflow and activities register on, resolved by the Temporal
   * starter from {@code factory.deploy.task-queue}.
   *
   * <p>A placeholder rather than a constant, because one image serves both deployers: {@code
   * WorkersTemplate} resolves {@code ${...}} in both {@code @WorkflowImpl} and {@code
   * @ActivityImpl} task queues (checked against temporal-spring-boot-autoconfigure 1.36.0). The
   * factory-deployer profile sets it to {@link #DEPLOY_FACTORY}; everything else gets {@link
   * #DEPLOY}, as before.
   */
  public static final String CONFIGURED = "${factory.deploy.task-queue:" + DEPLOY + "}";

  private DeployTaskQueues() {
  }
}
