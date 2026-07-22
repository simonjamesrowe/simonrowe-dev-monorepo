package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backfills {@code publishedDate} for aggregated articles that were saved without
 * one — e.g. manually imported pages (the AWS "Agent Toolkit" page) and feed items
 * (Rundown AI) whose date could not be scraped, LLM-extracted, or parsed from the
 * detail page.
 *
 * <p>The news page lists articles ordered by {@code publishedDate} descending, and
 * MongoDB sorts null/missing values last, so dateless articles were buried past the
 * page limit and never appeared. This sets the missing {@code publishedDate} to the
 * article's {@code fetchedAt} (the date it was added), matching the new fallback in
 * {@code ContentAggregationAgent#processArticle}. Only touches articles with a null
 * {@code publishedDate}, so it is safe and idempotent.
 */
@ChangeUnit(id = "backfill-article-published-dates", order = "010", author = "simonrowe")
public class V010BackfillArticlePublishedDates {

  private static final Logger log =
      LoggerFactory.getLogger(V010BackfillArticlePublishedDates.class);

  @Execution
  public void execution(final AggregatedArticleRepository articleRepository) {
    List<AggregatedArticle> articles = articleRepository.findAll();
    int updated = 0;
    for (AggregatedArticle article : articles) {
      if (article.publishedDate() != null) {
        continue;
      }
      Instant fallback =
          article.fetchedAt() != null ? article.fetchedAt() : Instant.now();
      articleRepository.save(new AggregatedArticle(
          article.id(), article.title(), article.sourceName(),
          article.sourceUrl(), article.originalUrl(), article.summary(),
          article.fullContent(), article.author(), fallback,
          article.fetchedAt(), article.visible(), article.imageUrl()));
      updated++;
    }
    log.info("Backfilled publishedDate for {} dateless articles", updated);
  }

  @RollbackExecution
  public void rollback() {
    // Additive backfill of a previously-null field; nothing to roll back.
  }
}
