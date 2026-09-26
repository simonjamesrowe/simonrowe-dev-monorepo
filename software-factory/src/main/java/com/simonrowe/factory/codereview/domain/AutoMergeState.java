package com.simonrowe.factory.codereview.domain;

import java.util.List;

/**
 * What GitHub says about a pull request at the moment an auto-merge decision is taken.
 *
 * <p>Read fresh for every decision rather than carried from {@code loadPullRequest}: the review
 * takes minutes, and every field here — head, labels, who armed auto-merge — can change in that
 * time.
 *
 * @param nodeId the GraphQL id the auto-merge mutations take
 * @param headSha the live head, compared against the commit that was actually reviewed
 * @param draft GitHub refuses to arm auto-merge on a draft
 * @param crossRepository true when the head lives in a fork (or a fork since deleted)
 * @param authorAssociation GitHub's {@code author_association}, e.g. {@code OWNER}, {@code MEMBER}
 * @param labels label names on the pull request
 * @param changedFiles GitHub's own count, which a file listing must match to count as complete
 * @param autoMergeArmed whether auto-merge is currently enabled
 * @param autoMergeArmedByBot whether it was enabled by a bot account rather than a person
 */
public record AutoMergeState(
    String nodeId,
    String headSha,
    boolean draft,
    boolean crossRepository,
    String authorAssociation,
    List<String> labels,
    int changedFiles,
    boolean autoMergeArmed,
    boolean autoMergeArmedByBot) {

  public AutoMergeState {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }
}
