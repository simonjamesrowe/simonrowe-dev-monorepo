# Feature Specification: Admin Data Operations

**Feature Branch**: `011-admin-data-ops`
**Created**: 2026-03-28
**Status**: Draft
**Input**: User description: "Build admin panel features to: 1. Clear all data, 2. Backup data to Google Drive, 3. Restore from Google Drive, 4. Rebuild the Elasticsearch index. Also produce a setup guide for Google Drive authentication."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Backup Data to Google Drive (Priority: P1)

As an admin, I want to back up all application data (database collections and uploaded media files) to Google Drive so that I have a secure, off-site copy of my data that I can restore from if needed.

**Why this priority**: Data backup is the most critical operation — without a reliable backup, all other data operations (clear, restore) carry unacceptable risk. Backup must work first before restore can be tested.

**Independent Test**: Can be fully tested by triggering a backup from the admin panel and verifying that a complete backup archive appears in the configured Google Drive folder containing all database collections and media files.

**Acceptance Scenarios**:

1. **Given** the admin is on the Data Operations page and Google Drive is connected, **When** they click "Backup to Google Drive", **Then** the system creates an archive of all database collections and uploaded media files and uploads it to the configured Google Drive folder.
2. **Given** a backup is in progress, **When** the admin views the Data Operations page, **Then** they see a progress indicator showing the current status of the backup.
3. **Given** the backup completes successfully, **When** the admin views the page, **Then** they see a success confirmation with the backup timestamp and file size.
4. **Given** the Google Drive connection is not configured or has expired, **When** the admin attempts a backup, **Then** they see a clear error message directing them to configure Google Drive credentials.
5. **Given** a backup fails mid-way (e.g., network error), **When** the failure occurs, **Then** the admin sees an error message with details, and no partial backup is left in Google Drive.

---

### User Story 2 - Restore Data from Google Drive (Priority: P2)

As an admin, I want to restore application data from a previous Google Drive backup so that I can recover from data loss or revert to a known good state.

**Why this priority**: Restore is the complement to backup and is essential for disaster recovery. It depends on backup existing first, making it the natural second priority.

**Independent Test**: Can be fully tested by selecting a previously created backup from Google Drive and restoring it, then verifying all database data and media files match the backup contents.

**Acceptance Scenarios**:

1. **Given** the admin is on the Data Operations page with Google Drive connected, **When** they click "Restore from Google Drive", **Then** they see a list of available backups with timestamps and file sizes.
2. **Given** the admin selects a backup to restore, **When** they confirm the restore action, **Then** the system replaces all current database data and media files with the backup contents.
3. **Given** a restore is in progress, **When** the admin views the page, **Then** they see a progress indicator showing restore status.
4. **Given** the restore completes successfully, **When** the admin views the page, **Then** they see a success confirmation and the search index is automatically rebuilt to reflect the restored data.
5. **Given** the admin initiates a restore, **When** the confirmation dialog appears, **Then** it warns that all current data will be replaced and requires explicit confirmation.
6. **Given** a restore fails mid-way, **When** the failure occurs, **Then** the system shows an error message detailing what succeeded and what failed, and the admin can use the auto-created pre-restore backup to recover manually.

---

### User Story 3 - Clear All Data (Priority: P3)

As an admin, I want to clear all application data so that I can start fresh, typically for development or testing purposes.

**Why this priority**: Clearing data is a destructive operation that is less frequently needed than backup/restore. Having backup in place first provides a safety net before clearing.

**Independent Test**: Can be fully tested by clicking "Clear All Data", confirming the action, and verifying all database collections are emptied, media files are removed, and search indices are cleared.

**Acceptance Scenarios**:

1. **Given** the admin is on the Data Operations page, **When** they click "Clear All Data", **Then** a confirmation dialog appears warning that all data will be permanently deleted.
2. **Given** the confirmation dialog is shown, **When** the admin types a confirmation phrase (e.g., "DELETE ALL DATA") and confirms, **Then** all database collections are emptied, all uploaded media files are removed, and search indices are cleared.
3. **Given** the clear operation completes, **When** the admin views the page, **Then** they see a success confirmation.
4. **Given** the admin dismisses the confirmation dialog without confirming, **When** they return to the page, **Then** no data has been modified.

---

### User Story 4 - Rebuild Search Index (Priority: P4)

As an admin, I want to rebuild the search index on demand so that I can fix search inconsistencies without needing to restart the application or run scripts manually.

**Why this priority**: Index rebuilding is a maintenance utility. The existing system already has scheduled sync and supports full reindexing — this story exposes that capability through the admin UI for convenience.

**Independent Test**: Can be fully tested by clicking "Rebuild Search Index", waiting for completion, and verifying that search results reflect current database data accurately.

**Acceptance Scenarios**:

1. **Given** the admin is on the Data Operations page, **When** they click "Rebuild Search Index", **Then** the system deletes and recreates the search indices and re-indexes all content from the database.
2. **Given** a rebuild is in progress, **When** the admin views the page, **Then** they see a progress indicator.
3. **Given** the rebuild completes successfully, **When** the admin views the page, **Then** they see a success confirmation with the number of documents indexed.
4. **Given** the search service is unavailable, **When** the admin attempts a rebuild, **Then** they see a clear error message indicating the search service is down.

---

### User Story 5 - Google Drive Authentication Setup Guide (Priority: P5)

As an admin setting up the application for the first time, I want a clear setup guide for configuring Google Drive authentication so that I can enable the backup and restore features.

**Why this priority**: This is documentation that supports the backup/restore features. It is needed before first use but is not application functionality itself.

**Independent Test**: Can be tested by following the guide from scratch and confirming that the application can successfully connect to Google Drive and perform a test backup.

**Acceptance Scenarios**:

1. **Given** an admin is setting up Google Drive integration for the first time, **When** they follow the setup guide, **Then** they can create a Google Cloud project, enable the Drive API, create service account credentials, and configure the application with the credentials.
2. **Given** the guide is complete, **When** the admin configures the application with the credentials, **Then** the Data Operations page shows a "Connected" status for Google Drive.

---

### Edge Cases

- What happens when the admin triggers a backup while another operation is already in progress? The system prevents concurrent operations and shows a message that an operation is already running.
- What happens when Google Drive storage quota is exceeded during backup? The system detects the quota error and reports it clearly to the admin.
- What happens when the backup archive is corrupted or incomplete during restore? The system validates the archive integrity before applying the restore and rejects invalid backups.
- What happens when a clear or restore operation is triggered while the public site has active users? The operation proceeds (admin accepts this risk via confirmation), but the site may show temporary inconsistencies.
- What happens when the network connection drops during a Google Drive upload/download? The system detects the failure, cleans up any partial state, and reports the error.
- What happens when the media files in a backup reference paths that conflict with existing files? The restore operation replaces all existing media files entirely, so conflicts do not arise.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a dedicated "Data Operations" section in the admin panel accessible from the admin sidebar navigation.
- **FR-002**: System MUST allow the admin to initiate a full backup of all database collections and uploaded media files to a fixed Google Drive folder named "simonrowe-backups" (auto-created if it does not exist).
- **FR-003**: System MUST display a list of available backups from Google Drive, showing backup timestamp and file size, when the admin initiates a restore.
- **FR-004**: System MUST allow the admin to restore all application data from a selected Google Drive backup, replacing current database data and media files.
- **FR-014**: System MUST automatically create a local backup of current data before starting a restore operation, so the admin can recover if the restore fails partway through.
- **FR-005**: System MUST automatically rebuild the search index after a successful data restore.
- **FR-006**: System MUST allow the admin to clear all local application data (database collections, uploaded media files, and search indices) with a typed confirmation safeguard. Google Drive backups are explicitly excluded from the clear operation.
- **FR-007**: System MUST allow the admin to trigger a manual rebuild of the search index independently of other operations.
- **FR-008**: System MUST show real-time progress and status feedback for all data operations (backup, restore, clear, rebuild).
- **FR-009**: System MUST prevent concurrent data operations — only one operation (backup, restore, clear, or rebuild) may run at a time.
- **FR-010**: System MUST display the Google Drive connection status on the Data Operations page.
- **FR-011**: System MUST handle operation failures gracefully, displaying clear error messages and cleaning up partial state where applicable.
- **FR-012**: System MUST require explicit confirmation before destructive operations (clear all data, restore from backup).
- **FR-013**: System MUST include a setup guide document for configuring Google Drive authentication (Google Cloud project, Drive API, service account, application configuration).

### Key Entities

- **Backup Archive**: A compressed archive containing exported database collection data and uploaded media files, stored in Google Drive. Key attributes: timestamp, file size, collection count, media file count.
- **Data Operation**: A trackable unit of work (backup, restore, clear, or rebuild) with a type, status (pending, in progress, completed, failed), start time, and optional error details.
- **Google Drive Connection**: The configuration and current status of the Google Drive integration, including authentication state. Uses a fixed folder named "simonrowe-backups" in the service account's Drive root.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Admin can complete a full backup of all data to Google Drive within 5 minutes for a dataset under 500MB.
- **SC-002**: Admin can restore from a backup and have the application fully operational (including search) within 5 minutes for a dataset under 500MB.
- **SC-003**: Admin can clear all data and have a clean application state within 30 seconds.
- **SC-004**: Admin can rebuild the search index within 2 minutes for up to 1,000 documents.
- **SC-005**: All four data operations are accessible and completable from the admin panel without any command-line or script access required.
- **SC-006**: A new admin can follow the Google Drive setup guide and have a working connection within 30 minutes with no prior Google Cloud experience.
- **SC-007**: Failed operations display actionable error messages within 5 seconds of failure detection.

## Clarifications

### Session 2026-03-28

- Q: What restore failure strategy should be used — true atomicity, best-effort with safety net, or best-effort only? → A: Best-effort with safety net — auto-create a local backup before restore; on failure, report what succeeded and offer manual recovery guidance.
- Q: Should "Clear All Data" also delete Google Drive backups, or only local application data? → A: Local only — clear database, media files, and search indices; Google Drive backups are untouched.
- Q: How should Google Drive service account credentials be provided to the application? → A: Environment variable containing the base64-encoded JSON key content directly (e.g., `GOOGLE_DRIVE_CREDENTIALS`).
- Q: Should the system manage backup retention in Google Drive automatically? → A: No automatic retention — all backups kept indefinitely; admin manages cleanup manually via Google Drive.
- Q: How should the Google Drive target folder be determined? → A: Fixed folder name — system always creates/uses a folder named "simonrowe-backups" in the service account's Drive root.

## Assumptions

- Google Drive integration will use a **service account** approach (server-to-server), not OAuth2 user consent flow. This avoids token refresh complexity and is appropriate since only the backend needs access. The service account JSON key is provided as a base64-encoded environment variable (e.g., `GOOGLE_DRIVE_CREDENTIALS`).
- The backup includes all database collections and the uploaded media directory. Application configuration and environment variables are not included in backups.
- The admin panel already has authentication in place, so all data operations endpoints are protected by the existing auth mechanism.
- Only one admin user is expected to perform data operations at a time; multi-admin concurrency for these operations is not required.
- The Google Drive setup guide will be a markdown document included in the project repository, not an in-app wizard.
- Search index rebuild leverages the existing full-sync capability already present in the codebase.

## Out of Scope

- Automatic backup retention or cleanup in Google Drive (admin manages manually).
- Scheduled/automated backups (this feature covers manual, on-demand operations only).
- Incremental or differential backups (each backup is a full snapshot).
- Backup encryption at rest (relies on Google Drive's built-in encryption).
- Multi-user concurrent admin operations on data management.
- Backup to providers other than Google Drive.
- In-app Google Drive setup wizard (a static documentation guide is provided instead).
