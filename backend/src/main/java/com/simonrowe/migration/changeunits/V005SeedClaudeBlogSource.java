package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Seeds the Claude Blog content source.
 */
@ChangeUnit(id = "seed-claude-blog-source", order = "005", author = "simonrowe")
public class V005SeedClaudeBlogSource {

  @Execution
  public void execution(final ContentSourceRepository contentSourceRepository) {
    if (contentSourceRepository.findByName("Claude Blog").isEmpty()) {
      ContentSource source = new ContentSource(
          null,
          "Claude Blog",
          "https://claude.com/blog",
          null,
          "https://claude.com/sitemap.xml",
          ContentSource.SourceType.NEWS,
          ContentSource.ScrapeStrategy.SITEMAP_HTML,
          true,
          null,
          null,
          null
      );
      contentSourceRepository.save(source);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName("Claude Blog").ifPresent(contentSourceRepository::delete);
  }
}
