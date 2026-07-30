package com.simonrowe.factory.codereview.domain;

/** Immutable input captured in Temporal history for one pull-request revision. */
public record ReviewRequest(
    String owner,
    String repository,
    int pullNumber,
    String expectedHeadSha,
    Long installationId,
    boolean publish) {
}
