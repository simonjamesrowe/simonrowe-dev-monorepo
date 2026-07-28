package com.simonrowe.reviewer.domain;

/** Completed workflow result retained by Temporal. */
public record ReviewResult(
    String workflowId, String headSha, boolean published, ReviewReport report) {
}
