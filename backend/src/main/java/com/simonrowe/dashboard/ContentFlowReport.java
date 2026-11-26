package com.simonrowe.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Articles arrived and summarised, per week.
 *
 * @param timeZone the zone the weeks are cut in
 * @param weeks one row per week, oldest first
 * @param generatedAt when this report was built
 */
public record ContentFlowReport(String timeZone, List<Week> weeks, Instant generatedAt) {

  /**
   * One week, Monday to Sunday.
   *
   * @param weekStart the Monday
   * @param arrived articles aggregated that week, hidden ones included
   * @param summarised articles whose first summary was finished that week
   */
  public record Week(LocalDate weekStart, long arrived, long summarised) {
  }
}
