package com.simonrowe.factory.pageread.config;

/** Temporal task queue names for reading a page in a headless browser. */
public final class PageReadTaskQueues {

  /** Polled by {@code PageReadWorkflowImpl}, on software-factory only. */
  public static final String PAGE_READ = "page-read";

  /**
   * Polled by the {@code page-reader} container, a Node worker that registers the one activity
   * and no workflow. Nothing in this JVM polls it: the browser never runs here.
   */
  public static final String PAGE_READER = "page-reader";

  private PageReadTaskQueues() {
  }
}
