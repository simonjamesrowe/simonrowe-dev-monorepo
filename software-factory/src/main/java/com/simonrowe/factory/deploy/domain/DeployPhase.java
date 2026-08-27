package com.simonrowe.factory.deploy.domain;

/**
 * The ordered phases of one deploy.
 *
 * <p>The first seven map one-for-one onto arguments of {@code scripts/restart-prod.sh}, which is
 * why {@link #argument()} exists: the enum is the single place the Java side and the shell side
 * agree on a name. {@code ROLLBACK_CONFIG} is only reached on the failure path.
 *
 * <p>{@link #TRIAGE} and {@link #REPORT} have no script argument — they are Java-side phases.
 * They are in this enum anyway so a persisted run record tells the whole story rather than only
 * the shell part of it.
 */
public enum DeployPhase {
  SYNC_CONFIG,
  MAINTENANCE_ON,
  PULL,
  RECREATE,
  VERIFY,
  MAINTENANCE_OFF,
  VERIFY_PUBLIC,
  ROLLBACK_CONFIG,
  ROLLBACK,
  TRIAGE,
  REPORT;

  /**
   * The {@code restart-prod.sh} argument for this phase, or null when it has none.
   *
   * @return the kebab-case script argument, or null for a Java-side phase
   */
  public String argument() {
    return switch (this) {
      case TRIAGE, REPORT -> null;
      default -> name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    };
  }
}
