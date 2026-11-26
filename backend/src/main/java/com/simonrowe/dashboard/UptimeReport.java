package com.simonrowe.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The uptime panel: one row per probed hostname, one cell per day, and the outage issues.
 *
 * @param probing whether the prober is switched on here
 * @param probingSince the first hour with any result, or null when nothing has been probed
 * @param timeZone the zone the days are cut in
 * @param days the days covered, oldest first
 * @param targets one row per configured hostname, in configured order
 * @param incidents the heartbeat's outage issues that overlap the window
 * @param generatedAt when this report was built
 */
public record UptimeReport(
    boolean probing,
    Instant probingSince,
    String timeZone,
    List<LocalDate> days,
    List<TargetUptime> targets,
    Incidents incidents,
    Instant generatedAt
) {

  /**
   * One hostname over the window.
   *
   * @param name the target's name
   * @param uptimePercent passing probes as a share of probes run, or null when none ran
   * @param probes probes run in the window
   * @param failures of which failed
   * @param averageMillis mean response time, or null when none ran
   * @param days one entry per day, aligned with {@link UptimeReport#days()}
   */
  public record TargetUptime(
      String name,
      Double uptimePercent,
      int probes,
      int failures,
      Long averageMillis,
      List<DayUptime> days
  ) {
  }

  /**
   * One hostname on one day.
   *
   * @param date the day
   * @param probes probes run that day
   * @param failures of which failed
   * @param unprobedMinutes minutes the prober should have covered and did not, because it (the
   *     backend, or the whole Pi) was not running; these are unknown, not up
   * @param status the day's summary
   * @param lastFailure what the day's latest failure saw, or null
   */
  public record DayUptime(
      LocalDate date,
      int probes,
      int failures,
      int unprobedMinutes,
      DayStatus status,
      String lastFailure
  ) {
  }

  /** How a day went for one hostname. */
  public enum DayStatus {
    /** Every probe passed and the prober ran all day. */
    UP,
    /** Some probes failed, under an hour's worth. */
    DEGRADED,
    /** An hour or more of failed probes. */
    DOWN,
    /** No failures seen, but the prober was absent for over an hour, so the day is not known. */
    GAPS,
    /** Before probing began, or the day has not started. */
    NO_DATA
  }

  /**
   * The {@code prod-down} issues the off-Pi heartbeat files.
   *
   * @param available false when GitHub could not be read and nothing was cached
   * @param items the issues, newest first
   */
  public record Incidents(boolean available, List<Incident> items) {
  }

  /**
   * One outage issue.
   *
   * @param number the issue number
   * @param title the issue title, which names the first failed check
   * @param openedAt when the heartbeat opened it
   * @param closedAt when a passing heartbeat closed it, or null while open
   * @param url the issue on GitHub
   */
  public record Incident(int number, String title, Instant openedAt, Instant closedAt, String url) {
  }
}
