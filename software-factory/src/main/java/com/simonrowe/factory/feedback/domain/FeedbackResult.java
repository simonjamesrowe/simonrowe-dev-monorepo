package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Final outcome of feedback workflow. */
public record FeedbackResult(
    String workflowId, int lessonCount, DistillationStatus distillationStatus,
    List<String> proposalUrls) {

  public FeedbackResult {
    proposalUrls = proposalUrls == null ? List.of() : List.copyOf(proposalUrls);
  }
}
