# Nightly full-backup job with 7-backup retention

**Date:** 2026-07-22
**Status:** Approved — ready for implementation plan

## Problem

The application can back up to Google Drive today, but only when an admin
manually triggers it from the Data Operations page. There is no scheduled
backup, and nothing prunes old backups, so the Drive folder grows unbounded.

We want an automated nightly backup and a bounded retention policy.

## Goals

1. Run a **full backup including media** every night at **22:00 Europe/London**
   (BST-aware) and upload it to Google Drive.
2. Retain only the **newest 7** backups in the Drive folder; delete older ones.
3. Prune both **once on next deploy** (Mongock baseline) and **after every
   successful nightly backup**, sharing one retention service.
4. Going forward, **all backups are full and self-contained** — media is always
   embedded, with no dedup and no data-only option.

## Non-goals

- Changing where backups are stored (still Google Drive, folder
  `simonrowe-backups`).
- Changing the restore flow. Restore must remain able to open older,
  media-deduped backups still present on Drive.
- Local filesystem retention for `scripts/backup.sh` (that host script is
  separate and out of scope).

## Existing system (context)

- `BackupService.performBackup(boolean includeMedia)` zips all Mongo
  collections + `uploads/` media + Elasticsearch embeddings + a manifest, then
  uploads to Google Drive. It updates `DataOperationsService` progress and
  swallows exceptions (calls `completeOperation` / `failOperation`).
- It currently **dedupes media**: when `uploads/` is byte-identical to a prior
  full backup it skips the media bytes and records `mediaSource` in the manifest
  (plus a `.media-state.json` sidecar on Drive). Restore then fetches media from
  the referenced backup.
- `GoogleDriveService` exposes `findOrCreateFolder()`, `listBackups(folderId)`
  (sorted `createdTime desc`, `.zip` only), and `deleteFile(fileId)`.
- `DataOperationsService.tryStartOperation(type)` is a mutex returning `null`
  when another op is in progress.
- Scheduled work uses Spring `@Scheduled(cron = ...)` with the cron in
  `application.yml` (e.g. `AggregationScheduler`).
- Mongock change-units live in `com.simonrowe.migration.changeunits`
  (`V001`…`V009`), run once at startup, and support Spring bean injection.
- The admin UI (`DataOperationsAdmin.tsx`) has two buttons: "Backup Data Only"
  (`handleBackup(false)`) and "Full Backup (with media)" (`handleBackup(true)`);
  `dataOperationsApi.ts#startBackup(token, includeMedia)` posts to
  `/api/admin/data-operations/backup?includeMedia=...`.

## Design

### Retention semantics

- Count **all `.zip` backups** in the folder toward the limit of 7 (newest by
  `createdTime` wins), regardless of automated vs manual.
- Keep the newest 7, delete the rest. Because backups are now self-contained
  (dedup dropped), there is no cross-backup media dependency to protect — every
  retained backup is independently restorable.
- The `.media-state.json` sidecar is not a `.zip`, so it is never counted or
  deleted; it simply becomes obsolete once dedup is removed.

### New components (`com.simonrowe.dataops`)

**`BackupRetentionService`**
- `pruneToLimit()`:
  - If Drive is not connected → log and return `0`.
  - Resolve `folderId`, `listBackups(folderId)` (already newest-first), keep the
    first `maxBackups` (default 7), `deleteFile()` the rest.
  - Log each deletion. A per-file delete failure is logged and does not abort
    the sweep. Returns the count deleted.
- `maxBackups` injected from `backup.retention.max-backups` (default 7).

**`BackupScheduler`** (`@Component @EnableScheduling`)
- `@Scheduled(cron = "${backup.schedule.cron:0 0 22 * * *}", zone = "${backup.schedule.zone:Europe/London}")`
- `runNightlyBackup()`:
  1. If Drive not connected → log warning, skip.
  2. `operationsService.tryStartOperation(BACKUP)`; if `null` (a manual op is
     running) → log and skip, avoiding collision.
  3. `boolean ok = backupService.performBackup();`
  4. If `ok` → `retentionService.pruneToLimit();`
  5. The whole body is wrapped so an exception can never kill the scheduler
     thread.

### Changes to existing code

**`BackupService`**
- Collapse `performBackup(boolean)` to a single `performBackup()` returning
  `boolean` — `true` after `completeOperation`, `false` after `failOperation`.
- Remove the media-dedup path entirely: drop `computeMediaFingerprint`,
  `MediaState`, `readMediaState`/`writeMediaState`, the `MEDIA_STATE_FILENAME`
  sidecar, and the `mediaSource` manifest field. Media is always walked from
  `uploads/` and embedded.
- Keep `createLocalBackup()` unchanged (already self-contained).

**`DataOperationsController`**
- Drop the `includeMedia` request param; `startBackup` always calls
  `performBackup()`.

**`RestoreService`** — unchanged. It keeps reading `mediaSource` so older
deduped backups still on Drive remain restorable; new backups just won't set it.

**Mongock** — `V010PruneBackupsToRetentionLimit` (order `010`) in
`migration/changeunits`. `@Execution` injects `BackupRetentionService` and calls
`pruneToLimit()` once; safe no-op when Drive is not connected.
`@RollbackExecution` is empty, matching sibling change-units.

**Frontend**
- `DataOperationsAdmin.tsx`: remove the "Backup Data Only" button, keep a single
  "Backup Now (full)" button.
- `dataOperationsApi.ts#startBackup`: drop the `includeMedia` argument and query
  param.

### Config (`application.yml`)

```yaml
backup:
  schedule:
    cron: "0 0 22 * * *"
    zone: "Europe/London"
  retention:
    max-backups: 7
```

## Error handling

- Scheduler never throws to the scheduling thread; all failures are logged.
- A failed backup does **not** trigger a prune ("prune only after success").
- Retention delete failures are logged per file and don't fail the backup.
- Drive-not-connected is a clean skip everywhere (scheduler and Mongock).

## Testing

- `BackupRetentionServiceTest` — keeps newest 7 / deletes the rest; ≤7 deletes
  nothing; Drive-disconnected is a no-op; a `deleteFile` failure doesn't stop the
  sweep.
- `BackupSchedulerTest` — skips when Drive disconnected; skips when an op is
  already in progress; prunes on success; does **not** prune on failure.
- Update existing `BackupService` / controller tests for the removed
  `includeMedia` / dedup paths.
