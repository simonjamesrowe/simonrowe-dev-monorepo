package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repoints the Claude Blog source from the alphabetically-sorted sitemap
 * (SITEMAP_HTML) to the chronological listing page (HTML_LISTING).
 *
 * <p>The sitemap at claude.com/sitemap.xml is ordered alphabetically and has no
 * {@code <lastmod>} dates, so SITEMAP_HTML (capped at the first 20 matching URLs)
 * only ever ingested the alphabetically-first posts (1m-context, a-field-guide,
 * agent-*, amazon-bedrock...) and never the newest ones. The listing page at
 * claude.com/blog server-renders every article anchor in newest-first order,
 * which HTML_LISTING reads directly. This change also clears the previously
 * mis-ingested Claude Blog articles so the correct, latest set re-seeds on the
 * next aggregation run.
 */
@ChangeUnit(id = "repoint-claude-blog-html-listing", order = "009", author = "simonrowe")
public class V009RepointClaudeBlogToListing {

  private static final Logger log =
      LoggerFactory.getLogger(V009RepointClaudeBlogToListing.class);

  @Execution
  public void execution(
      final ContentSourceRepository sourceRepository,
      final AggregatedArticleRepository articleRepository) {

    sourceRepository.findByName("Claude Blog").ifPresent(source -> {
      sourceRepository.save(new ContentSource(
          source.id(),
          source.name(),
          "https://claude.com/blog",
          source.feedUrl(),
          source.sitemapUrl(),
          source.sourceType(),
          ContentSource.ScrapeStrategy.HTML_LISTING,
          source.active(),
          null,
          null));
      log.info("Repointed Claude Blog source to HTML_LISTING and reset lastFetchedAt");
    });

    List<AggregatedArticle> articles = articleRepository.findAll();
    int deleted = 0;
    for (AggregatedArticle article : articles) {
      if ("Claude Blog".equals(article.sourceName())) {
        articleRepository.delete(article);
        deleted++;
      }
    }
    log.info("Deleted {} stale Claude Blog articles for re-ingestion", deleted);
  }

  @RollbackExecution
  public void rollback() {
    // Cleanup/re-ingestion operation; nothing to roll back.
  }
}
