package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mirrors {@link BackupSchedulerTest}. The platform scheduler has to behave
 * identically to the application one, because the operator reads both from the
 * same logs and reasons about them the same way.
 */
@ExtendWith(MockitoExtension.class)
class PlatformBackupSchedulerTest {

  @Mock
  private PlatformBackupService platformBackupService;
  @Mock
  private BackupRetentionService retentionService;
  @Mock
  private GoogleDriveService googleDriveService;
  @Mock
  private DataOperationsService operationsService;

  @InjectMocks
  private PlatformBackupScheduler scheduler;

  private DataOperation runningOp() {
    return DataOperation.start("op-1", OperationType.PLATFORM_BACKUP);
  }

  @Test
  void skipsWhenDriveNotConnected() {
    when(googleDriveService.isConnected()).thenReturn(false);

    scheduler.runNightlyPlatformBackup();

    verify(platformBackupService, never()).performBackup();
    verify(retentionService, never()).prunePlatformToLimit();
  }

  /**
   * The mutex is global, so an overrunning application backup costs the platform
   * backup its whole night. That is accepted — it logs, skips without retrying,
   * and self-corrects the next night — but it must not be silent, and it must not
   * leave a partial archive behind.
   */
  @Test
  void skipsWhenAnotherOperationInProgress() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP)).thenReturn(null);

    scheduler.runNightlyPlatformBackup();

    verify(platformBackupService, never()).performBackup();
    verify(retentionService, never()).prunePlatformToLimit();
  }

  @Test
  void prunesAfterSuccessfulBackup() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP))
        .thenReturn(runningOp());
    when(platformBackupService.performBackup()).thenReturn(true);

    scheduler.runNightlyPlatformBackup();

    verify(retentionService).prunePlatformToLimit();
  }

  /**
   * Pruning after a failed backup would delete a good older archive to make room
   * for one that was never uploaded.
   */
  @Test
  void doesNotPruneAfterFailedBackup() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP))
        .thenReturn(runningOp());
    when(platformBackupService.performBackup()).thenReturn(false);

    scheduler.runNightlyPlatformBackup();

    verify(retentionService, never()).prunePlatformToLimit();
  }

  /**
   * A prune failure after a successful upload is a prune failure, not data loss.
   * Reporting it as a backup failure sends the operator hunting for data that is
   * safely stored.
   */
  @Test
  void pruneFailureDoesNotFailTheBackup() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP))
        .thenReturn(runningOp());
    when(platformBackupService.performBackup()).thenReturn(true);
    doThrow(new IllegalStateException("drive unhappy"))
        .when(retentionService).prunePlatformToLimit();

    assertThatCode(() -> scheduler.runNightlyPlatformBackup()).doesNotThrowAnyException();
  }

  /**
   * Nothing may reach the scheduling thread: an escaping exception is how a
   * nightly job stops running and nobody notices for weeks.
   */
  @Test
  void neverThrowsToTheSchedulerThread() {
    when(googleDriveService.isConnected()).thenThrow(new RuntimeException("kaboom"));

    assertThatCode(() -> scheduler.runNightlyPlatformBackup()).doesNotThrowAnyException();
  }

  @Test
  void prunesOnlyThePlatformFolder() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.PLATFORM_BACKUP))
        .thenReturn(runningOp());
    when(platformBackupService.performBackup()).thenReturn(true);

    scheduler.runNightlyPlatformBackup();

    verify(retentionService).prunePlatformToLimit();
    verify(retentionService, never()).pruneToLimit();
  }
}
