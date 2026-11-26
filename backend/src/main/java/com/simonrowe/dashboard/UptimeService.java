package com.simonrowe.dashboard;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

import com.simonrowe.dashboard.UptimeReport.Incident;
import com.simonrowe.dashboard.UptimeReport.Incidents;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
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
    Map<String, Instant> firstProbes = firstProbes(targets);
    Instant probingSince = firstProbes.values().stream().min(Instant::compareTo).orElse(null);

    Incidents incidents = outageIssues.recent();
    List<Incident> overlapping = incidents.items().stream()
        .filter(i -> i.closedAt() == null || !i.closedAt().isBefore(windowStart))
        .toList();

    return new UptimeReport(
        properties.uptime().enabled(),
        probingSince,
        ZONE.getId(),
        List.copyOf(days),
        UptimeDays.build(targets, hours, firstProbes, days, now, ZONE),
        new Incidents(incidents.available(), overlapping),
        now);
  }

  /**
   * Each target's first probed hour. Per target, not one for the whole collection: a hostname
   * added to the list later would otherwise show every day before it existed as a gap.
   */
  private Map<String, Instant> firstProbes(final List<String> targets) {
    Aggregation firstHours = newAggregation(
        match(Criteria.where("target").in(targets)),
        group("target").min("hourStart").as("first"));
    Map<String, Instant> first = new HashMap<>();
    for (Document row
        : mongoTemplate.aggregate(firstHours, UptimeHour.COLLECTION, Document.class)) {
      Date when = row.getDate("first");
      // Kept although every stored hour has a start: a hand-edited row must not NPE the panel.
      if (when != null) {
        first.put(row.getString("_id"), when.toInstant());
      }
    }
    return first;
  }
}
