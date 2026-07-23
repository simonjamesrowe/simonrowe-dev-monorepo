package com.simonrowe.migration.changeunits;

import com.simonrowe.dataops.BackupRetentionService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time baseline prune of the Google Drive backups folder down to the
 * retention limit. Ongoing pruning happens after each nightly backup; this
 * change-unit just trims any pre-existing backlog on first deploy. Safe no-op
 * when Drive is not connected.
 */
@ChangeUnit(id = "prune-backups-to-retention-limit", order = "010", author = "simonrowe")
public class V010PruneBackupsToRetentionLimit {
  private static final Logger log = LoggerFactory.getLogger(V010PruneBackupsToRetentionLimit.class);

  @Execution
  public void execution(BackupRetentionService retentionService) {
    int deleted = retentionService.pruneToLimit();
    log.info("Baseline backup prune removed {} over-limit backups", deleted);
  }

  @RollbackExecution
  public void rollback() {
  }
}
