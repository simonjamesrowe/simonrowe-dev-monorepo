package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BackupRetentionServiceTest {

  @Mock
  private GoogleDriveService googleDriveService;

  private BackupRetentionService newService(final int maxBackups) {
    BackupRetentionService service = new BackupRetentionService(googleDriveService);
    ReflectionTestUtils.setField(service, "maxBackups", maxBackups);
    return service;
  }

  private BackupRetentionService newService(final int maxBackups,
      final int platformMaxBackups) {
    BackupRetentionService service = newService(maxBackups);
    ReflectionTestUtils.setField(service, "platformMaxBackups", platformMaxBackups);
    return service;
  }

  private List<BackupMetadata> backups(final int count) {
    List<BackupMetadata> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(new BackupMetadata("id-" + i, "backup-" + i + ".zip",
          Instant.now(), 100L, "100 B"));
    }
    return list;
  }

  @Test
  void deletesEverythingBeyondTheNewestSeven() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreateFolder()).thenReturn("folder-1");
    when(googleDriveService.listBackups("folder-1")).thenReturn(backups(10));

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isEqualTo(3);
    verify(googleDriveService).deleteFile("id-7");
    verify(googleDriveService).deleteFile("id-8");
    verify(googleDriveService).deleteFile("id-9");
    verify(googleDriveService, never()).deleteFile("id-6");
  }

  @Test
  void deletesNothingWhenAtOrBelowLimit() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreateFolder()).thenReturn("folder-1");
    when(googleDriveService.listBackups("folder-1")).thenReturn(backups(5));

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isEqualTo(0);
    verify(googleDriveService, never()).deleteFile(anyString());
  }

  @Test
  void isNoOpWhenDriveNotConnected() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(false);

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isEqualTo(0);
    verify(googleDriveService, never()).deleteFile(anyString());
  }

  @Test
  void continuesSweepWhenOneDeleteFails() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreateFolder()).thenReturn("folder-1");
    when(googleDriveService.listBackups("folder-1")).thenReturn(backups(10));
    doThrow(new IOException("boom")).when(googleDriveService).deleteFile("id-7");

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isEqualTo(2);
    verify(googleDriveService, times(3)).deleteFile(anyString());
  }

  /**
   * Regression: the sweep only caught {@link IOException}, so an unchecked failure from the Drive
   * client aborted it entirely. A {@code files.delete} returning 204 No Content made
   * {@code Apache5HttpResponse.getContent()} dereference a null entity, and the resulting NPE
   * escaped to the scheduler — leaving every remaining over-limit backup in place.
   */
  @Test
  void continuesSweepWhenOneDeleteThrowsAnUncheckedException() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreateFolder()).thenReturn("folder-1");
    when(googleDriveService.listBackups("folder-1")).thenReturn(backups(10));
    doThrow(new NullPointerException("Cannot invoke \"HttpEntity.getContent()\""))
        .when(googleDriveService).deleteFile("id-7");

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isEqualTo(2);
    verify(googleDriveService, times(3)).deleteFile(anyString());
  }

  @Test
  void keepsSweepingWhenEveryDeleteThrowsUnchecked() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreateFolder()).thenReturn("folder-1");
    when(googleDriveService.listBackups("folder-1")).thenReturn(backups(10));
    doThrow(new IllegalStateException("drive unhappy"))
        .when(googleDriveService).deleteFile(anyString());

    BackupRetentionService service = newService(7);
    int deleted = service.pruneToLimit();

    assertThat(deleted).isZero();
    verify(googleDriveService, times(3)).deleteFile(anyString());
  }

  // ---------------------------------------------------------------------------
  // Platform backups. The application assertions above are unchanged on purpose:
  // "the existing backup still behaves exactly as it did" is the requirement most
  // likely to regress here, so it is verified rather than assumed.
  // ---------------------------------------------------------------------------

  @Test
  void prunesThePlatformFolderToItsOwnLimit() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(backups(10));

    BackupRetentionService service = newService(7, 7);
    int deleted = service.prunePlatformToLimit();

    assertThat(deleted).isEqualTo(3);
    verify(googleDriveService).deleteFile("id-7");
    verify(googleDriveService, never()).deleteFile("id-6");
  }

  /**
   * The two windows are configured independently, so a change to one must not move
   * the other.
   */
  @Test
  void honoursIndependentLimitsForTheTwoBackupTypes() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(backups(10));

    BackupRetentionService service = newService(7, 3);
    int deleted = service.prunePlatformToLimit();

    assertThat(deleted).isEqualTo(7);
  }

  /**
   * The load-bearing separation assertion: pruning platform backups must resolve
   * the platform folder and never touch the application folder. A regression here
   * would silently halve both recovery windows.
   */
  @Test
  void neverResolvesTheApplicationFolderWhenPruningPlatformBackups() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(backups(2));

    BackupRetentionService service = newService(7, 7);
    service.prunePlatformToLimit();

    verify(googleDriveService, never()).findOrCreateFolder();
    verify(googleDriveService, never()).listBackups("folder-1");
  }

  @Test
  void platformPruneIsNoOpWhenDriveNotConnected() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(false);

    BackupRetentionService service = newService(7, 7);

    assertThat(service.prunePlatformToLimit()).isZero();
    verify(googleDriveService, never()).findOrCreatePlatformFolder();
    verify(googleDriveService, never()).deleteFile(anyString());
  }

  @Test
  void platformPruneContinuesSweepWhenOneDeleteFails() throws IOException {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(googleDriveService.findOrCreatePlatformFolder()).thenReturn("platform-folder");
    when(googleDriveService.listBackups("platform-folder")).thenReturn(backups(10));
    doThrow(new IOException("boom")).when(googleDriveService).deleteFile("id-7");

    BackupRetentionService service = newService(7, 7);

    assertThat(service.prunePlatformToLimit()).isEqualTo(2);
    verify(googleDriveService, times(3)).deleteFile(anyString());
  }

  @Test
  void folderParameterisedSweepDeletesOnlyBeyondTheGivenLimit() throws IOException {
    when(googleDriveService.listBackups("some-folder")).thenReturn(backups(5));

    BackupRetentionService service = newService(7, 7);

    assertThat(service.pruneToLimit("some-folder", 2)).isEqualTo(3);
    verify(googleDriveService).deleteFile("id-2");
    verify(googleDriveService, never()).deleteFile("id-1");
  }
}
