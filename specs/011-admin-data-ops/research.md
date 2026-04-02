# Research: Admin Data Operations

**Branch**: `011-admin-data-ops` | **Date**: 2026-03-28

## R1: Google Drive API Java Client

**Decision**: Use `com.google.apis:google-api-services-drive` v3 with `com.google.auth:google-auth-library-oauth2-http` for service account authentication.

**Rationale**: This is the official Google-maintained Java client for Drive API v3. It integrates with `GoogleCredentials` for service account auth and supports all required operations (folder creation, file upload, file listing, file download, file deletion).

**Alternatives considered**:
- `com.google.cloud:google-cloud-storage` (Cloud Storage, not Drive) — has first-class GraalVM support but is a different product; user specifically requested Google Drive.
- REST calls via Spring `WebClient` — adds maintenance burden for OAuth2 token management and multipart upload handling.

**GraalVM native image concern**: The Google API client libraries use reflection for JSON deserialization (`GenericJson`, `@Key` annotations). Requires running the GraalVM tracing agent during tests to generate `reflect-config.json`, `resource-config.json`, and `proxy-config.json`. These must be committed and maintained when library versions change.

**Versions**:
- `com.google.apis:google-api-services-drive:v3-rev20250511-2.0.0`
- `com.google.api-client:google-api-client:2.8.0`
- `com.google.auth:google-auth-library-oauth2-http:1.35.0`

## R2: Service Account Authentication Pattern

**Decision**: Decode the base64 `GOOGLE_DRIVE_CREDENTIALS` environment variable to bytes, wrap in a `ByteArrayInputStream`, and pass to `ServiceAccountCredentials.fromStream()`. Scope to `DriveScopes.DRIVE_FILE`.

**Rationale**: This is the standard Google Auth Library pattern. Using `DRIVE_FILE` scope (instead of full `DRIVE`) limits access to files created by the application, which is the least-privilege option.

**Alternatives considered**:
- File path to JSON key — rejected in clarification (user chose base64 env var for simpler deployment).
- Application Default Credentials (ADC) — not suitable since the key is provided as an env var, not via `GOOGLE_APPLICATION_CREDENTIALS` file path convention.

## R3: MongoDB Backup/Restore Approach

**Decision**: Use `MongoTemplate` (Spring Data) to iterate all collections and serialize documents as Extended JSON (`JsonMode.EXTENDED`). On restore, parse Extended JSON back to `Document` objects and insert with `MongoTemplate`.

**Rationale**: Eliminates the external `mongodump`/`mongorestore` binary dependency. Works cleanly in GraalVM native image containers (minimal base images). Extended JSON preserves BSON types (`$oid`, `$date`, `$ref`) for lossless round-trip, which is critical for `@DBRef` fields used by blogs (tags, skills references).

**Alternatives considered**:
- Shelling out to `mongodump`/`mongorestore` via `ProcessBuilder` — rejected because native image containers use minimal base images without MongoDB tools installed.
- Relaxed JSON mode — rejected because it loses type fidelity for ObjectId, dates, and DBRef fields.

**Collections to back up** (9 total): `blogs`, `tags`, `skills`, `skill_groups`, `jobs`, `profiles`, `social_medias`, `tourSteps`, `media_assets`.

## R4: Archive Format

**Decision**: Use ZIP format via `java.util.zip.ZipOutputStream` (JDK built-in).

**Rationale**: Zero additional dependencies, full GraalVM native image compatibility, and easy to inspect/debug. Archive structure:
```
backup-20260328-143000.zip
├── collections/
│   ├── blogs.json
│   ├── tags.json
│   ├── skills.json
│   ├── skill_groups.json
│   ├── jobs.json
│   ├── profiles.json
│   ├── social_medias.json
│   ├── tourSteps.json
│   └── media_assets.json
└── uploads/
    ├── image1.jpg
    ├── image2.png
    └── ...
```

**Alternatives considered**:
- tar.gz via Apache Commons Compress — adds a dependency for marginal compression benefit.
- Individual files (no archive) — would require multiple Google Drive uploads and complicate restore.

## R5: Progress Reporting Mechanism

**Decision**: Use Server-Sent Events (SSE) via Spring MVC's `SseEmitter` for real-time operation progress from backend to frontend.

**Rationale**: The project already uses WebSocket/STOMP for chat, but SSE is simpler for unidirectional server-to-client progress updates. No additional dependencies required — `SseEmitter` is built into Spring MVC. Frontend uses native `EventSource` API.

**Alternatives considered**:
- WebSocket/STOMP (existing in project) — overkill for unidirectional progress; adds unnecessary complexity.
- Polling — adds latency and unnecessary requests; poor UX for long-running operations.

## R6: Concurrency Control

**Decision**: Use an `AtomicReference<DataOperation>` in the service layer to track the currently running operation. All operation endpoints check this before starting and reject with HTTP 409 Conflict if an operation is in progress.

**Rationale**: Single-admin, single-instance deployment. In-memory atomic state is the simplest correct solution. No database-level locking needed.

**Alternatives considered**:
- Database-level distributed lock (MongoDB) — over-engineered for single-instance, single-admin scenario.
- Spring `@Async` with `Future` tracking — similar but less explicit about mutual exclusion.

## R7: Existing Codebase Integration Points

**Decision**: Leverage existing codebase components directly.

**Key integration points identified**:
- `IndexService.fullSyncSiteIndex()` and `fullSyncBlogIndex()` — already implement full reindexing. Call both for rebuild.
- `AdminLayout.tsx` sidebar — add "Data Operations" after "Media" nav item.
- Frontend routes in `App.tsx` — add `/admin/data-operations` route.
- Backend admin controller pattern — follow existing `AdminBlogController`, `AdminTagController` etc. at `/api/admin/data-operations`.
- `application.yml` — add `google.drive.credentials` and `uploads.path` already exists.
- Database name: `simonrowe`, 9 collections total.
