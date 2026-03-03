# Tasks: Add Global Head of Engineering Job & Skills

**Input**: Design documents from `/specs/009-global-job/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Integration tests are included to verify data insertion, as the existing test infrastructure validates API responses against seeded data.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing. Since this is a data-only feature, implementation centers on a bash/zsh seed script and a MongoDB migration script — no backend/frontend code changes are needed.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Copy assets and establish the seed script infrastructure

- [x] T001 Copy Global logo from `specs/009-global-job/attachments/global-logo.jpg` to `backend/uploads/global-logo.jpg`
- [x] T002 Create bash seed script shell with MongoDB container detection, idempotency framework, and logging in `scripts/seed-global-job.sh`
- [x] T003 Create MongoDB migration script shell with database connection and helper functions in `scripts/add-global-job-data.js`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core seed script infrastructure that MUST be complete before user story data can be added

**Warning**: No user story work can begin until this phase is complete

- [x] T004 Add idempotency checks to `scripts/add-global-job-data.js` — skip if Global job or AI group already exists in `simonrowe` database
- [x] T005 Add display order shifting logic to `scripts/add-global-job-data.js` — increment all 9 existing skill group `displayOrder` values by 1
- [x] T006 Add skill ID resolution helper to `scripts/add-global-job-data.js` — query `skill_groups` collection to build a map of skill name → skill ID for linking

**Checkpoint**: Seed script infrastructure ready — user story data insertion can now be added

---

## Phase 3: User Story 1 - View Global Head of Engineering Position on Timeline (Priority: P1)

**Goal**: Add the Global job document to MongoDB so it appears as the most recent position on the employment timeline

**Independent Test**: Run seed script, then `curl http://localhost:8080/api/jobs | jq '.[] | select(.company == "Global")'` returns the complete job with title, dates, description, skills, and logo

### Implementation for User Story 1

- [x] T007 [US1] Author the Global job long description in markdown format based on Spotlight reviews (2023-2025) and GitHub repo analysis, covering: role overview, team leadership, architecture & technical strategy, platform & infrastructure, product delivery, and engineering practices — store as embedded string in `scripts/add-global-job-data.js`
- [x] T008 [US1] Add Global job document insertion to `scripts/add-global-job-data.js` with fields: title "Head of Engineering", company "Global", companyUrl "https://global.com", companyImage (url: "/uploads/global-logo.jpg", name: "global-logo.jpg", mime: "image/jpeg"), startDate "2021-08-01", endDate null, location "Holborn, London", shortDescription, longDescription (from T007), isEducation false, includeOnResume true, skills [] (populated later in T018)
- [x] T009 [US1] Add integration test case to `backend/src/test/java/com/simonrowe/employment/JobControllerTest.java` verifying GET /api/jobs returns 10 jobs and the Global job has correct title, company, dates, location, and non-empty skills array

**Checkpoint**: Global job visible on timeline with full detail view

---

## Phase 4: User Story 2 - Browse New AI Skill Group (Priority: P2)

**Goal**: Insert the AI skill group with 5 skills so it appears first in the skills grid

**Independent Test**: Run seed script, then `curl http://localhost:8080/api/skills | jq '.[] | select(.name == "AI")'` returns the group with 5 skills, correct display order 1, and calculated rating

### Implementation for User Story 2

- [x] T010 [US2] Add AI skill group document insertion to `scripts/add-global-job-data.js` with name "AI", description, displayOrder 1, image null, and 5 embedded skills: Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, MCP — each with generated unique ID (new ObjectId().str), name, rating (placeholder TBD), displayOrder (1-5), and description per `specs/009-global-job/data-model.md`
- [x] T011 [US2] Add AI group rating calculation to `scripts/add-global-job-data.js` — compute average of 5 skill ratings and set as group rating
- [x] T012 [US2] Add integration test case to `backend/src/test/java/com/simonrowe/skills/SkillGroupControllerTest.java` verifying GET /api/skills returns 10 groups, AI group has displayOrder 1, contains 5 skills, and has a calculated rating

**Checkpoint**: AI skill group visible in skills grid at position 1 with all 5 skills

---

## Phase 5: User Story 3 - Discover New Skills Added to Existing Groups (Priority: P3)

**Goal**: Add 11 new skills to 5 existing skill groups and recalculate affected group ratings

**Independent Test**: Run seed script, then verify each target group contains the new skills: Cloud (+3), CI/CD (+2), Testing (+1), Web (+4), Identity & Security (+1)

### Implementation for User Story 3

- [x] T013 [P] [US3] Add 3 new Cloud skills to `scripts/add-global-job-data.js` — Terraform, OpenTelemetry, AWS — appended to Cloud group's skills array with generated IDs, ratings, display orders, and descriptions per data-model.md
- [x] T014 [P] [US3] Add 2 new CI/CD skills to `scripts/add-global-job-data.js` — Jenkins, GitHub Actions — appended to CI/CD group's skills array with generated IDs, ratings, display orders, and descriptions per data-model.md
- [x] T015 [P] [US3] Add 1 new Testing skill to `scripts/add-global-job-data.js` — Playwright — appended to Testing group's skills array with generated ID, rating, display order, and description per data-model.md
- [x] T016 [P] [US3] Add 4 new Web skills to `scripts/add-global-job-data.js` — GraphQL, Material UI, Vite, Directus — appended to Web group's skills array with generated IDs, ratings, display orders, and descriptions per data-model.md
- [x] T017 [P] [US3] Add 1 new Identity & Security skill to `scripts/add-global-job-data.js` — OAuth2/OIDC — appended to Identity & Security group's skills array with generated ID, rating, display order, and description per data-model.md
- [x] T018 [US3] Add rating recalculation logic to `scripts/add-global-job-data.js` for the 5 affected groups (Cloud, CI/CD, Testing, Web, Identity & Security) — recompute each group's rating as the average of all child skill ratings

**Checkpoint**: All 11 new skills appear in their respective groups with recalculated ratings

---

## Phase 6: User Story 4 - Cross-Reference Global Job with Skills (Priority: P4)

**Goal**: Link the Global job to ~43 skill IDs spanning all 10 groups and verify bidirectional consistency

**Independent Test**: Run seed script, then verify the Global job's skills array contains IDs from all 10 groups, and each linked skill's job correlations include the Global job

### Implementation for User Story 4

- [x] T019 [US4] Add skill ID resolution and job update to `scripts/add-global-job-data.js` — query all skill groups to resolve IDs for the ~27 existing skills and 16 new skills listed in `specs/009-global-job/data-model.md` (Skill Linkage section), then update the Global job's skills array with all resolved IDs
- [x] T020 [US4] Add validation logging to `scripts/add-global-job-data.js` — print summary of total skills linked, skills per group, and any skills that could not be resolved
- [x] T021 [US4] Add integration test to `backend/src/test/java/com/simonrowe/employment/JobControllerTest.java` verifying GET /api/jobs/{id} for the Global job returns resolved skills from at least 4 different skill groups (per SC-006)

**Checkpoint**: Bidirectional skill-job cross-referencing fully functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Update test assertions, create backup, and final verification

- [x] T022 Update existing job count assertions in `backend/src/test/java/com/simonrowe/employment/JobControllerTest.java` from 9 to 10 — N/A: tests use `@BeforeEach` cleanup, no hardcoded counts
- [x] T023 Update existing skill group count assertions in `backend/src/test/java/com/simonrowe/skills/SkillGroupControllerTest.java` from 9 to 10 — N/A: tests use `@BeforeEach` cleanup, no hardcoded counts
- [ ] T024 Run seed script against local MongoDB: `./scripts/seed-global-job.sh`
- [ ] T025 Create updated backup with `./scripts/create-backup.sh` to include new Global job and skills data
- [ ] T026 Verify backup restore works: `./scripts/restore-backup.sh` followed by `./scripts/seed-global-job.sh`
- [x] T027 Run full backend test suite: `cd backend && ./gradlew test` — BUILD SUCCESSFUL, 100% pass rate
- [ ] T028 Run quickstart.md validation — follow all steps in `specs/009-global-job/quickstart.md` end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion (T002, T003) — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Phase 2 — adds job document to seed script
- **User Story 2 (Phase 4)**: Depends on Phase 2 — adds AI group to seed script (independent of US1)
- **User Story 3 (Phase 5)**: Depends on Phase 2 — adds skills to existing groups (independent of US1, US2)
- **User Story 4 (Phase 6)**: Depends on US1 + US2 + US3 — resolves skill IDs from all groups and links to job
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) — No dependencies on other stories
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) — No dependencies on other stories
- **User Story 4 (P4)**: Depends on US1, US2, and US3 — needs all skill groups populated and job inserted before linking

### Within Each User Story

- Script logic before test cases
- Data insertion before ID resolution
- Group-level operations before individual skill operations

### Parallel Opportunities

- T001 (copy logo) can run in parallel with T002 (bash script) and T003 (JS script)
- US1, US2, and US3 (Phases 3, 4, 5) can be implemented in parallel as they add independent data to the seed script
- T013, T014, T015, T016, T017 (adding skills to 5 different groups) can all run in parallel
- T022 and T023 (updating test assertions) can run in parallel

---

## Parallel Example: User Story 3

```bash
# Launch all skill group additions together (different groups, no dependencies):
Task: "Add 3 new Cloud skills (Terraform, OpenTelemetry, AWS) in scripts/add-global-job-data.js"
Task: "Add 2 new CI/CD skills (Jenkins, GitHub Actions) in scripts/add-global-job-data.js"
Task: "Add 1 new Testing skill (Playwright) in scripts/add-global-job-data.js"
Task: "Add 4 new Web skills (GraphQL, Material UI, Vite, Directus) in scripts/add-global-job-data.js"
Task: "Add 1 new Identity & Security skill (OAuth2/OIDC) in scripts/add-global-job-data.js"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (copy logo, create script shells)
2. Complete Phase 2: Foundational (idempotency, display order shifting, ID resolution)
3. Complete Phase 3: User Story 1 (Global job with empty skills array)
4. **STOP and VALIDATE**: Run seed script, verify job appears on timeline
5. The Global job is visible but has no linked skills yet

### Incremental Delivery

1. Complete Setup + Foundational → Script infrastructure ready
2. Add User Story 1 → Global job on timeline (MVP!)
3. Add User Story 2 → AI skill group in skills grid
4. Add User Story 3 → New skills enriching existing groups
5. Add User Story 4 → Full cross-referencing between job and skills
6. Polish → Updated tests, backup, final verification
7. Each story adds data without breaking previous stories

### Single Developer Strategy

Since this is a data-only feature with a single seed script:

1. Complete Phases 1-2: Setup + Foundational
2. Build the seed script incrementally through US1 → US2 → US3 → US4
3. Run seed script once after all user stories are implemented
4. Run tests and create backup in Polish phase

---

## Notes

- [P] tasks = different files or independent sections within same file, no dependencies
- [Story] label maps task to specific user story for traceability
- The bash script `scripts/seed-global-job.sh` is the user-facing entry point
- The JS script `scripts/add-global-job-data.js` runs via `mongosh` inside the MongoDB container (same pattern as `migrate-strapi-data.js`)
- Proficiency ratings marked as "TBD" in data-model.md need site owner input before final seeding
- Skill IDs are generated dynamically by the migration script — they are not hardcoded
- The seed script is idempotent — safe to run multiple times
- Total new data: 1 job, 1 skill group (5 skills), 11 skills in existing groups, ~43 skill-job links
