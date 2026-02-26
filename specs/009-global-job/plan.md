# Implementation Plan: Add Global Head of Engineering Job & Skills

**Branch**: `009-global-job` | **Date**: 2026-02-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/009-global-job/spec.md`

## Summary

Add a new "Head of Engineering" job entry for Global and enrich the skills portfolio with a new AI skill group (5 skills) and 11 new skills in existing groups. This is a data-only feature requiring a MongoDB migration script, a company logo image, and integration tests. No changes to backend models, services, controllers, frontend types, or UI components are needed — the existing infrastructure from spec 004-skills-employment handles all display and cross-referencing automatically.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend), JavaScript (migration script)
**Primary Dependencies**: Spring Boot 3.5.x, Spring Data MongoDB, React (latest stable)
**Storage**: MongoDB (existing `jobs` and `skill_groups` collections)
**Testing**: Testcontainers (backend integration), Vitest (frontend)
**Target Platform**: Docker Compose (local), Linux server (production)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Standard — data addition only, no performance-critical paths
**Constraints**: Must conform to existing data model (spec 004), idempotent migration
**Scale/Scope**: 1 new job, 1 new skill group (5 skills), 11 new skills in existing groups, ~43 skill-job links

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Status | Notes |
|------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No container changes needed |
| II. Modern Java & React Stack | PASS | Uses existing MongoDB, no new dependencies introduced |
| III. Quality Gates | PASS | Migration script tested via integration tests with Testcontainers |
| IV. Observability & Operability | PASS | No new services, existing observability applies |
| V. Simplicity & Incremental Delivery | PASS | Data-only feature, minimal code changes |
| MongoDB as primary persistence | PASS | All data in MongoDB collections |
| Data seeding via restore script | PASS | Migration script runs alongside restore; new backup created after |
| Image serving via /uploads/** | PASS | Global logo served from backend/uploads/ |

**Post-Phase 1 Re-check**: All gates still pass. No schema changes, no new dependencies, no new containers.

## Project Structure

### Documentation (this feature)

```text
specs/009-global-job/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0: Skill deduplication, group mapping, image handling
├── data-model.md        # Phase 1: Complete data model for new documents
├── quickstart.md        # Phase 1: Setup and verification guide
├── contracts/
│   └── README.md        # No new API contracts needed
├── checklists/
│   └── requirements.md  # Spec quality checklist
├── attachments/         # Source files (Global logo, Spotlight reviews)
└── tasks.md             # Phase 2 output (via /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── uploads/
│   └── global-logo.jpg                          # NEW: Global company logo
└── src/test/java/com/simonrowe/
    ├── employment/
    │   └── JobControllerTest.java                # MODIFIED: Add Global job test case
    └── skills/
        └── SkillGroupControllerTest.java         # MODIFIED: Add AI group test case

scripts/
└── add-global-job-data.js                        # NEW: MongoDB migration script

frontend/
└── (no changes)
```

**Structure Decision**: Existing web application structure (backend/ + frontend/) is used. The only new source files are the migration script and the company logo. Test files are modified to add verification for the new data.

## Implementation Approach

### Phase A: Data Preparation

1. **Copy Global logo** from attachments to `backend/uploads/global-logo.jpg`
2. **Author the job long description** in markdown format based on Spotlight reviews (2023-2025) and GitHub repo analysis
3. **Assign proficiency ratings** for all 16 new skills (site owner input needed)

### Phase B: Migration Script

Create `scripts/add-global-job-data.js` that:

1. **Connects to MongoDB** (`simonrowe` database)
2. **Checks idempotency** — skips if Global job or AI group already exists
3. **Inserts AI skill group** at displayOrder 1 with 5 embedded skills
4. **Updates existing group display orders** — increment all by 1
5. **Adds 11 new skills** to their target groups (Cloud: 3, CI/CD: 2, Testing: 1, Web: 4, Identity & Security: 1)
6. **Recalculates group ratings** for the 5 affected groups
7. **Resolves skill IDs** — queries all skill groups to build the complete list of skill IDs for the Global job
8. **Inserts Global job** with all resolved skill IDs
9. **Logs summary** of changes made

### Phase C: Testing

1. **Backend integration test** — Verify the Global job is returned by GET /api/jobs and includes resolved skills
2. **Backend integration test** — Verify the AI skill group appears in GET /api/skills with correct display order
3. **Backend integration test** — Verify bidirectional skill-job correlations
4. **Existing tests** — Ensure all existing tests still pass (job count assertions may need updating from 9 to 10, skill group count from 9 to 10)

### Phase D: Backup Update

1. Run the migration script against the local database
2. Create a new backup with `scripts/create-backup.sh`
3. Verify restore works with `scripts/restore-backup.sh`

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Existing test assertions hardcode job/skill counts | High | Low | Update count assertions in test fixtures |
| Proficiency ratings not provided by owner | Medium | Medium | Use placeholder ratings, update later |
| Skill IDs in backup differ from generated IDs | Low | High | Migration script resolves IDs dynamically |
| Search index not updated after data addition | Low | Medium | Trigger search reindex after migration |

## Complexity Tracking

No constitution violations. No complexity justifications needed.
