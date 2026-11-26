package com.simonrowe.dashboard;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

import com.simonrowe.summary.SummaryStatus;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Counts, per week, the news articles that arrived and the ones that were summarised.
 *
 * <p>Both have full history already: {@code aggregated_articles.fetchedAt} and
 * {@code article_summaries.completedAt}. What was listened to does not (the playlist forgets a
 * listened item after 30 days), so it waits for a listening log of its own.
 *
 * <p><b>An article is counted as summarised once, in the week of its first summary.</b> Raising
 * {@code SUMMARY_FORMAT_VERSION} writes a second summary for the same article under a new id,
 * and counting summaries rather than articles would count it twice.
 */
@Service
public class ContentFlowService {

  static final ZoneId ZONE = ZoneId.of("Europe/London");
  private static final String ARTICLES = "aggregated_articles";
  private static final String SUMMARIES = "article_summaries";

  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param mongoTemplate where the articles and summaries live
   * @param clock the time source
   */
  public ContentFlowService(final MongoTemplate mongoTemplate, final Clock clock) {
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /**
   * The last {@code weekCount} weeks, Monday to Sunday in London, this week included.
   *
   * @param weekCount how many weeks, already clamped by the caller
   * @return one row per week, oldest first
   */
  public ContentFlowReport weekly(final int weekCount) {
    Instant now = clock.instant();
    LocalDate thisWeek = weekOf(now);
    Map<LocalDate, long[]> counts = new LinkedHashMap<>();
    for (int i = weekCount - 1; i >= 0; i--) {
      counts.put(thisWeek.minusWeeks(i), new long[2]);
    }
    Instant from = counts.keySet().iterator().next().atStartOfDay(ZONE).toInstant();

    Query arrived = Query.query(Criteria.where("fetchedAt").gte(from));
    arrived.fields().include("fetchedAt");
    for (Document article : mongoTemplate.find(arrived, Document.class, ARTICLES)) {
      increment(counts, article.getDate("fetchedAt"), 0);
    }

    Aggregation firstSummaries = newAggregation(
        match(Criteria.where("status").is(SummaryStatus.READY.name())
            .and("completedAt").ne(null)),
        group("articleId").min("completedAt").as("first"),
        match(Criteria.where("first").gte(from)));
    for (Document summary
        : mongoTemplate.aggregate(firstSummaries, SUMMARIES, Document.class)) {
      increment(counts, summary.getDate("first"), 1);
    }

    List<ContentFlowReport.Week> weeks = new ArrayList<>();
    counts.forEach((start, values) ->
        weeks.add(new ContentFlowReport.Week(start, values[0], values[1])));
    return new ContentFlowReport(ZONE.getId(), List.copyOf(weeks), now);
  }

  private static void increment(final Map<LocalDate, long[]> counts, final Date when,
      final int column) {
    if (when == null) {
      return;
    }
    long[] row = counts.get(weekOf(when.toInstant()));
    if (row != null) {
      row[column]++;
    }
  }

  static LocalDate weekOf(final Instant instant) {
    return instant.atZone(ZONE).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}
