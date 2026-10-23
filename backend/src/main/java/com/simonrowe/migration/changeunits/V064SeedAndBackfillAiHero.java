package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.scrapers.AiHeroScraper;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds Matt Pocock's AI Hero blog as a content source and pre-populates this year's posts.
 *
 * <p>It uses the {@code AI_HERO} strategy rather than {@code RSS}: the site's feed is mostly
 * dictionary entries, skill pages and landing pages, and {@code AiHeroScraper} keeps only
 * the posts (its Javadoc has the detail).
 *
 * <p>The backfill starts at {@link AiHeroScraper#PUBLISHED_FLOOR}, the same floor the
 * scraper applies on every scheduled run, so the two cannot disagree about where the
 * archive starts. On 2026-10-23 that was 26 posts. Each gets one classifier call and its
 * own {@code og:image}, so no image is generated.
 *
 * <p>Seeding is idempotent via the {@code findByName} guard, and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run. A failed backfill is logged and
 * never rethrown: it must not block application boot, and the scheduled run picks the
 * posts up anyway.
 */
@ChangeUnit(id = "seed-and-backfill-ai-hero", order = "064", author = "simonrowe")
public class V064SeedAndBackfillAiHero {

  private static final Logger log = LoggerFactory.getLogger(V064SeedAndBackfillAiHero.class);

  static final String SOURCE_NAME = "AI Hero";

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName(SOURCE_NAME).isPresent()) {
      log.info("AI Hero source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        SOURCE_NAME,
        "https://www.aihero.dev",
        "https://www.aihero.dev/rss.xml",
        null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.AI_HERO,
        true,
        null,
        null,
        null));
    log.info("Seeded AI Hero content source");

    try {
      aggregationAgent.backfillSource(saved, AiHeroScraper.PUBLISHED_FLOOR);
    } catch (Exception e) {
      log.error("AI Hero backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName(SOURCE_NAME)
        .ifPresent(contentSourceRepository::delete);
  }
}
