package com.simonrowe.dataops;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Prunes the Google Drive backups folder so only the newest N application backups are
 * retained.
 *
 * <p>Platform-backup retention is deliberately <em>not</em> here: that archive is
 * captured and pruned by {@code scripts/backup-platform.sh} in the {@code deployer},
 * so its whole lifecycle has one owner. The separation the two folders exist to
 * preserve is unchanged — the sweep deletes everything past the newest N {@code .zip}
 * in whichever folder it is pointed at, so a shared folder would make the two backup
 * types evict each other and quietly halve both recovery windows.
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
   * Deletes all but the newest {@code backup.retention.max-backups} application
   * backups. Safe no-op when Drive is not connected.
   *
   * @return the number of backups successfully deleted
   */
  public int pruneToLimit() {
    if (!googleDriveService.isConnected()) {
      LOG.warn("Backup retention skipped: Google Drive is not connected");
      return 0;
    }
    try {
      return pruneToLimit(googleDriveService.findOrCreateFolder(), maxBackups);
    } catch (IOException ex) {
      LOG.error("Backup retention failed: {}", ex.getMessage());
      return 0;
    }
  }

  /**
   * Deletes all but the newest {@code maxRetained} backups in one folder.
   *
   * <p>A failure deleting one backup is logged and does not abort the sweep,
   * whether or not it is an {@link IOException} — Drive client failures are not
   * always checked exceptions, and one file must never cost the whole sweep.
   *
   * @param folderId the Drive folder to prune
   * @param maxRetained how many of the newest backups to keep
   * @return the number of backups successfully deleted
   */
  public int pruneToLimit(final String folderId, final int maxRetained) {
    try {
      List<BackupMetadata> backups = googleDriveService.listBackups(folderId);
      if (backups.size() <= maxRetained) {
        LOG.info("Backup retention: {} backups present in folder {}, within limit of {}",
            backups.size(), folderId, maxRetained);
        return 0;
      }
      List<BackupMetadata> toDelete = backups.subList(maxRetained, backups.size());
      int deleted = 0;
      for (BackupMetadata backup : toDelete) {
        try {
          googleDriveService.deleteFile(backup.fileId());
          LOG.info("Backup retention: deleted old backup {}", backup.fileName());
          deleted++;
        } catch (IOException | RuntimeException ex) {
          LOG.error("Backup retention: failed to delete {}", backup.fileName(), ex);
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
