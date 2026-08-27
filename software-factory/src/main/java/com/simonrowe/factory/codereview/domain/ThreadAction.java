package com.simonrowe.factory.codereview.domain;

/**
 * What reconciling a new report against the pull request's existing conversations decided to do.
 *
 * <p><b>There is deliberately no delete case.</b> Its absence is the guarantee: the previous
 * implementation deleted every inline comment on every re-review, which destroyed a thread root
 * even when a human had replied to it — taking the reply with it — and made GitHub's "N resolved"
 * counter permanently zero. Adding a delete variant here is how that regresses, so the type is
 * sealed and the omission is the point.
 */
public sealed interface ThreadAction {

  /**
   * The finding is still reported and its thread is open. Do nothing at all.
   *
   * <p>Doing nothing is the whole improvement: the thread keeps its original posting time, its
   * position in the conversation and every reply on it.
   */
  record Leave(String nodeId) implements ThreadAction {
  }

  /**
   * Post a fresh thread for this finding.
   *
   * <p>Either it has no thread yet, or its thread was resolved and the finding has come back — a
   * regression deserves a new conversation rather than a silent reopening of the old one.
   */
  record PostNew(ReviewFinding finding) implements ThreadAction {
  }

  /**
   * The finding is gone from the report. Reply saying so, then resolve the thread.
   *
   * <p>Reply first, always: a resolution that lands without its explanation is indistinguishable
   * from someone quietly closing an inconvenient finding.
   */
  record ReplyAndResolve(String nodeId) implements ThreadAction {
  }
}
