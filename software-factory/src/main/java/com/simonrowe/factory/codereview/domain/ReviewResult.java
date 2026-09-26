package com.simonrowe.factory.codereview.domain;

/**
 * Completed workflow result retained by Temporal.
 *
 * @param autoMerge what happened to auto-merge; null for a run started before auto-merge existed
 */
public record ReviewResult(
    String workflowId,
    String headSha,
    boolean published,
    ReviewReport report,
    MergeDecision autoMerge) {
}
