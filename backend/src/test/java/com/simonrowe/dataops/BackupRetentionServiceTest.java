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
}
