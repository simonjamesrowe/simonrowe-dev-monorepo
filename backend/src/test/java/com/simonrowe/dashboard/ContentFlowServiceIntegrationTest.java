package com.simonrowe.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class ContentFlowServiceIntegrationTest extends AbstractIntegrationTest {

  // Thursday 26 November 2026; this week starts Monday the 23rd.
  private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

  @Autowired
  private MongoTemplate mongoTemplate;

  private ContentFlowService service;

  @BeforeEach
  @AfterEach
  void reset() {
    mongoTemplate.getCollection("aggregated_articles").drop();
    mongoTemplate.getCollection("article_summaries").drop();
    service = new ContentFlowService(mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void article(final String id, final String fetchedAt) {
    mongoTemplate.insert(new Document("_id", id)
        .append("title", id)
        .append("fetchedAt", Date.from(Instant.parse(fetchedAt))), "aggregated_articles");
  }

  private void summary(final String id, final String articleId, final String status,
      final String completedAt) {
    mongoTemplate.insert(new Document("_id", id)
        .append("articleId", articleId)
        .append("status", status)
        .append("completedAt", completedAt == null ? null : Date.from(Instant.parse(completedAt))),
        "article_summaries");
  }

  @Test
  void countsArrivalsAndFirstSummariesPerLondonWeek() {
    article("a1", "2026-11-23T08:00:00Z");
    article("a2", "2026-11-25T08:00:00Z");
    // Sunday 22 November, 23:30 in London (still GMT): last week, not this one.
    article("a3", "2026-11-22T23:30:00Z");
    article("old", "2026-01-01T00:00:00Z");
    summary("s1", "a1", "READY", "2026-11-24T10:00:00Z");
    summary("s3", "a3", "READY", "2026-11-22T23:45:00Z");
    summary("failed", "a2", "FAILED", "2026-11-25T10:00:00Z");
    summary("generating", "a2", "GENERATING", null);

    ContentFlowReport report = service.weekly(2);

    assertThat(report.timeZone()).isEqualTo("Europe/London");
    assertThat(report.weeks()).containsExactly(
        new ContentFlowReport.Week(LocalDate.of(2026, 11, 16), 1, 1),
        new ContentFlowReport.Week(LocalDate.of(2026, 11, 23), 2, 1));
  }

  @Test
  void resummarisedArticleCountsOnceInItsFirstWeek() {
    article("a1", "2026-11-10T08:00:00Z");
    summary("v1", "a1", "READY", "2026-11-17T10:00:00Z");
    summary("v2", "a1", "READY", "2026-11-24T10:00:00Z");
    // First summarised before the window: not counted inside it at all.
    summary("early", "a9", "READY", "2026-10-01T10:00:00Z");
    summary("early-v2", "a9", "READY", "2026-11-24T11:00:00Z");

    ContentFlowReport report = service.weekly(2);

    assertThat(report.weeks()).extracting(ContentFlowReport.Week::summarised)
        .containsExactly(1L, 0L);
  }

  @Test
  void emptyWeeksAreStillListed() {
    assertThat(service.weekly(12).weeks()).hasSize(12)
        .allSatisfy(week -> assertThat(week.arrived()).isZero());
    assertThat(service.weekly(12).weeks().getLast().weekStart())
        .isEqualTo(LocalDate.of(2026, 11, 23));
  }
}
