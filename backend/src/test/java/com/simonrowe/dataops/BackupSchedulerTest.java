package com.simonrowe.dataops;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackupSchedulerTest {

  @Mock
  private BackupService backupService;
  @Mock
  private BackupRetentionService retentionService;
  @Mock
  private GoogleDriveService googleDriveService;
  @Mock
  private DataOperationsService operationsService;

  @InjectMocks
  private BackupScheduler scheduler;

  private DataOperation runningOp() {
    return DataOperation.start("op-1", OperationType.BACKUP);
  }

  @Test
  void skipsWhenDriveNotConnected() {
    when(googleDriveService.isConnected()).thenReturn(false);

    scheduler.runNightlyBackup();

    verify(backupService, never()).performBackup();
    verify(retentionService, never()).pruneToLimit();
  }

  @Test
  void skipsWhenAnotherOperationInProgress() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.BACKUP)).thenReturn(null);

    scheduler.runNightlyBackup();

    verify(backupService, never()).performBackup();
    verify(retentionService, never()).pruneToLimit();
  }

  @Test
  void prunesAfterSuccessfulBackup() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.BACKUP)).thenReturn(runningOp());
    when(backupService.performBackup()).thenReturn(true);

    scheduler.runNightlyBackup();

    verify(backupService).performBackup();
    verify(retentionService).pruneToLimit();
  }

  @Test
  void doesNotPruneWhenBackupFails() {
    when(googleDriveService.isConnected()).thenReturn(true);
    when(operationsService.tryStartOperation(OperationType.BACKUP)).thenReturn(runningOp());
    when(backupService.performBackup()).thenReturn(false);

    scheduler.runNightlyBackup();

    verify(backupService).performBackup();
    verify(retentionService, never()).pruneToLimit();
  }
}
