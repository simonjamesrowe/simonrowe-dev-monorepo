package com.simonrowe.factory.deploy.domain;

/**
 * Queryable progress snapshot, for an operator watching a deploy in the Temporal UI.
 *
 * @param phase where the run has got to
 * @param detail one line of narration
 * @param sha the commit being deployed, or null before the first phase
 */
public record DeployProgress(DeployPhase phase, String detail, String sha) {

  /** The state a run reports before its first activity completes. */
  public static DeployProgress accepted() {
    return new DeployProgress(null, "Accepted", null);
  }
}
