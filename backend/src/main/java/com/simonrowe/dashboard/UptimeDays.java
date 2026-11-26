package com.simonrowe.dashboard;

import com.simonrowe.dashboard.UptimeReport.DayStatus;
import com.simonrowe.dashboard.UptimeReport.DayUptime;
import com.simonrowe.dashboard.UptimeReport.TargetUptime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns hourly probe counts into per-day cells. Pure, so every threshold is tested without a
 * database.
 */
final class UptimeDays {

  /** An hour of failed probes, or an hour of not probing, is what makes a day red or hatched. */
  static final int HOUR_OF_MINUTES = 60;

  private UptimeDays() {
  }

  /**
   * Builds one row per target.
   *
   * @param targets the configured target names, in display order
   * @param hours every stored hour inside the window
   * @param firstProbes each target's first probed hour; a target missing from it was never
   *     probed, so every day before it was added reads as no data rather than as a gap
   * @param days the days to report, oldest first
   * @param now the current time
   * @param zone the zone days are cut in
   * @return the rows
   */
  static List<TargetUptime> build(
      final List<String> targets,
      final List<UptimeHour> hours,
      final Map<String, Instant> firstProbes,
      final List<LocalDate> days,
      final Instant now,
      final ZoneId zone) {
    Map<String, Map<LocalDate, List<UptimeHour>>> byTarget = new HashMap<>();
    for (UptimeHour hour : hours) {
      LocalDate day = hour.hourStart().atZone(zone).toLocalDate();
      byTarget.computeIfAbsent(hour.target(), t -> new HashMap<>())
          .computeIfAbsent(day, d -> new ArrayList<>())
          .add(hour);
    }

    List<TargetUptime> rows = new ArrayList<>();
    for (String target : targets) {
      Map<LocalDate, List<UptimeHour>> perDay = byTarget.getOrDefault(target, Map.of());
      Instant probingSince = firstProbes.get(target);
      List<DayUptime> cells = new ArrayList<>();
      int probes = 0;
      int failures = 0;
      long millis = 0;
      for (LocalDate day : days) {
        List<UptimeHour> dayHours = perDay.getOrDefault(day, List.of());
        DayUptime cell = cell(day, dayHours, probingSince, now, zone);
        cells.add(cell);
        probes += cell.probes();
        failures += cell.failures();
        millis += dayHours.stream().mapToLong(UptimeHour::totalMillis).sum();
      }
      Double percent = probes == 0 ? null : 100.0 * (probes - failures) / probes;
      Long average = probes == 0 ? null : millis / probes;
      rows.add(new TargetUptime(target, percent, probes, failures, average, List.copyOf(cells)));
    }
    return List.copyOf(rows);
  }

  private static DayUptime cell(
      final LocalDate day,
      final List<UptimeHour> hours,
      final Instant probingSince,
      final Instant now,
      final ZoneId zone) {
    int probes = hours.stream().mapToInt(UptimeHour::probes).sum();
    int failures = hours.stream().mapToInt(UptimeHour::failures).sum();
    String lastFailure = hours.stream()
        .filter(h -> h.lastFailureAt() != null)
        .max(Comparator.comparing(UptimeHour::lastFailureAt))
        .map(UptimeHour::lastFailure)
        .orElse(null);
    long expected = expectedMinutes(day, probingSince, now, zone);
    int unprobed = (int) Math.max(0, expected - probes);
    return new DayUptime(day, probes, failures, unprobed,
        status(probes, failures, unprobed, expected), lastFailure);
  }

  /**
   * Minutes of the day the prober should have covered: from when probing began (or the day's
   * start) to the day's end (or now). Probing began on the hour of its first result, so the
   * first day can read up to 59 minutes short, under the {@link #HOUR_OF_MINUTES} threshold.
   */
  static long expectedMinutes(
      final LocalDate day, final Instant probingSince, final Instant now, final ZoneId zone) {
    if (probingSince == null) {
      return 0;
    }
    Instant dayStart = day.atStartOfDay(zone).toInstant();
    Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
    Instant from = dayStart.isAfter(probingSince) ? dayStart : probingSince;
    Instant to = dayEnd.isBefore(now) ? dayEnd : now;
    return to.isAfter(from) ? Duration.between(from, to).toMinutes() : 0;
  }

  static DayStatus status(
      final int probes, final int failures, final int unprobed, final long expected) {
    if (probes == 0) {
      return expected > 0 ? DayStatus.GAPS : DayStatus.NO_DATA;
    }
    if (failures >= HOUR_OF_MINUTES) {
      return DayStatus.DOWN;
    }
    if (failures > 0) {
      return DayStatus.DEGRADED;
    }
    return unprobed > HOUR_OF_MINUTES ? DayStatus.GAPS : DayStatus.UP;
  }
}
