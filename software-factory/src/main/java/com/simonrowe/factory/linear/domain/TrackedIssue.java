package com.simonrowe.factory.linear.domain;

import java.time.Instant;

/**
 * A Linear issue found by fingerprint.
 *
 * @param id the Linear issue UUID
 * @param identifier the human identifier, e.g. {@code SIM-42}
 * @param url the issue's web URL
 * @param stateType the issue's current workflow state type, read from Linear rather than cached
 * @param createdAt when the issue was created, used to break ties within a precedence band
 */
public record TrackedIssue(
    String id, String identifier, String url, IssueStateType stateType, Instant createdAt) {
}
