package com.simonrowe.migration.changeunits;

import com.simonrowe.media.ArticleImageBackfillService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Backfills images for visible aggregated articles created without one — notably
 * Spring Blog RSS items, whose feed carries no image and which fell back to the
 * (then-broken) DALL-E 3 generator, leaving blank cards on the news page.
 *
 * <p>Delegates to {@link ArticleImageBackfillService}, which only touches
 * articles that still have no image, so this change unit is safe and idempotent.
 */
@ChangeUnit(id = "backfill-article-images", order = "002", author = "simonrowe")
public class V002BackfillArticleImages {

  @Execution
  public void execution(final ArticleImageBackfillService backfillService) {
    backfillService.backfillMissingImages();
  }

  @RollbackExecution
  public void rollback() {
    // Generated images are additive (new media assets + an imageUrl on articles
    // that had none); there is nothing meaningful to roll back.
  }
}
