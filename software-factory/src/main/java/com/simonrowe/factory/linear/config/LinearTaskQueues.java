package com.simonrowe.factory.linear.config;

/** Temporal task queue names for the Linear issue sink. */
public final class LinearTaskQueues {

  /**
   * Task queue polled only by {@code software-factory}, which alone holds {@code LINEAR_API_KEY}.
   *
   * <p>The {@code deployer} runs the same image but leaves {@code factory.linear.enabled}
   * false, so it registers no activity implementation and never receives the credential. That is
   * the same confinement {@code DeployTaskQueues} documents in the other direction.
   */
  public static final String LINEAR = "linear";

  private LinearTaskQueues() {
  }
}
