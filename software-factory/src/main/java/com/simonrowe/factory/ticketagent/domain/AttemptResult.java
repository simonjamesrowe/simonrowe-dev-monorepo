package com.simonrowe.factory.ticketagent.domain;

import java.util.List;

/**
 * What one agent run produced.
 *
 * @param status what happened
 * @param headSha the pushed commit, when {@code PUSHED}
 * @param summary the agent's own account of what it changed, or why it changed nothing
 * @param prTitle a conventional-commit title for the pull request
 * @param prBody the pull request description
 * @param changedPaths every path the run changed
 * @param silencing reasons the diff only quiets something (a mute rule, a log level, a disabled
 *     test); non-empty means a person must merge it
 * @param diff the would-be diff, on a dry run only
 */
public record AttemptResult(
    Status status,
    String headSha,
    String summary,
    String prTitle,
    String prBody,
    List<String> changedPaths,
    List<String> silencing,
    String diff) {

  public AttemptResult {
    changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
    silencing = silencing == null ? List.of() : List.copyOf(silencing);
  }

  /** The four ways a run ends. */
  public enum Status {
    /** A commit is on the branch. */
    PUSHED,
    /** The agent changed nothing, and said why in {@code summary}. */
    NO_CHANGE,
    /** The agent touched a path it may never push; nothing was pushed. */
    DENIED,
    /** A dry run with a change; {@code diff} holds it. */
    DRY_RUN
  }
}
