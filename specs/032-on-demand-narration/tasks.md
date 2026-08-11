# Tasks: On-Demand Blog Narration

**Input**: Design documents from `/specs/032-on-demand-narration/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/narration-api.yaml, quickstart.md

**Tests**: Required by the specification and project constitution for backend concurrency/restore behavior and the critical frontend journey.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel because it targets different files with no incomplete dependency
- **[Story]**: Maps the task to a specification user story

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configure narration and document the external Google prerequisites.

- [X] T001 Add narration and Google Long Audio settings with disabled-safe defaults in `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`, and `.env.example`
- [X] T002 Add production credential-file mount and narration environment forwarding in `docker-compose.prod.yml`
- [X] T003 [P] Write the complete manual Google Cloud project, API, bucket, IAM, ADC, billing-budget, local, and Raspberry Pi setup guide in `docs/setup/google-cloud-tts.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create durable state, deterministic speech input, provider boundary, storage, messaging, and public request protection.

- [X] T004 Create `NarrationStatus`, `Narration`, and `NarrationRepository` with deterministic IDs and required indexes in `backend/src/main/java/com/simonrowe/narration/`
- [X] T005 [P] Create validated `NarrationProperties` and Google credential/HTTP client configuration in `backend/src/main/java/com/simonrowe/narration/NarrationProperties.java` and `GoogleTextToSpeechConfig.java`
- [X] T006 [P] Implement and test deterministic Markdown-to-speech conversion in `backend/src/main/java/com/simonrowe/narration/BlogNarrationScriptBuilder.java` and `backend/src/test/java/com/simonrowe/narration/BlogNarrationScriptBuilderTest.java`
- [X] T007 [P] Define provider operations/error classification and implement Google Long Audio REST start, poll, and download in `backend/src/main/java/com/simonrowe/narration/NarrationProvider.java` and `GoogleTextToSpeechProvider.java`
- [X] T008 Test Google request payloads, operation parsing, credential-safe failures, and download behavior in `backend/src/test/java/com/simonrowe/narration/GoogleTextToSpeechProviderTest.java`
- [X] T009 [P] Implement atomic MP3 validation/storage/checksum handling in `backend/src/main/java/com/simonrowe/narration/NarrationStorage.java` and test it in `backend/src/test/java/com/simonrowe/narration/NarrationStorageTest.java`
- [X] T010 [P] Add `NarrationRequestEvent` and Kafka publisher in `backend/src/main/java/com/simonrowe/events/NarrationRequestEvent.java` and `backend/src/main/java/com/simonrowe/narration/NarrationRequestPublisher.java`
- [X] T011 Extend Bucket4j configuration and routing for narration endpoints in `backend/src/main/java/com/simonrowe/ratelimit/RateLimitConfig.java`, `RateLimitInterceptor.java`, `backend/src/main/java/com/simonrowe/WebConfig.java`, and corresponding tests

**Checkpoint**: Durable narration primitives are ready and Google calls remain disabled-safe.

---

## Phase 3: User Story 1 - Listen to a Published Blog (Priority: P1) 🎯 MVP

**Goal**: A signed-out visitor can discover and play ready narration using accessible controls.

**Independent Test**: Seed a published blog and ready narration, open its detail page signed out, and play, pause, seek, and change speed; drafts and missing blogs expose nothing.

- [X] T012 [P] [US1] Add narration public DTO and status contract mapping in `backend/src/main/java/com/simonrowe/narration/NarrationResponse.java`
- [X] T013 [US1] Implement current published-blog narration lookup in `backend/src/main/java/com/simonrowe/narration/BlogNarrationService.java`
- [X] T014 [US1] Implement anonymous GET/POST narration routes in `backend/src/main/java/com/simonrowe/narration/BlogNarrationController.java`
- [X] T015 [US1] Add MockMvc coverage for ready, not requested, unpublished, missing, anonymous, and sanitized responses in `backend/src/test/java/com/simonrowe/narration/BlogNarrationControllerTest.java`
- [X] T016 [P] [US1] Add discriminated narration response types and API functions in `frontend/src/types/blog.ts` and `frontend/src/services/blogApi.ts`
- [X] T017 [US1] Build the accessible generated-narration strip with native audio and speed selection in `frontend/src/components/blog/BlogNarration.tsx` and mount it from `frontend/src/components/blog/BlogDetail.tsx`
- [X] T018 [P] [US1] Add BEM narration styles, responsive layout, focus, dark/light, and reduced-motion behavior in `frontend/src/styles.css`
- [X] T019 [US1] Test ready playback, public Listen, speed changes, no autoplay, and accessible names in `frontend/src/components/blog/BlogNarration.test.tsx`

**Checkpoint**: Ready narration is independently playable and accessible.

---

## Phase 4: User Story 2 - Prepare and Reuse Uncached Audio (Priority: P1)

**Goal**: Explicit uncached requests create one durable Kafka job, show progress through long polling, and survive concurrency and restarts.

**Independent Test**: Issue 100 simultaneous requests for one uncached published blog, redeliver its Kafka event, and verify one narration, one provider start, one final MP3, and automatic frontend transition to READY.

- [X] T020 [P] [US2] Add Mongo concurrency tests for deterministic insert and atomic claim behavior in `backend/src/test/java/com/simonrowe/narration/BlogNarrationConcurrencyTest.java`
- [X] T021 [US2] Implement idempotent create/retry, versioned status, atomic claim, state transitions, and bounded long polling in `backend/src/main/java/com/simonrowe/narration/BlogNarrationService.java`
- [X] T022 [US2] Implement Long Audio job start/resume/poll/download/revalidation in `backend/src/main/java/com/simonrowe/narration/NarrationRequestConsumer.java`
- [X] T023 [US2] Test Kafka redelivery, operation resumption, stale blog handling, safe failure, and ambiguous non-retry in `backend/src/test/java/com/simonrowe/narration/NarrationRequestConsumerTest.java`
- [X] T024 [US2] Add queued/expired lease recovery and blog update/delete invalidation in `backend/src/main/java/com/simonrowe/narration/NarrationRecoveryScheduler.java` and `NarrationContentChangeConsumer.java`
- [X] T025 [US2] Extend controller tests for idempotent POST, 202 queueing, failed retry, versioned waits, size rejection, and provider-disabled behavior in `backend/src/test/java/com/simonrowe/narration/BlogNarrationControllerTest.java`
- [X] T026 [US2] Implement abortable sequential bounded long polling, preparing/delayed/failure/retry states, and no-autoplay transition in `frontend/src/components/blog/BlogNarration.tsx`
- [X] T027 [US2] Test queued-to-ready polling, delayed cap, retry, unavailable/ineligible, and unmount abort behavior in `frontend/src/components/blog/BlogNarration.test.tsx`

**Checkpoint**: Uncached anonymous generation is durable, deduplicated, observable, and recoverable.

---

## Phase 5: User Story 3 - Restore Narrations with Blogs (Priority: P2)

**Goal**: Narration records and MP3s round-trip through full backup/restore without regeneration.

**Independent Test**: Back up ready and in-progress narrations, clear, restore, and verify valid audio remains ready while inconsistent state becomes safely retryable; restore an older archive without narrations.

- [X] T028 [US3] Add narration collection and explicit narration/audio counts to `backend/src/main/java/com/simonrowe/dataops/BackupService.java` and add narration cleanup coverage to `ClearService.java`
- [X] T029 [US3] Implement restored index recreation and record/file reconciliation in `backend/src/main/java/com/simonrowe/narration/NarrationRestoreValidator.java` and wire it into `backend/src/main/java/com/simonrowe/dataops/RestoreService.java`
- [X] T030 [US3] Add backup coverage, archive round-trip, old-archive compatibility, and corrupt-file tests in `backend/src/test/java/com/simonrowe/dataops/NarrationBackupCoverageTest.java` and `backend/src/test/java/com/simonrowe/narration/NarrationRestoreValidatorTest.java`

**Checkpoint**: Generated paid assets satisfy the full-with-media disaster-recovery policy.

---

## Phase 6: User Story 4 - Control Cost and Diagnose Failures (Priority: P2)

**Goal**: Provider-bound work respects the configured monthly ceiling and emits useful non-identifying operational telemetry.

**Independent Test**: Exhaust the configured limit, verify uncached work is blocked while cached playback continues, and account for generated/reused/failed/uncertain characters through metrics and logs.

- [X] T031 [US4] Implement monthly provider-character accounting and pre-dispatch budget enforcement in `backend/src/main/java/com/simonrowe/narration/NarrationBudgetService.java`
- [X] T032 [US4] Add generated, reused, failed, uncertain, duration, and character Micrometer instrumentation across `BlogNarrationService.java` and `NarrationRequestConsumer.java`
- [X] T033 [US4] Test budget boundaries, cached bypass, sanitized errors, and metric increments in `backend/src/test/java/com/simonrowe/narration/NarrationBudgetServiceTest.java` and service/consumer tests

**Checkpoint**: Anonymous narration has an application-level spend ceiling and observable outcomes.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Validate contracts, runtime configuration, media behavior, and all quality gates.

- [X] T034 [P] Update user-facing operational wording in `frontend/src/pages/admin/DataOperationsAdmin.tsx` to state that generated narration is included in full backups
- [X] T035 [P] Add HTTP range and immutable-cache verification for narration MP3s in `backend/src/test/java/com/simonrowe/narration/NarrationMediaServingTest.java`
- [X] T036 Reconcile implementation with `specs/032-on-demand-narration/contracts/narration-api.yaml` and execute every applicable scenario in `specs/032-on-demand-narration/quickstart.md`
- [X] T037 Run `cd backend && ../gradlew test check` and resolve test, checkstyle, and coverage failures
- [X] T038 Run `cd frontend && npm test && npm run lint && npm run build` and resolve frontend failures
- [X] T039 Confirm all manual prerequisites and production rollout/rollback steps are complete in `docs/setup/google-cloud-tts.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup has no dependencies.
- Foundational depends on Setup and blocks all stories.
- US1 depends on the foundation and delivers ready-audio playback.
- US2 depends on US1's API/component shell and adds generation/status transitions.
- US3 depends on the durable model and storage from US2.
- US4 depends on the provider-dispatch path from US2 but not US3.
- Polish depends on all selected stories.

### Parallel Opportunities

- T003 documentation can run alongside configuration tasks.
- T005, T006, T007, T009, and T010 target separate foundational files.
- T012 and T016 can establish backend/frontend contracts in parallel.
- T018 can follow the agreed component class names while backend US1 work proceeds.
- US3 backup work and US4 budget work touch different subsystems after US2.
- T034 and T035 are independent polish tasks.

## Implementation Strategy

### MVP First

1. Complete Setup and Foundational phases.
2. Complete US1 so an existing ready asset is publicly playable.
3. Complete US2 so visitors can create that asset on demand.
4. Validate concurrency and the full browser transition before backup/cost extensions.

### Incremental Delivery

1. Foundation → provider disabled safely.
2. US1 → accessible ready playback.
3. US2 → on-demand Google generation and dedupe.
4. US3 → durable disaster recovery.
5. US4 → spend protection and telemetry.
6. Polish → full quality-gate and setup verification.

## Format Validation

All 39 tasks use the required checkbox, sequential task ID, optional `[P]`, required story label within story phases, concrete action, and explicit file path format.
