package com.simonrowe.dataops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the platform datastore backup every night and prunes the platform Drive
 * folder to its retention limit afterwards.
 *
 * <p>Deliberately the same shape as {@link BackupScheduler}: the operator reads
 * both jobs from the same logs, so they should fail, skip and report the same way.
 */
@Component
public class PlatformBackupScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformBackupScheduler.class);

  private final PlatformBackupService platformBackupService;
  private final BackupRetentionService retentionService;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;

  public PlatformBackupScheduler(
      final PlatformBackupService platformBackupService,
      final BackupRetentionService retentionService,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService) {
    this.platformBackupService = platformBackupService;
    this.retentionService = retentionService;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
  }

  /**
   * Runs the nightly platform backup: skips when Drive is not connected or another
   * data operation is already in progress, otherwise captures the platform
   * datastores and, on success, prunes old platform backups to the retention
   * limit.
   *
   * <p>02:00 is four hours clear of the 22:00 application backup, and that gap is
   * load-bearing rather than arbitrary. The application backup zips all media and
   * uploads it over a residential uplink, so it can run long, and
   * {@link DataOperationsService} holds a single global mutex — an overlap does
   * not queue, it costs the platform backup its whole night. On collision this
   * logs and skips with no retry: visible in the logs, and self-correcting the
   * next night.
   */
  @Scheduled(
      cron = "${backup.platform.schedule.cron:0 0 2 * * *}",
      zone = "${backup.platform.schedule.zone:Europe/London}")
  public void runNightlyPlatformBackup() {
    try {
      if (!googleDriveService.isConnected()) {
        LOG.warn("Nightly platform backup skipped: Google Drive is not connected");
        return;
      }
      if (operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP) == null) {
        LOG.warn("Nightly platform backup skipped: another data operation is in progress");
        return;
      }
      LOG.info("Nightly platform backup starting");
      boolean ok = platformBackupService.performBackup();
      if (!ok) {
        // Pruning after a failure would delete a good older archive to make room
        // for one that was never uploaded.
        LOG.error("Nightly platform backup failed; skipping retention prune");
        return;
      }
      // Reported separately from the backup itself: a prune failure after a
      // successful upload is a prune failure, not data loss, and logging it as
      // "backup errored" sends the operator hunting for data that is safely
      // stored.
      try {
        retentionService.prunePlatformToLimit();
      } catch (Exception ex) {
        LOG.error("Nightly platform backup succeeded but retention pruning failed; "
            + "old platform backups are accumulating in Drive", ex);
      }
    } catch (Exception ex) {
      // Nothing may reach the scheduling thread: an escaping exception is how a
      // nightly job stops running and nobody notices for weeks.
      LOG.error("Nightly platform backup job errored", ex);
    }
  }
}
