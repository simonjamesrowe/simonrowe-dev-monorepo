package com.simonrowe.factory.temporal;

import java.time.Duration;

/** Limits shared by every daily Temporal schedule this service declares. */
public final class ScheduledRuns {

  /**
   * How long a scheduled run may live before Temporal ends it.
   *
   * <p>Every schedule here uses {@code SKIP} overlap, so a run that never finishes silently stops
   * its schedule for good: {@code logwatch-daily} skipped eleven runs behind one stuck workflow
   * before anyone noticed. An execution timeout shorter than the gap between firings means a stuck
   * run always ends before the next one is due, so it costs at most its own cycle.
   *
   * <p>22 hours, not 23: the platform backup fires on a {@code Europe/London} calendar, and the day
   * the clocks go forward is 23 hours long. It still clears the longest legitimate run, a platform
   * backup that exhausts three six-hour capture attempts.
   */
  public static final Duration EXECUTION_TIMEOUT = Duration.ofHours(22);

  private ScheduledRuns() {
  }
}
