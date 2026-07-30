package com.simonrowe.factory.codereview.domain;

/** Completed workflow result retained by Temporal. */
public record ReviewResult(
    String workflowId, String headSha, boolean published, ReviewReport report) {
}
