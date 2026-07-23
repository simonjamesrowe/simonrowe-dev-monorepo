package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the Dan Vega blog content source and pre-populates the last 30 days of
 * posts so the news feed is not empty until the next scheduled aggregation run.
 *
 * <p>The source is scraped with the {@code HTML_LISTING} strategy (like the Claude
 * Blog source): the {@code /blog} index page's newest posts are fetched full-body.
 * The backfill is wrapped so that any failure (LLM, network, Kafka) is logged but
 * never rethrown — a failed pre-population must not fail the migration or block
 * application boot. The 6-hourly scheduled job backfills any gaps afterwards.
 * Seeding is idempotent ({@code findByName} guard) and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run.
 */
@ChangeUnit(id = "seed-and-backfill-dan-vega-blog", order = "011", author = "simonrowe")
public class V011SeedAndBackfillDanVegaBlog {

  private static final Logger log =
      LoggerFactory.getLogger(V011SeedAndBackfillDanVegaBlog.class);

  private static final long BACKFILL_WINDOW_DAYS = 30;

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName("Dan Vega").isPresent()) {
      log.info("Dan Vega source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        "Dan Vega",
        "https://www.danvega.dev/blog",
        null,
        null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING,
        true,
        null,
        null));
    log.info("Seeded Dan Vega content source");

    Instant since = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS);
    try {
      aggregationAgent.backfillSource(saved, since);
    } catch (Exception e) {
      // A failed pre-population must never break app boot; the scheduled
      // aggregation job will pick up the source on its next run.
      log.error("Dan Vega backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName("Dan Vega")
        .ifPresent(contentSourceRepository::delete);
  }
}
