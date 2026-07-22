package com.simonrowe.dataops;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Prunes the Google Drive backups folder so only the newest
 * {@code backup.retention.max-backups} backups are retained.
 */
@Service
public class BackupRetentionService {

  private static final Logger LOG =
      LoggerFactory.getLogger(BackupRetentionService.class);

  private final GoogleDriveService googleDriveService;

  @Value("${backup.retention.max-backups:7}")
  private int maxBackups;

  public BackupRetentionService(final GoogleDriveService googleDriveService) {
    this.googleDriveService = googleDriveService;
  }

  /**
   * Deletes all but the newest {@code maxBackups} backups. Safe no-op when
   * Drive is not connected. A failure deleting one backup is logged and does
   * not abort the sweep.
   *
   * @return the number of backups successfully deleted
   */
  public int pruneToLimit() {
    if (!googleDriveService.isConnected()) {
      LOG.warn("Backup retention skipped: Google Drive is not connected");
      return 0;
    }
    try {
      String folderId = googleDriveService.findOrCreateFolder();
      List<BackupMetadata> backups = googleDriveService.listBackups(folderId);
      if (backups.size() <= maxBackups) {
        LOG.info("Backup retention: {} backups present, within limit of {}",
            backups.size(), maxBackups);
        return 0;
      }
      List<BackupMetadata> toDelete = backups.subList(maxBackups, backups.size());
      int deleted = 0;
      for (BackupMetadata backup : toDelete) {
        try {
          googleDriveService.deleteFile(backup.fileId());
          LOG.info("Backup retention: deleted old backup {}", backup.fileName());
          deleted++;
        } catch (IOException ex) {
          LOG.error("Backup retention: failed to delete {}: {}",
              backup.fileName(), ex.getMessage());
        }
      }
      LOG.info("Backup retention: deleted {} of {} over-limit backups",
          deleted, toDelete.size());
      return deleted;
    } catch (IOException ex) {
      LOG.error("Backup retention failed: {}", ex.getMessage());
      return 0;
    }
  }
}
