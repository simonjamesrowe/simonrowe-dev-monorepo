# Data Model: Admin Data Operations

**Branch**: `011-admin-data-ops` | **Date**: 2026-03-28

## Entities

### DataOperation (in-memory only, not persisted)

Tracks the currently running data operation. Held in an `AtomicReference` in the service layer.

| Field | Type | Description |
|-------|------|-------------|
| id | String (UUID) | Unique operation identifier |
| type | Enum: BACKUP, RESTORE, CLEAR, REBUILD_INDEX | The operation being performed |
| status | Enum: IN_PROGRESS, COMPLETED, FAILED | Current state |
| startedAt | Instant | When the operation started |
| completedAt | Instant (nullable) | When the operation finished |
| progressMessage | String | Human-readable current step description |
| progressPercent | Integer (0-100) | Estimated completion percentage |
| errorMessage | String (nullable) | Error details if status is FAILED |
| resultSummary | String (nullable) | Summary on completion (e.g., "9 collections, 47 media files backed up") |

**State transitions**:
- `→ IN_PROGRESS` (operation started)
- `IN_PROGRESS → COMPLETED` (operation succeeded)
- `IN_PROGRESS → FAILED` (operation encountered an error)

**Not persisted**: This entity exists only in-memory for the duration of an operation. The system is single-admin, single-instance — no need for durable operation tracking.

### BackupMetadata (derived from Google Drive file metadata)

Not a separate entity — constructed from the Google Drive file listing when displaying available backups for restore.

| Field | Type | Description |
|-------|------|-------------|
| fileId | String | Google Drive file ID |
| fileName | String | Archive filename (e.g., `backup-20260328-143000.zip`) |
| createdAt | Instant | When the backup was created (from Drive file metadata) |
| fileSize | Long | Size in bytes |

### GoogleDriveConnectionStatus (derived, not persisted)

Checked on-demand by testing the service account credentials against the Drive API.

| Field | Type | Description |
|-------|------|-------------|
| connected | Boolean | Whether credentials are valid and Drive API is reachable |
| folderName | String | Fixed: "simonrowe-backups" |
| errorMessage | String (nullable) | Connection error details if not connected |

## Backup Archive Structure

The ZIP archive contains all MongoDB collections as Extended JSON and all uploaded media files:

```
backup-YYYYMMDD-HHMMSS.zip
├── collections/
│   ├── blogs.json           # Extended JSON array
│   ├── tags.json
│   ├── skills.json
│   ├── skill_groups.json
│   ├── jobs.json
│   ├── profiles.json
│   ├── social_medias.json
│   ├── tourSteps.json
│   └── media_assets.json
├── uploads/                  # Media files (preserves directory structure)
│   ├── image1.jpg
│   ├── image2.png
│   └── ...
└── manifest.json             # Archive metadata
```

### manifest.json

| Field | Type | Description |
|-------|------|-------------|
| version | String | Archive format version (e.g., "1.0") |
| createdAt | String (ISO 8601) | Timestamp of backup creation |
| databaseName | String | "simonrowe" |
| collectionCount | Integer | Number of collections in archive |
| mediaFileCount | Integer | Number of files in uploads/ |
| collections | Map<String, Integer> | Collection name → document count |

## Existing Collections (read/write during operations)

These are the existing MongoDB collections that will be exported/imported/cleared. No schema changes are required.

| Collection | Document Entity | DBRef Dependencies |
|------------|----------------|-------------------|
| blogs | Blog | tags, skills (via @DBRef) |
| tags | Tag | None |
| skills | Skill | None |
| skill_groups | SkillGroup | skills (via @DBRef) |
| jobs | Job | skills (via @DBRef) |
| profiles | Profile | None |
| social_medias | SocialMedia | None |
| tourSteps | TourStep | None |
| media_assets | MediaAsset | None |

**Import order for restore** (respects DBRef dependencies):
1. tags, skills, profiles, social_medias, tourSteps, media_assets (no dependencies)
2. skill_groups (depends on skills)
3. jobs (depends on skills)
4. blogs (depends on tags, skills)
