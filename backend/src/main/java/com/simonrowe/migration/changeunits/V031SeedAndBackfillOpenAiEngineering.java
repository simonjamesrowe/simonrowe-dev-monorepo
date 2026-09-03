package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds OpenAI's engineering section as a content source and pre-populates its archive.
 *
 * <p>The requested section is {@code https://openai.com/news/engineering/}, but neither
 * HTML strategy can read it: <strong>every HTML page on openai.com answers 403</strong>
 * to the scrapers' Chrome user agent (Cloudflare bot protection), so
 * {@code HTML_LISTING} cannot load the listing and {@code SITEMAP_HTML} cannot load the
 * article pages the engineering sitemap points at. Two URL filters would reject the
 * articles even without that: they live at {@code /index/<slug>}, which carries no
 * {@code blog}/{@code news}/{@code article}/{@code post} segment for
 * {@code looksLikeArticle}, and does not start with the listing's own
 * {@code /news/engineering} section for {@code isArticleLink}.
 *
 * <p>Only the XML endpoints are served, so this is {@code RSS} against the site-wide
 * feed. That feed is OpenAI's entire output — over a thousand items across twenty-plus
 * categories — and the engineering section exists in it purely as a
 * {@code <category>Engineering</category>} label. Hence {@code categoryFilter}: without
 * it this source would put every OpenAI company, product and policy announcement on the
 * news page. The filter's 19 matches line up one-for-one with the engineering sitemap,
 * which is the check that it selects the intended section.
 *
 * <p>The cutoff is an absolute instant rather than a relative window because the archive
 * is finite and fixed: the earliest engineering post is 30 Oct 2025. A relative window
 * would make the size of the backfill depend on the date this change unit first runs, so
 * a fresh environment created later would silently ingest less than an earlier one.
 *
 * <p>These items carry a description but no image, so each one is illustrated by
 * {@code BlogImageGenerationService} rather than a downloaded {@code og:image}. Seeding
 * is idempotent via the {@code findByName} guard, and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run. The backfill is wrapped so any
 * failure (LLM, network, Kafka) is logged but never rethrown — a failed pre-population
 * must not block application boot.
 */
@ChangeUnit(id = "seed-and-backfill-openai-engineering", order = "031", author = "simonrowe")
public class V031SeedAndBackfillOpenAiEngineering {

  private static final Logger log =
      LoggerFactory.getLogger(V031SeedAndBackfillOpenAiEngineering.class);

  private static final String SOURCE_NAME = "OpenAI Engineering";

  /** Matched case-insensitively against each feed entry's categories. */
  private static final String CATEGORY = "Engineering";

  /** Slightly before the earliest engineering post (30 Oct 2025), so none is cut. */
  private static final Instant BACKFILL_SINCE = Instant.parse("2025-10-01T00:00:00Z");

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName(SOURCE_NAME).isPresent()) {
      log.info("OpenAI Engineering source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        SOURCE_NAME,
        "https://openai.com/news/engineering/",
        "https://openai.com/news/rss.xml",
        null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.RSS,
        true,
        null,
        null,
        CATEGORY));
    log.info("Seeded OpenAI Engineering content source");

    try {
      aggregationAgent.backfillSource(saved, BACKFILL_SINCE);
    } catch (Exception e) {
      log.error("OpenAI Engineering backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName(SOURCE_NAME)
        .ifPresent(contentSourceRepository::delete);
  }
}
