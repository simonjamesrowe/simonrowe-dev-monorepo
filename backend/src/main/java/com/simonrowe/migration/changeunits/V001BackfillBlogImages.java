package com.simonrowe.migration.changeunits;

import com.simonrowe.media.BlogImageBackfillService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Backfills featured images for any blog created without one (notably the weekly
 * roundups generated while the DALL-E 3 model was retired and the generator
 * silently returned no image).
 *
 * <p>Delegates to {@link BlogImageBackfillService}, which only touches blogs that
 * still have no image, so this change unit is safe and idempotent.
 */
@ChangeUnit(id = "backfill-blog-featured-images", order = "001", author = "simonrowe")
public class V001BackfillBlogImages {

  @Execution
  public void execution(final BlogImageBackfillService backfillService) {
    backfillService.backfillMissingImages();
  }

  @RollbackExecution
  public void rollback() {
    // Generated images are additive (new media assets + a featuredImageUrl on
    // blogs that had none); there is nothing meaningful to roll back.
  }
}
