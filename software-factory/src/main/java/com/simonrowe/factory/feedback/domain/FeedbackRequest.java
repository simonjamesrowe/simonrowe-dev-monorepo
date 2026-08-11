package com.simonrowe.factory.feedback.domain;

/** Feedback module initiation request from webhook. */
public record FeedbackRequest(
    String owner, String repository, int pullNumber, Long installationId, boolean dryRun) {
}
