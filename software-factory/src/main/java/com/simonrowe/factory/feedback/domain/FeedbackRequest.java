package com.simonrowe.factory.feedback.domain;

/** Feedback module initiation request from webhook. */
public record FeedbackRequest(
    String owner, String repository, int pullNumber, Long installationId, boolean dryRun,
    boolean linearFilingEnabled) {

  /** Compatibility constructor for callers created before feedback gained Linear filing. */
  public FeedbackRequest(
      final String owner,
      final String repository,
      final int pullNumber,
      final Long installationId,
      final boolean dryRun) {
    this(owner, repository, pullNumber, installationId, dryRun, true);
  }
}
