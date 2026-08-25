package com.simonrowe.dataops;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Covers the two endpoints that make the platform backup usable on demand.
 *
 * <p>The admin-auth assertions are not ceremony: these endpoints read and write
 * a Drive folder holding every platform secret's worth of restorable data, and
 * they share a global mutex with destructive operations like {@code /clear}.
 */
class DataOperationsControllerPlatformTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/admin/data-operations";

  @MockitoBean
  private PlatformBackupService platformBackupService;

  @MockitoBean
  private GoogleDriveService googleDriveService;

  @Autowired
  private DataOperationsService operationsService;

  @AfterEach
  void releaseMutex() {
    // The mutex is process-wide state on a shared context; a test that starts an
    // operation and does not clear it would fail every later test with a 409.
    operationsService.completeOperation("test cleanup");
  }

  private List<BackupMetadata> platformBackups() {
    return List.of(
        new BackupMetadata("id-1", "platform-backup-20260825-020000.zip",
            Instant.parse("2026-08-25T02:00:00Z"), 913448201L, "871.1 MB"),
        new BackupMetadata("id-2", "platform-backup-20260824-020000.zip",
            Instant.parse("2026-08-24T02:00:00Z"), 900000000L, "858.3 MB"));
  }

  // ---------------------------------------------------------------------------
  // POST /platform-backup
  // ---------------------------------------------------------------------------

  @Test
  void startPlatformBackupReturnsAcceptedAndRunsTheCapture() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(platformBackupService.performBackup()).thenReturn(true);

    mockMvc.perform(post(BASE + "/platform-backup").with(adminJwt()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.type").value("PLATFORM_BACKUP"))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    // Runs asynchronously on a CompletableFuture, like its siblings, so the
    // verification has to wait rather than assert immediately.
    verify(platformBackupService, timeout(5_000)).performBackup();
  }

  @Test
  void startPlatformBackupIsUnavailableWhenDriveIsNotConnected() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(false);

    mockMvc.perform(post(BASE + "/platform-backup").with(adminJwt()))
        .andExpect(status().isServiceUnavailable());

    verify(platformBackupService, never()).performBackup();
  }

  /**
   * Refused, not queued. Two captures at once would fight over the same
   * ClickHouse staging file and the same operation-progress stream.
   */
  @Test
  void startPlatformBackupConflictsWhenAnotherOperationIsInProgress() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(true);
    operationsService.tryStartOperation(OperationType.CLEAR);

    mockMvc.perform(post(BASE + "/platform-backup").with(adminJwt()))
        .andExpect(status().isConflict());

    verify(platformBackupService, never()).performBackup();
  }

  @Test
  void startPlatformBackupRejectsAnonymousCallers() throws Exception {
    mockMvc.perform(post(BASE + "/platform-backup"))
        .andExpect(status().isUnauthorized());

    verify(platformBackupService, never()).performBackup();
  }

  @Test
  void startPlatformBackupRejectsNonAdminCallers() throws Exception {
    mockMvc.perform(post(BASE + "/platform-backup").with(jwt()))
        .andExpect(status().isForbidden());

    verify(platformBackupService, never()).performBackup();
  }

  // ---------------------------------------------------------------------------
  // GET /platform-backups
  // ---------------------------------------------------------------------------

  @Test
  void listPlatformBackupsReturnsTheRetainedArchives() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(platformBackups());

    mockMvc.perform(get(BASE + "/platform-backups").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].fileName").value("platform-backup-20260825-020000.zip"))
        .andExpect(jsonPath("$[0].fileSizeFormatted").value("871.1 MB"));
  }

  /**
   * The separation assertion at the HTTP layer: listing platform backups must
   * never resolve the application folder, or the operator would see one list and
   * believe it described the other.
   */
  @Test
  void listPlatformBackupsNeverReadsTheApplicationFolder() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(platformBackups());

    mockMvc.perform(get(BASE + "/platform-backups").with(adminJwt()))
        .andExpect(status().isOk());

    verify(googleDriveService).findOrCreatePlatformFolder();
    verify(googleDriveService, never()).findOrCreateFolder();
  }

  @Test
  void listPlatformBackupsIsUnavailableWhenDriveIsNotConnected() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(false);

    mockMvc.perform(get(BASE + "/platform-backups").with(adminJwt()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void listPlatformBackupsReportsDriveFailureAsServerError() throws Exception {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder())
        .thenThrow(new java.io.IOException("drive unreachable"));

    mockMvc.perform(get(BASE + "/platform-backups").with(adminJwt()))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void listPlatformBackupsRejectsAnonymousCallers() throws Exception {
    mockMvc.perform(get(BASE + "/platform-backups"))
        .andExpect(status().isUnauthorized());

    verify(googleDriveService, never()).listBackups(any());
  }

  @Test
  void listPlatformBackupsRejectsNonAdminCallers() throws Exception {
    mockMvc.perform(get(BASE + "/platform-backups").with(jwt()))
        .andExpect(status().isForbidden());

    verify(googleDriveService, never()).listBackups(any());
  }
}
