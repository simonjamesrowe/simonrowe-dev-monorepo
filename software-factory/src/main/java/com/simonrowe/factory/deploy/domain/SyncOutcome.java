package com.simonrowe.factory.deploy.domain;

import java.util.List;

/**
 * The configuration-sync result, recorded whether or not it moved {@code HEAD}.
 *
 * <p>"Images only, and here is why" is a first-class result rather than an error: a change
 * touching a non-allowlisted service is reported with the manual command, not half-applied.
 *
 * @param decision what was decided
 * @param previousSha the rollback target; non-null only when {@code decision} is {@code APPLIED}
 * @param targetSha the commit the deploy is for
 * @param affectedServices every service the compose change would affect
 * @param heldBackServices the affected services outside the recreate allowlist
 * @param missingVariable a variable the host's env file lacks, best-effort, else null
 * @param manualCommand what a human should run to apply a held-back change, else null
 * @param detail one line for the run record
 */
public record SyncOutcome(
    SyncDecision decision,
    String previousSha,
    String targetSha,
    List<String> affectedServices,
    List<String> heldBackServices,
    String missingVariable,
    String manualCommand,
    String detail) {

  public SyncOutcome {
    affectedServices = affectedServices == null ? List.of() : List.copyOf(affectedServices);
    heldBackServices = heldBackServices == null ? List.of() : List.copyOf(heldBackServices);
  }

  /** The outcome when {@code factory.deploy.sync-config} is off. */
  public static SyncOutcome disabled(final String targetSha) {
    return new SyncOutcome(
        SyncDecision.DISABLED,
        null,
        targetSha,
        List.of(),
        List.of(),
        null,
        null,
        "configuration sync is disabled; images only");
  }
}
