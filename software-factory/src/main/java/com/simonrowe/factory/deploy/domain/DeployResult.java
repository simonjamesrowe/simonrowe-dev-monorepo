package com.simonrowe.factory.deploy.domain;

/**
 * What the deploy workflow returns.
 *
 * <p>Deliberately small: the full story is the persisted {@code deploy_runs} record, because a
 * workflow result survives only as long as its history does.
 *
 * @param status how the deploy ended
 * @param sha the commit deployed
 * @param syncDecision what configuration sync decided
 * @param issueUrl the reported issue, or null when nothing failed
 * @param detail one line summarising the run
 */
public record DeployResult(
    DeployStatus status,
    String sha,
    SyncDecision syncDecision,
    String issueUrl,
    String detail) {
}
