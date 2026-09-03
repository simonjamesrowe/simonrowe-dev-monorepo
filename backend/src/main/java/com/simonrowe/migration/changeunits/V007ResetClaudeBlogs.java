package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes recently scraped Claude blogs so they can be re-ingested
 * with the correct titles and images.
 */
@ChangeUnit(id = "reset-claude-blogs", order = "007", author = "simonrowe")
public class V007ResetClaudeBlogs {

  private static final Logger log = LoggerFactory.getLogger(V007ResetClaudeBlogs.class);

  @Execution
  public void execution(
      final AggregatedArticleRepository articleRepository,
      final ContentSourceRepository sourceRepository) {
    
    Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
    List<AggregatedArticle> articles = articleRepository.findAll();
    
    int deleted = 0;
    for (AggregatedArticle article : articles) {
      if ("Claude Blog".equals(article.sourceName()) 
          && article.fetchedAt() != null 
          && article.fetchedAt().isAfter(oneWeekAgo)) {
        log.info("Deleting recent Claude blog: {}", article.title());
        articleRepository.delete(article);
        deleted++;
      }
    }
    log.info("Deleted {} recent Claude blogs for re-ingestion", deleted);
    
    // Reset the lastFetchedAt for the source to ensure it triggers immediately
    // on the next aggregation run
    sourceRepository.findByName("Claude Blog").ifPresent(source -> {
      sourceRepository.save(new com.simonrowe.aggregation.ContentSource(
          source.id(), source.name(), source.baseUrl(),
          source.feedUrl(), source.sitemapUrl(),
          source.sourceType(), source.scrapeStrategy(),
          source.active(), null, null, source.categoryFilter()));
      log.info("Reset lastFetchedAt for Claude Blog source");
    });
  }

  @RollbackExecution
  public void rollback() {
    // Left empty as this is a cleanup operation for re-ingestion
  }
}
