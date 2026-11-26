package com.simonrowe.dashboard;

import com.simonrowe.dashboard.UptimeReport.Incident;
import com.simonrowe.dashboard.UptimeReport.Incidents;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Builds the uptime panel from {@link UptimeHour} and the outage issues. */
@Service
public class UptimeService {

  /** Days are cut where the site's owner lives, not in the container's UTC. */
  static final ZoneId ZONE = ZoneId.of("Europe/London");

  private final DashboardProperties properties;
  private final MongoTemplate mongoTemplate;
  private final OutageIssues outageIssues;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param properties dashboard settings
   * @param mongoTemplate where probe results live
   * @param outageIssues the heartbeat's outage issues
   * @param clock the time source
   */
  public UptimeService(
      final DashboardProperties properties,
      final MongoTemplate mongoTemplate,
      final OutageIssues outageIssues,
      final Clock clock) {
    this.properties = properties;
    this.mongoTemplate = mongoTemplate;
    this.outageIssues = outageIssues;
    this.clock = clock;
  }

  /**
   * The last {@code dayCount} days, today included.
   *
   * @param dayCount how many days, already clamped by the caller
   * @return the report
   */
  public UptimeReport report(final int dayCount) {
    Instant now = clock.instant();
    LocalDate today = now.atZone(ZONE).toLocalDate();
    List<LocalDate> days = new ArrayList<>();
    for (int i = dayCount - 1; i >= 0; i--) {
      days.add(today.minusDays(i));
    }
    Instant windowStart = days.getFirst().atStartOfDay(ZONE).toInstant();

    List<String> targets = properties.uptime().targets().stream()
        .map(DashboardProperties.Target::name)
        .toList();
    // Only configured targets: a hostname dropped from the list keeps its hours until the TTL
    // expires them, and they would be read here only to be thrown away.
    List<UptimeHour> hours = mongoTemplate.find(
        Query.query(Criteria.where("hourStart").gte(windowStart).and("target").in(targets)),
        UptimeHour.class);
    UptimeHour first = mongoTemplate.findOne(
        new Query().with(Sort.by(Sort.Direction.ASC, "hourStart")).limit(1), UptimeHour.class);
    Instant probingSince = first == null ? null : first.hourStart();

    Incidents incidents = outageIssues.recent();
    List<Incident> overlapping = incidents.items().stream()
        .filter(i -> i.closedAt() == null || !i.closedAt().isBefore(windowStart))
        .toList();

    return new UptimeReport(
        properties.uptime().enabled(),
        probingSince,
        ZONE.getId(),
        List.copyOf(days),
        UptimeDays.build(targets, hours, probingSince, days, now, ZONE),
        new Incidents(incidents.available(), overlapping),
        now);
  }
}
