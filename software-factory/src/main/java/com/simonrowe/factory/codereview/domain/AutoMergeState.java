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
 * @param authorPermission the author's permission on this repository as GitHub's permission API
 *     reports it: {@code admin}, {@code write}, {@code read} or {@code none}. Deliberately not
 *     {@code author_association}, which reads a private organisation member as {@code
 *     CONTRIBUTOR} to an App token and so refused the repository owner
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
    String authorPermission,
    List<String> labels,
    int changedFiles,
    boolean autoMergeArmed,
    boolean autoMergeArmedByBot) {

  public AutoMergeState {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }
}
