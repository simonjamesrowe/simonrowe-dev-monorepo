package com.simonrowe.factory.writing.config;

/** Temporal task queue names for the content-writing workflow. */
public final class WritingTaskQueues {

  /** Polled by the content-writing workflow and its one activity, on software-factory only. */
  public static final String CONTENT_WRITING = "content-writing";

  private WritingTaskQueues() {
  }
}
