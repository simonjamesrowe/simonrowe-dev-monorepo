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
 * Seeds ai4jvm.com as a content source and pre-populates its curated headlines.
 *
 * <p>AI4JVM is an index rather than a publisher: its news block links out to InfoQ,
 * foojay, javapro, GitHub releases and others, so it is scraped with the
 * {@code LINK_ROUNDUP} strategy, which follows each link to the real article. Every
 * ingested item is attributed to {@code AI4JVM} — the card's outbound link still goes to
 * the original publisher, and per-publisher source names would add a dozen one-off
 * filter pills to the news page.
 *
 * <p>The backfill window is 120 days rather than the 30 used for Dan Vega: the curated
 * list spans several months and the page only grows at the top, so anything cut here is
 * never offered again. The backfill is wrapped so that any failure (LLM, network, Kafka)
 * is logged but never rethrown — a failed pre-population must not block application boot.
 * Seeding is idempotent via the {@code findByName} guard, and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run.
 */
@ChangeUnit(id = "seed-and-backfill-ai4jvm", order = "018", author = "simonrowe")
public class V018SeedAndBackfillAi4Jvm {

  private static final Logger log = LoggerFactory.getLogger(V018SeedAndBackfillAi4Jvm.class);

  private static final String SOURCE_NAME = "AI4JVM";
  private static final long BACKFILL_WINDOW_DAYS = 120;

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName(SOURCE_NAME).isPresent()) {
      log.info("AI4JVM source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        SOURCE_NAME,
        "https://ai4jvm.com",
        null,
        null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.LINK_ROUNDUP,
        true,
        null,
        null));
    log.info("Seeded AI4JVM content source");

    Instant since = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS);
    try {
      aggregationAgent.backfillSource(saved, since);
    } catch (Exception e) {
      log.error("AI4JVM backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName(SOURCE_NAME)
        .ifPresent(contentSourceRepository::delete);
  }
}
