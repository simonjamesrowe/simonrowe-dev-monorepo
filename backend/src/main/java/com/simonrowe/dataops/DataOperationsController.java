package com.simonrowe.dataops;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin/data-operations")
public class DataOperationsController {

  private static final Logger LOG =
      LoggerFactory.getLogger(DataOperationsController.class);

  private final DataOperationsService operationsService;
  private final GoogleDriveService googleDriveService;
  private final BackupService backupService;
  private final RestoreService restoreService;
  private final ClearService clearService;
  private final com.simonrowe.search.IndexService indexService;
  private final com.simonrowe.embedding.EmbeddingService embeddingService;

  public DataOperationsController(
      final DataOperationsService operationsService,
      final GoogleDriveService googleDriveService,
      final BackupService backupService,
      final RestoreService restoreService,
      final ClearService clearService,
      final com.simonrowe.search.IndexService indexService,
      final com.simonrowe.embedding.EmbeddingService embeddingService
  ) {
    this.operationsService = operationsService;
    this.googleDriveService = googleDriveService;
    this.backupService = backupService;
    this.restoreService = restoreService;
    this.clearService = clearService;
    this.indexService = indexService;
    this.embeddingService = embeddingService;
  }

  @GetMapping("/status")
  public DataOperationsStatus getStatus() {
    return operationsService.getStatus();
  }

  @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamProgress() {
    return operationsService.streamProgress();
  }

  @PostMapping("/backup")
  public ResponseEntity<DataOperation> startBackup() {
    requireDriveConnected();
    DataOperation operation = requireNoOperationInProgress(OperationType.BACKUP);
    CompletableFuture.runAsync(() -> backupService.performBackup());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }

  @GetMapping("/backups")
  public List<BackupMetadata> listBackups() {
    requireDriveConnected();
    try {
      String folderId = googleDriveService.findOrCreateFolder();
      return googleDriveService.listBackups(folderId);
    } catch (IOException ex) {
      LOG.error("Failed to list backups", ex);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to list backups: " + ex.getMessage());
    }
  }

  /**
   * Lists the retained platform archives, newest first.
   *
   * <p>Reads the <em>platform</em> Drive folder, which is deliberately a different
   * folder from the one {@link #listBackups()} reads. The two retention windows are
   * independent so that neither backup type can evict the other.
   *
   * <p>This is the whole of the backend's involvement in the platform backup. The
   * capture itself runs in the {@code deployer} container as {@code
   * scripts/backup-platform.sh}, because constitution 2.0.0 forbids this container —
   * the one terminating public traffic — from holding the Docker socket or launching
   * a host process. Listing a Drive folder is a network call, so it stays here.
   *
   * @return the retained platform archives
   */
  @GetMapping("/platform-backups")
  public List<BackupMetadata> listPlatformBackups() {
    requireDriveConnected();
    try {
      String folderId = googleDriveService.findOrCreatePlatformFolder();
      return googleDriveService.listBackups(folderId);
    } catch (IOException ex) {
      LOG.error("Failed to list platform backups", ex);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to list platform backups: " + ex.getMessage());
    }
  }

  // No platform capture or restore endpoint here, for two different reasons.
  // Capture: constitution 2.0.0 forbids host-level container access in this
  // container, so it runs in the `deployer` on a Temporal schedule. An admin-UI
  // trigger is deferred, not cancelled - it needs a Temporal client here.
  // Restore: scripts/restore-platform.sh, because the scenario that motivates it is
  // a rebuilt host, where this application is the thing being rebuilt.

  @PostMapping("/restore")
  public ResponseEntity<DataOperation> startRestore(
      @RequestBody final RestoreRequest request
  ) {
    if (request.backupFileId() == null || request.backupFileId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "backupFileId is required");
    }
    requireDriveConnected();
    DataOperation operation = requireNoOperationInProgress(OperationType.RESTORE);
    CompletableFuture.runAsync(
        () -> restoreService.performRestore(request.backupFileId()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }

  @PostMapping("/clear")
  public ResponseEntity<DataOperation> startClear(
      @RequestBody final ClearRequest request
  ) {
    if (!ClearRequest.REQUIRED_PHRASE.equals(request.confirmationPhrase())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Confirmation phrase must be exactly: " + ClearRequest.REQUIRED_PHRASE);
    }
    DataOperation operation = requireNoOperationInProgress(OperationType.CLEAR);
    CompletableFuture.runAsync(clearService::performClear);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }

  @PostMapping("/rebuild-index")
  public ResponseEntity<DataOperation> startRebuildIndex() {
    DataOperation operation =
        requireNoOperationInProgress(OperationType.REBUILD_INDEX);
    CompletableFuture.runAsync(() -> {
      try {
        operationsService.updateProgress("Rebuilding site search index...", 20);
        indexService.fullSyncSiteIndex();
        operationsService.updateProgress("Rebuilding blog search index...", 60);
        indexService.fullSyncBlogIndex();
        operationsService.completeOperation("Search indices rebuilt successfully");
      } catch (IOException ex) {
        LOG.error("Index rebuild failed", ex);
        operationsService.failOperation(
            "Index rebuild failed: " + ex.getMessage());
      }
    });
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }

  @PostMapping("/reembed")
  public ResponseEntity<DataOperation> startReembed() {
    DataOperation operation =
        requireNoOperationInProgress(OperationType.REEMBED_CONTENT);
    CompletableFuture.runAsync(() -> {
      try {
        int total = 0;
        operationsService.updateProgress("Embedding blog posts...", 5);
        total += embeddingService.embedAllBlogs();
        operationsService.updateProgress("Embedding jobs...", 25);
        total += embeddingService.embedAllJobs();
        operationsService.updateProgress("Embedding skills...", 45);
        total += embeddingService.embedAllSkills();
        operationsService.updateProgress("Embedding code examples...", 65);
        total += embeddingService.embedAllCodeExamples();
        operationsService.updateProgress("Embedding articles...", 80);
        total += embeddingService.embedAllArticles();
        operationsService.updateProgress("Embedding events...", 92);
        total += embeddingService.embedAllEvents();
        operationsService.completeOperation(
            "Re-embedded " + total + " items successfully");
      } catch (Exception ex) {
        LOG.error("Re-embedding failed", ex);
        operationsService.failOperation(
            "Re-embedding failed: " + ex.getMessage());
      }
    });
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }

  private void requireDriveConnected() {
    if (!googleDriveService.isConnected()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Google Drive is not connected. "
              + "Configure GOOGLE_DRIVE_CREDENTIALS to enable this feature.");
    }
  }

  private DataOperation requireNoOperationInProgress(
      final OperationType type
  ) {
    DataOperation operation = operationsService.tryStartOperation(type);
    if (operation == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Another data operation is already in progress");
    }
    return operation;
  }
}
