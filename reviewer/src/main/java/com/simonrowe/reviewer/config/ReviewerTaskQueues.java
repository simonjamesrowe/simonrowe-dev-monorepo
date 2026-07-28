package com.simonrowe.reviewer.config;

/** Stable Temporal task-queue names form the deployment boundary for reviewer workers. */
public final class ReviewerTaskQueues {

  public static final String REVIEWS = "code-review";

  private ReviewerTaskQueues() {
  }
}
