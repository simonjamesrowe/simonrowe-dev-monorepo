package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Final outcome of feedback workflow. */
public record FeedbackResult(
    String workflowId, int lessonCount, DistillationStatus distillationStatus,
    List<String> proposalUrls, String issueUrl) {

  public FeedbackResult {
    proposalUrls = proposalUrls == null ? List.of() : List.copyOf(proposalUrls);
  }

  /** Compatibility constructor for no-signal and older callers. */
  public FeedbackResult(
      final String workflowId,
      final int lessonCount,
      final DistillationStatus distillationStatus,
      final List<String> proposalUrls) {
    this(workflowId, lessonCount, distillationStatus, proposalUrls, null);
  }
}
