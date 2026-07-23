package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges the duplicate Dan Vega news source into a single one.
 *
 * <p>Articles imported manually via URL are tagged with the bare hostname
 * ({@code danvega.dev}, from {@code extractHostName}), whereas articles picked up
 * by the seeded {@code HTML_LISTING} content source (V011) are tagged with its
 * name ({@code Dan Vega}). Because the news filter pills are derived from the
 * distinct {@code sourceName} values on articles, this produced two pills for the
 * same blog. This change re-tags the {@code danvega.dev} articles as
 * {@code Dan Vega} so they collapse into one source.
 *
 * <p>Only {@code sourceName} is rewritten; {@code originalUrl} (the unique key) is
 * untouched, so the operation is safe to re-run and cannot violate the unique
 * index.
 */
@ChangeUnit(id = "merge-dan-vega-sources", order = "012", author = "simonrowe")
public class V012MergeDanVegaSources {

  private static final Logger log =
      LoggerFactory.getLogger(V012MergeDanVegaSources.class);

  private static final String LEGACY_SOURCE_NAME = "danvega.dev";
  private static final String CANONICAL_SOURCE_NAME = "Dan Vega";

  @Execution
  public void execution(final AggregatedArticleRepository articleRepository) {
    List<AggregatedArticle> legacy =
        articleRepository.findBySourceName(LEGACY_SOURCE_NAME);
    if (legacy.isEmpty()) {
      log.info("No '{}' articles found; nothing to merge", LEGACY_SOURCE_NAME);
      return;
    }

    for (AggregatedArticle article : legacy) {
      articleRepository.save(new AggregatedArticle(
          article.id(),
          article.title(),
          CANONICAL_SOURCE_NAME,
          article.sourceUrl(),
          article.originalUrl(),
          article.summary(),
          article.fullContent(),
          article.author(),
          article.publishedDate(),
          article.fetchedAt(),
          article.visible(),
          article.imageUrl()));
    }
    log.info("Re-tagged {} '{}' articles as '{}'",
        legacy.size(), LEGACY_SOURCE_NAME, CANONICAL_SOURCE_NAME);
  }

  @RollbackExecution
  public void rollback() {
    // Data re-tagging operation; nothing to roll back.
  }
}
