# Implementation Plan: Admin Data Operations

**Branch**: `011-admin-data-ops` | **Date**: 2026-03-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/011-admin-data-ops/spec.md`

## Summary

Add a "Data Operations" section to the admin panel with four capabilities: backup all data (MongoDB + media) to Google Drive, restore from a Google Drive backup, clear all local data, and rebuild the Elasticsearch search index. The backend uses the Google Drive API v3 with service account authentication (base64-encoded credentials via environment variable), MongoTemplate for programmatic database export/import (Extended JSON for lossless round-trip), and java.util.zip for archive creation. Progress is streamed to the frontend via Server-Sent Events (SSE). A Google Drive authentication setup guide is included as project documentation.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, Spring Data MongoDB, Spring Data Elasticsearch, Google Drive API v3 (`google-api-services-drive`), Google Auth Library, React (latest stable), Lucide React
**Storage**: MongoDB (primary, database: `simonrowe`), Elasticsearch (search indices: `site_search`, `blog_search`), Google Drive (backup archives)
**Testing**: Testcontainers (MongoDB, Elasticsearch), Vitest (frontend), GraalVM tracing agent for native image configs
**Target Platform**: Linux container (GraalVM native image), Docker Compose orchestration
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Backup/restore < 5 min for < 500MB dataset, clear < 30s, index rebuild < 2 min for 1,000 docs
**Constraints**: GraalVM native image compatibility (Google API client requires reflection configs), single-admin single-instance deployment
**Scale/Scope**: 9 MongoDB collections, ~50-500MB typical dataset, single admin user

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Changes contained within existing backend/ and frontend/ modules |
| II. Modern Java & React Stack | PASS | Uses Java 21, Spring Boot 3.5.x, React, MongoDB, Elasticsearch. New deps: Google Drive API (justified by feature requirement). Plain CSS + BEM for styling. Lucide React icons. |
| III. Quality Gates | PASS | Integration tests with Testcontainers. Google Java Style Guide. JaCoCo coverage. |
| IV. Observability & Operability | PASS | Structured logging for all operations. No new metrics endpoints needed. |
| V. Simplicity & Incremental Delivery | PASS | Each operation (backup, restore, clear, rebuild) is independently testable. In-memory operation tracking (no new persistence). ZIP format uses JDK built-ins. |
| VI. Admin CMS UX Standards | PASS | Follows existing admin page patterns, Lucide React icons for actions. |
| VII. Backup & Restore | EVOLUTION | This feature replaces shell-script backup/restore with in-app Google Drive backup. The existing scripts remain for local use. Constitution Principle VII describes the current shell-script approach — this feature extends it with a programmatic, UI-driven alternative. |
| VIII. Shell Scripting Standards | N/A | No new shell scripts in this feature. |

**Post-Phase 1 re-check**: PASS. No violations. The evolution of Principle VII is additive (in-app backup supplements existing scripts, does not remove them).

## Project Structure

### Documentation (this feature)

```text
specs/011-admin-data-ops/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # OpenAPI 3.1 contract
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/dataops/
│   ├── DataOperationsController.java     # REST + SSE endpoints
│   ├── DataOperationsService.java        # Operation orchestration + concurrency control
│   ├── BackupService.java                # MongoDB export + ZIP + Drive upload
│   ├── RestoreService.java               # Drive download + ZIP extract + MongoDB import
│   ├── ClearService.java                 # Drop collections + delete uploads + clear indices
│   ├── GoogleDriveService.java           # Drive API wrapper
│   ├── GoogleDriveConfig.java            # Credentials config from env var
│   ├── DataOperation.java                # Operation status record
│   ├── BackupMetadata.java               # Backup listing DTO
│   ├── DataOperationsStatus.java         # Combined status DTO
│   ├── RestoreRequest.java               # Request body record
│   ├── ClearRequest.java                 # Request body record
│   └── OperationType.java                # Enum
├── src/main/resources/
│   └── META-INF/native-image/            # GraalVM tracing agent output
│       ├── reflect-config.json
│       ├── resource-config.json
│       └── proxy-config.json
├── src/test/java/com/simonrowe/dataops/
│   ├── DataOperationsControllerTest.java # Integration tests (Testcontainers)
│   ├── BackupServiceTest.java            # Unit tests
│   ├── RestoreServiceTest.java           # Unit tests
│   ├── ClearServiceTest.java             # Unit tests
│   └── GoogleDriveServiceTest.java       # Unit tests (mocked Drive client)
└── build.gradle.kts                      # Add Google Drive API dependencies

frontend/
├── src/
│   ├── pages/admin/
│   │   └── DataOperationsAdmin.tsx       # Data operations page
│   ├── services/
│   │   └── dataOperationsApi.ts          # API client + SSE helper
│   ├── components/admin/
│   │   └── AdminLayout.tsx               # Add nav item (modified)
│   ├── App.tsx                           # Add route (modified)
│   └── styles.css                        # Data operations styles (modified)
└── tests/
    └── DataOperationsAdmin.test.tsx      # Component tests

docs/
└── google-drive-setup.md                 # Google Drive auth setup guide
```

**Structure Decision**: Follows the existing web application structure with backend/ and frontend/ modules. New backend code is in a `dataops` package following the existing pattern (e.g., `media`, `search`, `admin`). The Google Drive setup guide goes in a new `docs/` directory at the repository root.

## Complexity Tracking

No constitution violations. No complexity justifications needed.
