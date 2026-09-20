# Tasks: CoParent Migration

**Input**: Design documents from `specs/048-coparent-migration/`

**Tests**: Required by the feature specification. Backend contract tests use the shared MongoDB Testcontainer; frontend behaviour uses Vitest/MSW and Playwright.

## Phase 1: Setup

**Purpose**: Establish the isolated product entry points and dependency baseline.

- [X] T001 Add the CoParent frontend runtime/test dependencies and scripts in `frontend/package.json` and `frontend/package-lock.json`
- [X] T002 [P] Add disabled-by-default CoParent backend and frontend environment variables to `backend/src/main/resources/application.yml`, `.env.example`, and `frontend/.env.example`
- [X] T003 [P] Create the CoParent Java package skeleton in `backend/src/main/java/com/simonrowe/coparent/` and frontend entry skeleton in `frontend/src/coparent/`

---

## Phase 2: Foundational

**Purpose**: Shared tenancy, persistence, errors, feature isolation, and schema prerequisites that block every user story.

- [X] T004 Write failing feature-flag, authentication, malformed-id, and cross-family isolation tests in `backend/src/test/java/com/simonrowe/coparent/CoparentAccessIntegrationTest.java`
- [X] T005 Implement CoParent configuration, feature gating, JWT identity extraction, family access enforcement, and problem responses in `backend/src/main/java/com/simonrowe/coparent/config/` and `backend/src/main/java/com/simonrowe/coparent/shared/`
- [X] T006 [P] Implement the ten dedicated-database Mongo document models in `backend/src/main/java/com/simonrowe/coparent/model/`
- [X] T007 [P] Implement family-scoped persistence and audit support in `backend/src/main/java/com/simonrowe/coparent/persistence/`
- [X] T008 Write `V043CreateCoparentCollectionsTest` in `backend/src/test/java/com/simonrowe/migration/changeunits/V043CreateCoparentCollectionsTest.java`
- [X] T009 Implement idempotent collection/index creation in `backend/src/main/java/com/simonrowe/migration/changeunits/V043CreateCoparentCollections.java`
- [X] T010 Write `V044MigrateCoparentDataTest` in `backend/src/test/java/com/simonrowe/migration/changeunits/V044MigrateCoparentDataTest.java`
- [X] T011 Implement guarded, id-preserving source database migration in `backend/src/main/java/com/simonrowe/migration/changeunits/V044MigrateCoparentData.java`

**Checkpoint**: CoParent is disabled by default, authenticated when enabled, family scoped, and its schema/migration can be replayed safely.

---

## Phase 3: User Story 1 - Set up and manage a family (P1) MVP

**Goal**: A signed-in parent can complete onboarding and manage their family and children without crossing tenant boundaries.

**Independent Test**: Complete onboarding, edit a child, sign back in, and verify persistence plus wrong-family denial.

- [X] T012 [US1] Write family, parent, child, current-user, and onboarding contract tests in `backend/src/test/java/com/simonrowe/coparent/family/FamilyApiIntegrationTest.java`
- [X] T013 [US1] Implement family, parent, child, and onboarding services in `backend/src/main/java/com/simonrowe/coparent/family/`
- [X] T014 [US1] Implement family, parent, child, current-user, and onboarding controllers in `backend/src/main/java/com/simonrowe/coparent/family/`
- [X] T015 [US1] Port the authenticated CoParent app shell, Auth0 adapter, API client, routing, onboarding, dashboard, and family screens into `frontend/src/coparent/`
- [ ] T016 [US1] Translate the migrated shell/onboarding/family presentation into scoped BEM rules in `frontend/src/styles.css`
- [X] T017 [US1] Add onboarding and child-management component tests in `frontend/src/coparent/**/*.test.tsx`

**Checkpoint**: User Story 1 is independently usable and tenant-isolation tested.

---

## Phase 4: User Story 2 - Invite the other parent (P2)

**Goal**: A primary parent can invite a co-parent and the intended authenticated recipient can join exactly once.

**Independent Test**: Exercise create, duplicate, resend, cancel, expiry, wrong-email, and successful acceptance.

- [X] T018 [US2] Write invitation contract tests in `backend/src/test/java/com/simonrowe/coparent/invitation/InvitationApiIntegrationTest.java`
- [X] T019 [US2] Implement invitation lifecycle, redacted-token handling, and mail delivery in `backend/src/main/java/com/simonrowe/coparent/invitation/`
- [X] T020 [US2] Port invitation management and acceptance screens into `frontend/src/coparent/`
- [X] T021 [US2] Add invitation component coverage in the ported CoParent frontend tests

**Checkpoint**: User Stories 1 and 2 work independently and invitations do not expose reusable secrets.

---

## Phase 5: User Story 3 - Coordinate schedules (P3)

**Goal**: Family members can manage calendar data and safely decide schedule-change requests.

**Independent Test**: Two parents create/edit/delete events and decide a request while another family sees nothing.

- [X] T022 [US3] Write event, category, recurrence, and schedule-change contract tests in `backend/src/test/java/com/simonrowe/coparent/calendar/CalendarApiIntegrationTest.java`
- [X] T023 [US3] Implement event/category/calendar and schedule-change services/controllers in `backend/src/main/java/com/simonrowe/coparent/calendar/`
- [X] T024 [US3] Port day/week/month calendar, editor, categories, and schedule-change UI into `frontend/src/coparent/components/calendar/`
- [X] T025 [US3] Add calendar and schedule-change component tests in `frontend/src/coparent/components/calendar/`

**Checkpoint**: User Stories 1–3 work independently with role and requester rules covered asymmetrically.

---

## Phase 6: User Story 4 - Communicate and record decisions (P4)

**Goal**: Family members can message each other and make auditable child permission decisions.

**Independent Test**: Send/read/unread messages and approve/deny permissions as two family members, including outsider denial.

- [X] T026 [US4] Write conversation, message, unread-state, and permission contract tests in `backend/src/test/java/com/simonrowe/coparent/messaging/MessagingApiIntegrationTest.java`
- [X] T027 [US4] Implement conversation/message/permission services and controllers in `backend/src/main/java/com/simonrowe/coparent/messaging/`
- [X] T028 [US4] Port message list/thread/composer and permission decision UI into `frontend/src/coparent/components/messaging/`
- [X] T029 [US4] Add messaging and permission component tests in `frontend/src/coparent/components/messaging/`

**Checkpoint**: All migrated product workflows are independently testable and family isolated.

---

## Phase 7: User Story 5 - Operate CoParent in simonrowe.dev (P5)

**Goal**: Serve, monitor, back up, restore, and disable CoParent through the shared platform.

**Independent Test**: Exercise host-based deep links and API routing, PWA scope, backup/restore, and disabled-product isolation.

- [X] T030 [US5] Configure the third Vite entry, canonical-origin bootstrap, manifest, scoped service worker, and static-only caching in `frontend/vite.config.ts`, `frontend/coparent/index.html`, and `frontend/src/coparent/`
- [X] T031 [US5] Add CoParent host/deep-link/API/maintenance routing to `config/nginx/nginx-proxy.conf`, `frontend/nginx.conf`, and `docker-compose.prod.yml`
- [X] T032 [US5] Extend backup, restore, and hostname monitoring workflows in `scripts/backup.sh`, `scripts/restore.sh`, and `scripts/monitor-prod.sh`
- [X] T033 [US5] Add routing and operational regression tests in `frontend/e2e/coparent.local.spec.ts` and `scripts/test/`
- [X] T034 [US5] Add a production cutover/rollback runbook in `docs/runbooks/coparent-cutover.md` and update `README.md` and `docs/architecture.md`

---

## Phase 8: Polish and Quality Gates

- [ ] T035 Replace the scoped legacy utility CSS with BEM, verify dark/mobile styling, and retain only implemented placeholder routes in `frontend/src/coparent/` and `frontend/src/styles.css`
- [X] T036 Run the full backend test, checkstyle, and coverage gates
- [X] T037 Run frontend lint, unit coverage, production build, dependency audit, and CoParent Playwright checks
- [X] T038 Validate `specs/048-coparent-migration/quickstart.md` and record production-only inventory, Auth0, DNS, SMTP, and cutover prerequisites without performing cutover

---

## Dependencies and Execution Order

- Phase 1 precedes Phase 2; Phase 2 blocks every user story.
- User stories execute in priority order because invitations, calendars, and conversations reuse family membership from User Story 1.
- Within each story, tests are written and observed failing before implementation; backend contracts precede frontend integration.
- User Story 5 depends on all application stories so its smoke and recovery checks cover the complete product.
- Final quality gates depend on every implementation task.

## Parallel Opportunities

- T002 and T003 can proceed after dependency selection without touching the same files.
- T006 and T007 can proceed once persistence shapes are agreed.
- Frontend presentation work can proceed alongside a story's backend implementation after its contract is fixed.
- Documentation and isolated script tests can proceed alongside frontend routing after the host contract is fixed.
