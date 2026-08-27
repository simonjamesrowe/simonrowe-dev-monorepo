package com.simonrowe.dataops;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Covers the one platform-backup endpoint the backend still serves.
 *
 * <p>Listing is all that is left here: the capture runs in the {@code deployer} as
 * {@code scripts/backup-platform.sh}, because constitution 2.0.0 forbids this
 * container from holding Docker access. Reading a Drive folder is a network call, so
 * it stays.
 *
 * <p>The admin-auth assertions are not ceremony — this endpoint names the archives
 * holding every platform secret's worth of restorable data.
 */
class DataOperationsControllerPlatformTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/admin/data-operations";

  @MockitoBean
  private GoogleDriveService googleDriveService;

  private List<BackupMetadata> platformBackups() {
    return List.of(
        new BackupMetadata("id-1", "platform-backup-20260825-020000.zip",
            Instant.parse("2026-08-25T02:00:00Z"), 913448201L, "871.1 MB"),
        new BackupMetadata("id-2", "platform-backup-20260824-020000.zip",
            Instant.parse("2026-08-24T02:00:00Z"), 900000000L, "858.3 MB"));
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
