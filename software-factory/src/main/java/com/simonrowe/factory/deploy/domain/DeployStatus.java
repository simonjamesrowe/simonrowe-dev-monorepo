package com.simonrowe.factory.deploy.domain;

/**
 * How one deploy ended.
 *
 * <p>{@link #ROLLED_BACK} and {@link #ROLLBACK_FAILED} must be distinguishable at a glance: the
 * second means the maintenance page is still up and a human is needed now.
 */
public enum DeployStatus {
  /** Every phase passed, including the configuration fast-forward. */
  DEPLOYED,
  /** Passed, but configuration sync declined — images only. See {@link SyncOutcome}. */
  DEPLOYED_IMAGES_ONLY,
  /** The deploy failed verification and the previous version was restored and verified. */
  ROLLED_BACK,
  /** The rollback itself failed verification. The maintenance page is deliberately left up. */
  ROLLBACK_FAILED,
  /** The deploy failed and {@code factory.deploy.rollback-enabled} was false. */
  ROLLBACK_DISABLED,
  /** Failed before there was anything to roll back. */
  FAILED,
  /** Nothing to do — already current, or a dry run. */
  SKIPPED
}
