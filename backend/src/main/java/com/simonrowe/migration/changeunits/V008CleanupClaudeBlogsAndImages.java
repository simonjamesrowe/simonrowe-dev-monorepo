package com.simonrowe.migration.changeunits;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

@ChangeUnit(id = "cleanup-claude-blogs-and-images", order = "008", author = "simonrowe")
public class V008CleanupClaudeBlogsAndImages {
  private static final Logger log = LoggerFactory.getLogger(V008CleanupClaudeBlogsAndImages.class);
  private static final Pattern LOCALIZED_URL_PATTERN = 
      Pattern.compile("(?i)^https?://[^/]+/(de|fr|it|ja|ko|es|zh|ru|pt|nl)/.*");

  @Execution
  public void execution(AggregatedArticleRepository repository) {
    List<AggregatedArticle> allContent = repository.findAll();
    int deleted = 0;
    int updated = 0;
    
    for (AggregatedArticle content : allContent) {
      boolean shouldDelete = false;
      
      // Delete localized Claude blogs
      if (content.originalUrl() != null 
          && LOCALIZED_URL_PATTERN.matcher(content.originalUrl()).matches()) {
        shouldDelete = true;
      }
      
      // Delete generic weird images (like the giant hand from Rundown AI or Tessl)
      // Wait, actually, let's just let the frontend handle fallback images.
      
      if (shouldDelete) {
        repository.delete(content);
        deleted++;
      }
    }
    
    log.info("Deleted {} localized/invalid articles", deleted);
  }

  @RollbackExecution
  public void rollback() {
  }
}
