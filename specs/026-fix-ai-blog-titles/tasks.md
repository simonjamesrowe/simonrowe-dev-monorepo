---

description: "Task list for feature implementation"
---

# Tasks: Fix AI Blog Titles and Images

**Input**: Design documents from `/specs/026-fix-ai-blog-titles/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [X] T001 Verify backend compiles properly

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Relevant AI Blog Titles (Priority: P1) 🎯 MVP

**Goal**: Ensure new AI blogs generate appropriate titles instead of generic phrases.

**Independent Test**: Can be fully tested by generating a new AI digest blog and observing the title.

### Implementation for User Story 1

- [X] T002 [P] [US1] Update `METADATA_PROMPT` in `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java` to explicitly forbid "this week in AI" phrases (if not already handled).

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - Retrospective Title and Image Fix (Priority: P1)

**Goal**: All historically generated AI blogs have their titles fixed and featured images regenerated based on the newly improved prompts.

**Independent Test**: Run application and verify historical digest blogs have updated titles and images.

### Implementation for User Story 2

- [X] T003 [US2] Create new Mongock ChangeUnit in `backend/src/main/java/com/simonrowe/migration/changeunits/V006FixAiBlogTitles.java` that:
  - Iterates over blogs with "AI & Tech Roundup", "this week in AI", or tagged "Weekly Digest" with generic titles.
  - Calls `DigestMetadataGenerator.generate` using the blog content.
  - Regenerates images via `BlogImageGenerationService.generateAndStore`.
  - Saves the updated blog entities via `BlogRepository`.

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [X] T004 Run quickstart.md validation to ensure migration completes successfully.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Independent
- **User Story 2 (P1)**: Depends on User Story 1's prompt modifications to ensure the migration uses the correct rules.

### Within Each User Story

- Story complete before moving to next priority

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 3: User Story 1
2. **STOP and VALIDATE**: Test User Story 1 independently

### Incremental Delivery

1. Complete User Story 1 (prompt update)
2. Add User Story 2 (migration) to fix historical data

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
