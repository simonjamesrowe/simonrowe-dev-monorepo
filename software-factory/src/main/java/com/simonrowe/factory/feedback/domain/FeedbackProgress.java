package com.simonrowe.factory.feedback.domain;

/** Transient progress report during feedback workflow execution. */
public record FeedbackProgress(FeedbackPhase phase, String detail, Integer lessonCount) {

  /** Factory for ACCEPTED phase progress. */
  public static FeedbackProgress accepted() {
    return new FeedbackProgress(FeedbackPhase.ACCEPTED, "Workflow accepted", null);
  }
}
