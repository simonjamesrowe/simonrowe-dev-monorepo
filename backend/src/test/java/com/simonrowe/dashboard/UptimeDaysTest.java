package com.simonrowe.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.dashboard.UptimeReport.DayStatus;
import com.simonrowe.dashboard.UptimeReport.DayUptime;
import com.simonrowe.dashboard.UptimeReport.TargetUptime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UptimeDaysTest {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
  private static final Instant DAY_START = DAY.atStartOfDay(LONDON).toInstant();
  private static final Instant LATER = DAY.plusDays(3).atStartOfDay(LONDON).toInstant();

  /** Every hour of the day for a target, each with the given probes and failures. */
  private static List<UptimeHour> fullDay(final String target, final int failuresInFirstHours,
      final int failingHours) {
    List<UptimeHour> hours = new ArrayList<>();
    for (int h = 0; h < 24; h++) {
      Instant start = DAY_START.plusSeconds(h * 3600L);
      int failures = h < failingHours ? failuresInFirstHours : 0;
      hours.add(new UptimeHour(UptimeHour.idFor(target, start), target, start, 60, failures,
          60 * 200L, failures > 0 ? "HTTP 502" : null,
          failures > 0 ? start.plusSeconds(60) : null));
    }
    return hours;
  }

  private static DayUptime only(final List<TargetUptime> rows) {
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().days()).hasSize(1);
    return rows.getFirst().days().getFirst();
  }

  @Test
  void dayWithEveryProbePassingIsUp() {
    List<TargetUptime> rows = UptimeDays.build(List.of("www"), fullDay("www", 0, 0),
        DAY_START, List.of(DAY), LATER, LONDON);

    DayUptime day = only(rows);
    assertThat(day.status()).isEqualTo(DayStatus.UP);
    assertThat(day.probes()).isEqualTo(1440);
    assertThat(day.unprobedMinutes()).isZero();
    assertThat(rows.getFirst().uptimePercent()).isEqualTo(100.0);
    assertThat(rows.getFirst().averageMillis()).isEqualTo(200L);
  }

  @Test
  void underAnHourOfFailuresIsDegradedAndAnHourOrMoreIsDown() {
    DayUptime degraded = only(UptimeDays.build(List.of("www"), fullDay("www", 59, 1),
        DAY_START, List.of(DAY), LATER, LONDON));
    DayUptime down = only(UptimeDays.build(List.of("www"), fullDay("www", 30, 2),
        DAY_START, List.of(DAY), LATER, LONDON));

    assertThat(degraded.status()).isEqualTo(DayStatus.DEGRADED);
    assertThat(degraded.lastFailure()).isEqualTo("HTTP 502");
    assertThat(down.status()).isEqualTo(DayStatus.DOWN);
    assertThat(down.failures()).isEqualTo(60);
  }

  @Test
  void minutesTheProberMissedAreGapsNotUptime() {
    // Twelve hours probed, twelve hours of nothing: the backend (or the Pi) was down.
    List<UptimeHour> half = fullDay("www", 0, 0).subList(0, 12);

    DayUptime day = only(UptimeDays.build(List.of("www"), half, DAY_START, List.of(DAY), LATER,
        LONDON));

    assertThat(day.status()).isEqualTo(DayStatus.GAPS);
    assertThat(day.unprobedMinutes()).isEqualTo(720);
  }

  @Test
  void dayWithoutProbesIsGapsAfterProbingBeganAndNoDataBefore() {
    LocalDate before = DAY.minusDays(1);
    List<TargetUptime> rows = UptimeDays.build(List.of("www"), List.of(), DAY_START,
        List.of(before, DAY), LATER, LONDON);

    assertThat(rows.getFirst().days()).extracting(DayUptime::status)
        .containsExactly(DayStatus.NO_DATA, DayStatus.GAPS);
    assertThat(rows.getFirst().uptimePercent()).isNull();
    assertThat(rows.getFirst().averageMillis()).isNull();
  }

  @Test
  void todayOnlyExpectsTheMinutesSoFar() {
    Instant noon = DAY_START.plusSeconds(12 * 3600L);

    assertThat(UptimeDays.expectedMinutes(DAY, DAY_START, noon, LONDON)).isEqualTo(720);
    assertThat(UptimeDays.expectedMinutes(DAY, null, noon, LONDON)).isZero();
    assertThat(UptimeDays.expectedMinutes(DAY.plusDays(1), DAY_START, noon, LONDON)).isZero();
  }

  @Test
  void theClockChangeDayIsTwentyFiveHoursLong() {
    // 25 October 2026: London falls back an hour, so the day has 1,500 minutes.
    LocalDate fallBack = LocalDate.of(2026, 10, 25);
    Instant since = fallBack.minusDays(1).atStartOfDay(LONDON).toInstant();

    assertThat(UptimeDays.expectedMinutes(fallBack, since, LATER, LONDON)).isEqualTo(1500);
  }

  @Test
  void rowsFollowConfiguredOrderAndIgnoreUnconfiguredTargets() {
    List<UptimeHour> hours = new ArrayList<>(fullDay("api", 0, 0));
    hours.addAll(fullDay("retired", 0, 0));

    List<TargetUptime> rows = UptimeDays.build(List.of("www", "api"), hours, DAY_START,
        List.of(DAY), LATER, LONDON);

    assertThat(rows).extracting(TargetUptime::name).containsExactly("www", "api");
    assertThat(rows.get(0).days().getFirst().status()).isEqualTo(DayStatus.GAPS);
    assertThat(rows.get(1).days().getFirst().status()).isEqualTo(DayStatus.UP);
  }
}
