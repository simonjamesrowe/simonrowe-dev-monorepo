# Quickstart: Admin Data Operations

**Branch**: `011-admin-data-ops` | **Date**: 2026-03-28

## Prerequisites

- Java 21, Node.js (latest LTS), Docker (for MongoDB, Elasticsearch, Kafka)
- Google Cloud project with Drive API enabled and a service account key (see setup guide)
- Running MongoDB instance (`mongodb://localhost:27017/simonrowe`)
- Running Elasticsearch instance
- Auth0 configured (existing)

## Environment Variables (new for this feature)

```bash
# Base64-encoded Google service account JSON key
export GOOGLE_DRIVE_CREDENTIALS="<base64-encoded-json-key>"
```

## Backend Changes

### New dependencies (backend/build.gradle.kts)

```kotlin
implementation("com.google.apis:google-api-services-drive:v3-rev20250511-2.0.0")
implementation("com.google.api-client:google-api-client:2.8.0")
implementation("com.google.auth:google-auth-library-oauth2-http:1.35.0")
```

### New files

```
backend/src/main/java/com/simonrowe/dataops/
├── DataOperationsController.java    # REST endpoints + SSE progress
├── DataOperationsService.java       # Orchestrates all 4 operations
├── BackupService.java               # MongoDB export + ZIP creation
├── RestoreService.java              # ZIP extraction + MongoDB import
├── ClearService.java                # Drop collections + delete uploads + clear indices
├── GoogleDriveService.java          # Google Drive API wrapper (upload, download, list, folder management)
├── GoogleDriveConfig.java           # Spring config for Drive client (credentials from env var)
├── DataOperation.java               # Operation status record
├── BackupMetadata.java              # Backup listing record
├── DataOperationsStatus.java        # Combined status DTO
├── RestoreRequest.java              # Request body record
├── ClearRequest.java                # Request body record
└── OperationType.java               # Enum: BACKUP, RESTORE, CLEAR, REBUILD_INDEX
```

### GraalVM native image configuration

Run tests with tracing agent to generate reflection configs for Google API client:
```bash
cd backend && ../gradlew test -Pagent
```

Generated configs go to:
```
backend/src/main/resources/META-INF/native-image/
├── reflect-config.json
├── resource-config.json
└── proxy-config.json
```

## Frontend Changes

### New files

```
frontend/src/pages/admin/DataOperationsAdmin.tsx   # Main page component
frontend/src/services/dataOperationsApi.ts         # API client functions
```

### Modified files

```
frontend/src/components/admin/AdminLayout.tsx  # Add "Data Operations" nav item
frontend/src/App.tsx                           # Add /admin/data-operations route
frontend/src/styles.css                        # Data operations page styles
```

## Testing

### Backend

```bash
cd backend && ../gradlew test
```

Key test files:
- `DataOperationsControllerTest.java` — integration tests with Testcontainers (MongoDB, Elasticsearch)
- `BackupServiceTest.java` — unit tests for ZIP creation and MongoDB export
- `GoogleDriveServiceTest.java` — unit tests with mocked Google Drive client
- `RestoreServiceTest.java` — unit tests for ZIP extraction and MongoDB import

### Frontend

```bash
cd frontend && npm test
```

Key test files:
- `DataOperationsAdmin.test.tsx` — component tests for all operation flows

## Quick Verification

1. Start backend: `cd backend && ../gradlew bootRun`
2. Start frontend: `cd frontend && npm run dev`
3. Navigate to `http://localhost:5173/admin/data-operations`
4. Verify Google Drive shows "Connected" status
5. Test "Rebuild Search Index" (simplest operation, no Drive dependency)
6. Test "Backup to Google Drive" and verify archive appears in Drive
7. Test "Restore from Google Drive" using the backup just created
8. Test "Clear All Data" with confirmation
