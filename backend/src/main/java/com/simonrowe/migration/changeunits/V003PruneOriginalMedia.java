package com.simonrowe.migration.changeunits;

import com.simonrowe.media.OriginalMediaPruneService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Reclaims disk and backup space by deleting the never-served {@code original.*}
 * media files, after rewriting the inline markdown references that point at them
 * to the equivalent variant.
 *
 * <p>Delegates to {@link OriginalMediaPruneService}, which keeps every media
 * asset document intact and leaves variant-less assets (e.g. SVGs) untouched, so
 * this change unit is safe and idempotent. Originals remain available in the
 * Google Drive media backup.
 */
@ChangeUnit(id = "prune-original-media", order = "003", author = "simonrowe")
public class V003PruneOriginalMedia {

  @Execution
  public void execution(final OriginalMediaPruneService pruneService) {
    pruneService.prune();
  }

  @RollbackExecution
  public void rollback() {
    // Deletion is not reversible from here — originals are preserved in the
    // Google Drive media backup and every variant remains on disk.
  }
}
