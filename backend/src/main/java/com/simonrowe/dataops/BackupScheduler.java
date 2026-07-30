package com.simonrowe.dataops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs a full backup every night and prunes the Drive folder to the retention
 * limit afterwards.
 */
@Component
@EnableScheduling
public class BackupScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupService backupService;
  private final BackupRetentionService retentionService;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;

  public BackupScheduler(
      final BackupService backupService,
      final BackupRetentionService retentionService,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService) {
    this.backupService = backupService;
    this.retentionService = retentionService;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
  }

  /**
   * Runs the nightly backup job: skips when Drive is not connected or another
   * data operation is already in progress, otherwise performs a full backup
   * and, on success, prunes old backups to the retention limit.
   */
  @Scheduled(
      cron = "${backup.schedule.cron:0 0 22 * * *}",
      zone = "${backup.schedule.zone:Europe/London}")
  public void runNightlyBackup() {
    try {
      if (!googleDriveService.isConnected()) {
        LOG.warn("Nightly backup skipped: Google Drive is not connected");
        return;
      }
      if (operationsService.tryStartOperation(OperationType.BACKUP) == null) {
        LOG.warn("Nightly backup skipped: another data operation is in progress");
        return;
      }
      LOG.info("Nightly backup starting");
      boolean ok = backupService.performBackup();
      if (!ok) {
        LOG.error("Nightly backup failed; skipping retention prune");
        return;
      }
      // Reported separately from the backup itself. A prune failure used to
      // surface as "Nightly backup job errored" even though the backup had
      // uploaded successfully, which reads as data loss when it is not.
      try {
        retentionService.pruneToLimit();
      } catch (Exception ex) {
        LOG.error("Nightly backup succeeded but retention pruning failed; "
            + "old backups are accumulating in Drive", ex);
      }
    } catch (Exception ex) {
      LOG.error("Nightly backup job errored", ex);
    }
  }
}
