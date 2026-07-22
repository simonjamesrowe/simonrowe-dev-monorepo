# Nightly Backup Job Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a scheduled nightly (22:00 Europe/London) full backup to Google Drive that retains only the newest 7 backups, pruning both after each successful backup and once on next deploy via Mongock.

**Architecture:** A new `BackupScheduler` (`@Scheduled` cron) calls the existing `BackupService.performBackup()`, then a new `BackupRetentionService.pruneToLimit()`. A Mongock change-unit calls the same retention service once at startup. `performBackup` is simplified to always produce a full, self-contained backup (media dedup and the data-only path removed).

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring `@Scheduled`, Mongock, JUnit 5 + Mockito + AssertJ, Google Drive SDK. Backend tests: `cd backend && ../gradlew test`.

---

## File structure

- Create `backend/src/main/java/com/simonrowe/dataops/BackupRetentionService.java` — lists Drive backups, keeps newest N, deletes the rest.
- Create `backend/src/main/java/com/simonrowe/dataops/BackupScheduler.java` — cron trigger orchestrating backup + prune.
- Create `backend/src/main/java/com/simonrowe/migration/changeunits/V010PruneBackupsToRetentionLimit.java` — one-time baseline prune.
- Create `backend/src/test/java/com/simonrowe/dataops/BackupRetentionServiceTest.java`
- Create `backend/src/test/java/com/simonrowe/dataops/BackupSchedulerTest.java`
- Modify `backend/src/main/java/com/simonrowe/dataops/BackupService.java` — `performBackup()` returns `boolean`; remove dedup + data-only.
- Modify `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — drop `includeMedia` param.
- Modify `backend/src/main/resources/application.yml` — add `backup:` block.
- Modify `frontend/src/pages/admin/DataOperationsAdmin.tsx` — single full-backup button.
- Modify `frontend/src/services/dataOperationsApi.ts` — drop `includeMedia` arg.

---

## Task 1: Add backup config to application.yml

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add the `backup` block**

Add this as a new top-level block (place it near the `aggregation:` block at the end of the file):

```yaml
backup:
  schedule:
    cron: "0 0 22 * * *"
    zone: "Europe/London"
  retention:
    max-backups: 7
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "chore: add nightly backup schedule and retention config"
```

---

## Task 2: BackupRetentionService (TDD)

Keeps the newest `maxBackups` Drive backups, deletes older ones. No-op when Drive is not connected. A single `deleteFile` failure must not abort the sweep.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/dataops/BackupRetentionService.java`
- Test: `backend/src/test/java/com/simonrowe/dataops/BackupRetentionServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/simonrowe/dataops/BackupRetentionServiceTest.java`:

```java
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
      // newest first, matching GoogleDriveService.listBackups ordering
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

    // id-7 failed, id-8 and id-9 still deleted
    assertThat(deleted).isEqualTo(2);
    verify(googleDriveService, times(3)).deleteFile(anyString());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ../gradlew test --tests '*BackupRetentionServiceTest*'`
Expected: FAIL — `BackupRetentionService` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/simonrowe/dataops/BackupRetentionService.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ../gradlew test --tests '*BackupRetentionServiceTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/dataops/BackupRetentionService.java \
        backend/src/test/java/com/simonrowe/dataops/BackupRetentionServiceTest.java
git commit -m "feat: add BackupRetentionService keeping newest 7 Drive backups"
```

---

## Task 3: Simplify BackupService to always-full, self-contained backups

Collapse `performBackup(boolean)` to a single `performBackup()` that returns `boolean` (success), always embeds media, and drops the media-dedup machinery. Update the controller call site in the same task so the code compiles.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/dataops/BackupService.java`
- Modify: `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java:66-74`

- [ ] **Step 1: Replace the two `performBackup` methods**

In `BackupService.java`, replace the existing `performBackup()` and `performBackup(final boolean includeMedia)` methods (the block from `public void performBackup() {` through the end of `performBackup(final boolean includeMedia)`, i.e. through its closing brace at the `finally`) with this single method:

```java
  /**
   * Runs a full, self-contained backup (all collections + media + embeddings)
   * and uploads it to Google Drive.
   *
   * @return {@code true} if the backup completed and uploaded successfully
   */
  public boolean performBackup() {
    Path tempFile = null;
    try {
      operationsService.updateProgress("Exporting database collections...", 10);
      String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
      String fileName = "backup-" + timestamp + ".zip";
      tempFile = Files.createTempFile("backup-", ".zip");

      Map<String, Integer> collectionCounts = new LinkedHashMap<>();
      int mediaFileCount = 0;

      String folderId = googleDriveService.findOrCreateFolder();

      try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tempFile));
           ZipOutputStream zos = new ZipOutputStream(fos)) {

        int progress = 10;
        int progressPerCollection = 50 / BACKUP_COLLECTIONS.size();

        for (String collectionName : BACKUP_COLLECTIONS) {
          operationsService.updateProgress(
              "Exporting collection: " + collectionName, progress);
          MongoDatabase db = mongoClient.getDatabase(databaseName);
          MongoCollection<RawBsonDocument> collection =
              db.getCollection(collectionName, RawBsonDocument.class);
          List<RawBsonDocument> docs = collection.find().into(new ArrayList<>());
          collectionCounts.put(collectionName, docs.size());

          StringBuilder sb = new StringBuilder();
          sb.append("[\n");
          for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
              sb.append(",\n");
            }
            sb.append(docs.get(i).toJson(JSON_SETTINGS));
          }
          sb.append("\n]");

          zos.putNextEntry(new ZipEntry("collections/" + collectionName + ".json"));
          zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();

          progress += progressPerCollection;
        }

        operationsService.updateProgress("Adding media files...", 60);
        Path uploadsDir = Path.of(uploadsPath);
        if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
          List<Path> mediaFiles = Files.walk(uploadsDir)
              .filter(Files::isRegularFile)
              .toList();
          mediaFileCount = mediaFiles.size();
          for (Path mediaFile : mediaFiles) {
            String entryPath = "uploads/" + uploadsDir.relativize(mediaFile);
            zos.putNextEntry(new ZipEntry(entryPath));
            Files.copy(mediaFile, zos);
            zos.closeEntry();
          }
        }

        operationsService.updateProgress("Exporting vector embeddings...", 70);
        try {
          String embeddingsJson = esBackupService.exportEmbeddings();
          zos.putNextEntry(new ZipEntry("embeddings/content-embeddings.json"));
          zos.write(embeddingsJson.getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();
        } catch (Exception ex) {
          LOG.warn("Failed to export embeddings, skipping: {}", ex.getMessage());
        }

        operationsService.updateProgress("Writing manifest...", 75);
        String manifest = buildManifest(timestamp, collectionCounts, mediaFileCount);
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(manifest.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      operationsService.updateProgress("Uploading to Google Drive...", 80);
      long fileSize = Files.size(tempFile);
      try (var is = Files.newInputStream(tempFile)) {
        googleDriveService.uploadFile(folderId, fileName, is, fileSize,
            (sent, total) -> {
              int percent = total > 0 ? 80 + (int) ((sent * 15L) / total) : 80;
              if (percent > 95) {
                percent = 95;
              }
              operationsService.updateProgress(
                  String.format("Uploading to Google Drive... %d%% (%s / %s)",
                      total > 0 ? (int) ((sent * 100L) / total) : 0,
                      BackupMetadata.formatFileSize(sent),
                      BackupMetadata.formatFileSize(total)),
                  percent);
            });
      }

      int totalDocs = collectionCounts.values().stream()
          .mapToInt(Integer::intValue).sum();
      String summary = String.format(
          "%d collections, %d documents, %d media files backed up (%s)",
          collectionCounts.size(), totalDocs, mediaFileCount,
          BackupMetadata.formatFileSize(fileSize));
      operationsService.completeOperation(summary);
      return true;

    } catch (Exception ex) {
      LOG.error("Backup failed", ex);
      operationsService.failOperation("Backup failed: " + ex.getMessage());
      return false;
    } finally {
      deleteTempFile(tempFile);
    }
  }
```

- [ ] **Step 2: Replace `buildManifest` to drop `mediaSource`**

Replace the existing `buildManifest(...)` method with this 3-arg version:

```java
  private String buildManifest(final String timestamp,
      final Map<String, Integer> collectionCounts,
      final int mediaFileCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"version\": \"1.1\",\n");
    sb.append("  \"createdAt\": \"").append(Instant.now()).append("\",\n");
    sb.append("  \"databaseName\": \"simonrowe\",\n");
    sb.append("  \"collectionCount\": ").append(collectionCounts.size()).append(",\n");
    sb.append("  \"mediaFileCount\": ").append(mediaFileCount).append(",\n");
    sb.append("  \"collections\": {\n");
    int i = 0;
    for (Map.Entry<String, Integer> entry : collectionCounts.entrySet()) {
      sb.append("    \"").append(entry.getKey()).append("\": ").append(entry.getValue());
      if (i < collectionCounts.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("  }\n");
    sb.append("}\n");
    return sb.toString();
  }
```

- [ ] **Step 3: Delete the now-unused dedup members**

Delete these from `BackupService.java` (they are no longer referenced):
- The field/constant `MEDIA_STATE_FILENAME` (the `static final String MEDIA_STATE_FILENAME = ...;` and its Javadoc).
- Method `computeMediaFingerprint()`.
- Method `readMediaState(...)`.
- Method `writeMediaState(...)`.
- Methods `extractJsonString(...)` and `extractJsonNumber(...)`.
- Method `jsonEscape(...)`.
- The nested `record MediaState(...) {}`.

Keep `createLocalBackup()`, `deleteTempFile()`, and all imports still in use. After deleting, remove any now-unused imports (e.g. `MessageDigest`, `NoSuchAlgorithmException`, `HexFormat`, `org.springframework.lang.Nullable`) — let the compiler/Checkstyle guide you.

- [ ] **Step 4: Update the controller call site**

In `DataOperationsController.java`, replace the `startBackup` method (lines ~66-74) with:

```java
  @PostMapping("/backup")
  public ResponseEntity<DataOperation> startBackup() {
    requireDriveConnected();
    DataOperation operation = requireNoOperationInProgress(OperationType.BACKUP);
    CompletableFuture.runAsync(() -> backupService.performBackup());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(operation);
  }
```

Remove the now-unused `RequestParam` import if nothing else uses it.

- [ ] **Step 5: Compile and run the full backend test suite**

Run: `cd backend && ../gradlew compileJava compileTestJava test`
Expected: BUILD SUCCESSFUL. If any pre-existing test referenced `performBackup(boolean)` or `includeMedia`, update it to the no-arg form.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/simonrowe/dataops/BackupService.java \
        backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java
git commit -m "refactor: always-full self-contained backups, drop media dedup and data-only"
```

---

## Task 4: BackupScheduler (TDD)

Cron-triggered orchestration: skip if Drive down or an op is in progress; run the backup; prune only on success; never let an exception escape.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/dataops/BackupScheduler.java`
- Test: `backend/src/test/java/com/simonrowe/dataops/BackupSchedulerTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/simonrowe/dataops/BackupSchedulerTest.java`:

```java
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
```

Note: `DataOperation` is a record; use its `DataOperation.start(id, type)` factory (as above) to build a non-null instance — the scheduler only null-checks the returned operation.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ../gradlew test --tests '*BackupSchedulerTest*'`
Expected: FAIL — `BackupScheduler` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/simonrowe/dataops/BackupScheduler.java`:

```java
package com.simonrowe.dataops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs a full backup every night and prunes the Drive folder to the retention
 * limit afterwards.
 */
@Component
@EnableScheduling
public class BackupScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupService backupService;
  private final BackupRetentionService retentionService;
  private final GoogleDriveService googleDriveService;
  private final DataOperationsService operationsService;

  public BackupScheduler(
      final BackupService backupService,
      final BackupRetentionService retentionService,
      final GoogleDriveService googleDriveService,
      final DataOperationsService operationsService) {
    this.backupService = backupService;
    this.retentionService = retentionService;
    this.googleDriveService = googleDriveService;
    this.operationsService = operationsService;
  }

  @Scheduled(
      cron = "${backup.schedule.cron:0 0 22 * * *}",
      zone = "${backup.schedule.zone:Europe/London}")
  public void runNightlyBackup() {
    try {
      if (!googleDriveService.isConnected()) {
        LOG.warn("Nightly backup skipped: Google Drive is not connected");
        return;
      }
      if (operationsService.tryStartOperation(OperationType.BACKUP) == null) {
        LOG.warn("Nightly backup skipped: another data operation is in progress");
        return;
      }
      LOG.info("Nightly backup starting");
      boolean ok = backupService.performBackup();
      if (ok) {
        retentionService.pruneToLimit();
      } else {
        LOG.error("Nightly backup failed; skipping retention prune");
      }
    } catch (Exception ex) {
      LOG.error("Nightly backup job errored", ex);
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ../gradlew test --tests '*BackupSchedulerTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/dataops/BackupScheduler.java \
        backend/src/test/java/com/simonrowe/dataops/BackupSchedulerTest.java
git commit -m "feat: add nightly BackupScheduler (22:00 Europe/London) with post-backup prune"
```

---

## Task 5: Mongock one-time baseline prune

A change-unit that runs once on next deploy to trim any existing backlog to the retention limit via `BackupRetentionService`.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/migration/changeunits/V010PruneBackupsToRetentionLimit.java`

- [ ] **Step 1: Write the change-unit**

Create `backend/src/main/java/com/simonrowe/migration/changeunits/V010PruneBackupsToRetentionLimit.java`:

```java
package com.simonrowe.migration.changeunits;

import com.simonrowe.dataops.BackupRetentionService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time baseline prune of the Google Drive backups folder down to the
 * retention limit. Ongoing pruning happens after each nightly backup; this
 * change-unit just trims any pre-existing backlog on first deploy. Safe no-op
 * when Drive is not connected.
 */
@ChangeUnit(id = "prune-backups-to-retention-limit", order = "010", author = "simonrowe")
public class V010PruneBackupsToRetentionLimit {

  private static final Logger log =
      LoggerFactory.getLogger(V010PruneBackupsToRetentionLimit.class);

  @Execution
  public void execution(final BackupRetentionService retentionService) {
    int deleted = retentionService.pruneToLimit();
    log.info("Baseline backup prune removed {} over-limit backups", deleted);
  }

  @RollbackExecution
  public void rollback() {
  }
}
```

- [ ] **Step 2: Compile**

Run: `cd backend && ../gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/simonrowe/migration/changeunits/V010PruneBackupsToRetentionLimit.java
git commit -m "feat: add Mongock V010 one-time backup retention prune"
```

---

## Task 6: Frontend — single full-backup button

Remove the "Backup Data Only" option so only full backups can be triggered.

**Files:**
- Modify: `frontend/src/pages/admin/DataOperationsAdmin.tsx`
- Modify: `frontend/src/services/dataOperationsApi.ts`

- [ ] **Step 1: Simplify the API helper**

In `frontend/src/services/dataOperationsApi.ts`, change `startBackup` to drop the `includeMedia` parameter and query string. New signature/body:

```ts
export async function startBackup(
  getAccessToken: () => Promise<string>,
): Promise<DataOperation> {
  const token = await getAccessToken()
  const url = `${DATA_OPS_URL}/backup`
  // ...keep the existing authFetch POST call below, unchanged except the url...
}
```

Keep the rest of the function body (the `authFetch(url, token, { method: 'POST' })` call and response handling) exactly as it was — only the parameter and the `url` line change.

- [ ] **Step 2: Simplify the admin page**

In `frontend/src/pages/admin/DataOperationsAdmin.tsx`:
- Change `handleBackup` to take no argument and call `startBackup(getAccessToken)`:

```tsx
  const handleBackup = async () => {
    try {
      // ...keep existing pre-call setup lines...
      await startBackup(getAccessToken)
      // ...keep existing post-call lines...
    } // ...keep existing catch/finally...
  }
```

- Remove the "Backup Data Only" `<button>` (the one with `onClick={() => handleBackup(false)}`).
- Change the remaining "Full Backup (with media)" button's handler to `onClick={() => handleBackup()}` and relabel it to `Backup Now (full)`.

- [ ] **Step 3: Typecheck / build the frontend**

Run: `cd frontend && npm run build`
Expected: build succeeds with no TypeScript errors. (If the project exposes `npm run typecheck`/`tsc`, run that too.)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/admin/DataOperationsAdmin.tsx \
        frontend/src/services/dataOperationsApi.ts
git commit -m "feat: admin backup UI offers a single full backup action"
```

---

## Task 7: Full verification

- [ ] **Step 1: Backend build + tests**

Run: `cd backend && ../gradlew clean build`
Expected: BUILD SUCCESSFUL (compiles, Checkstyle passes, all tests green).

- [ ] **Step 2: Frontend build + tests**

Run: `cd frontend && npm run build && npm test`
Expected: build succeeds; vitest passes.

- [ ] **Step 3: Confirm nothing still references removed APIs**

Run:
```bash
cd /Users/simonrowe/conductor/workspaces/simonrowe-dev-monorepo/sucre
grep -rn "includeMedia\|performBackup(true\|performBackup(false\|MEDIA_STATE_FILENAME\|computeMediaFingerprint" backend/src frontend/src || echo "clean"
```
Expected: `clean` (RestoreService keeps reading the `mediaSource` manifest field — that is expected and must remain).
```
