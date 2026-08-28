# Tasks: Software Factory Console

**Input**: Design documents from `specs/040-software-factory-console/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required by the specification and constitution. Tests precede their corresponding
implementation tasks.

## Phase 1: Setup

- [x] T001 Correct the generated active-feature context in `AGENTS.md` and keep `.specify/feature.json` pointed at feature 040
- [x] T002 [P] Add feedback, vulnerability-scan, Linear, platform-backup, and backend factory-client defaults to `software-factory/src/main/resources/application.yml`, `backend/src/main/resources/application.yml`, and `docker-compose.prod.yml`
- [x] T003 [P] Add shared factory token authentication tests in `software-factory/src/test/java/com/simonrowe/factory/admin/FactoryTokenAuthenticatorTest.java`

## Phase 2: Foundational

- [x] T004 Implement shared constant-time token authentication in `software-factory/src/main/java/com/simonrowe/factory/admin/FactoryTokenAuthenticator.java` and adopt it in the existing review/feedback controllers
- [x] T005 [P] Add factory status wire-model tests in `software-factory/src/test/java/com/simonrowe/factory/admin/FactoryStatusServiceTest.java`
- [x] T006 Implement local configuration, Temporal task-queue, and schedule inspection in `software-factory/src/main/java/com/simonrowe/factory/admin/` with `GET /api/factory/status`
- [x] T007 [P] Add backend proxy client tests in `backend/src/test/java/com/simonrowe/factoryadmin/FactoryAdminClientTest.java`
- [x] T008 Implement bounded factory/deployer HTTP clients and wire records in `backend/src/main/java/com/simonrowe/factoryadmin/` and configuration in `backend/src/main/resources/application.yml`
- [x] T009 [P] Add admin authorization and controller contract tests in `backend/src/test/java/com/simonrowe/factoryadmin/FactoryAdminControllerTest.java` and `backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java`
- [x] T010 Implement role-protected status aggregation in `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminController.java` and `FactoryAdminService.java`

## Phase 3: User Story 1 - Understand the factory at a glance (P1) 🎯 MVP

**Goal**: Render truthful state for all six modules in the existing admin shell.

**Independent Test**: Mixed configured/poller/schedule/downstream states remain distinguishable and
partial failures do not hide healthy modules.

- [x] T011 [P] [US1] Add frontend API/type tests in `frontend/src/services/softwareFactoryApi.test.ts` and `frontend/src/pages/admin/SoftwareFactoryAdmin.test.tsx`
- [x] T012 [P] [US1] Add Software Factory route and Circuit Board navigation icon in `frontend/src/App.tsx` and `frontend/src/components/admin/AdminLayout.tsx`
- [x] T013 [US1] Implement status fetching and module-ledger UI in
  `frontend/src/services/softwareFactoryApi.ts` and
  `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`. No separate
  `frontend/src/types/softwareFactory.ts`: the types are the service's wire contract and have no
  second consumer, so a separate module would only add an indirection
- [x] T014 [US1] Implement responsive factory-rail styling and accessible states in `frontend/src/styles.css`

## Phase 4: User Story 2 - Turn vulnerability findings into owned work (P1)

**Goal**: Replace automatic repair with scheduled/manual Dependency-Track-to-Linear ticketing.

**Independent Test**: Findings group by PURL, follow Linear recurrence policy, and cause no git,
GitHub PR, or CI activity.

- [x] T015 [P] [US2] Rewrite workflow and activity tests for issue-only scanning in `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/`
- [x] T016 [P] [US2] Add manual scan controller/service tests in `software-factory/src/test/java/com/simonrowe/factory/cvefix/api/`
- [x] T017 [US2] Simplify scan request/result/progress and persisted run models in `software-factory/src/main/java/com/simonrowe/factory/cvefix/domain/` and `persistence/CveFixRunRecord.java`
- [x] T018 [US2] Rewrite `CveFixActivities` and `CveFixActivitiesImpl` to fetch all grouped findings and record runs only in `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/`
- [x] T019 [US2] Rewrite `CveFixWorkflowImpl` to file one Linear occurrence per component and remove agent/PR/CI execution in `software-factory/src/main/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowImpl.java`
- [x] T020 [US2] Implement token-protected manual scan start/progress endpoints in `software-factory/src/main/java/com/simonrowe/factory/cvefix/api/`
- [x] T021 [US2] Make first-created CVE schedule active while preserving existing pause state in `software-factory/src/main/java/com/simonrowe/factory/cvefix/schedule/CveFixScheduleInitializer.java`
- [x] T022 [US2] Delete retired CVE agent, GitHub PR, CI gateway, suppression, and git mutation classes/tests under `software-factory/src/{main,test}/java/com/simonrowe/factory/cvefix/`
- [x] T023 [US2] Add backend scan proxy and frontend **Scan now** action/progress in `backend/src/main/java/com/simonrowe/factoryadmin/` and `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`

## Phase 5: User Story 3 - Turn review feedback into a traceable proposal (P1)

**Goal**: File one source-PR Linear issue before generating guidance PRs and cross-link every PR.

**Independent Test**: Useful feedback creates one issue and repairable PR attachments; no-signal,
disabled, repeated, and partial-target cases do not duplicate work.

- [x] T024 [P] [US3] Add Linear issue-id and arbitrary-attachment contract tests in `software-factory/src/test/java/com/simonrowe/factory/linear/`
- [x] T025 [US3] Extend Linear filed-issue and attachment contracts in `software-factory/src/main/java/com/simonrowe/factory/linear/`
- [x] T026 [P] [US3] Add feedback workflow, PR body, persistence, and disabled-controller tests in `software-factory/src/test/java/com/simonrowe/factory/feedback/`
- [x] T027 [US3] Render structured feedback tickets and add the `feedback` producer policy in `software-factory/src/main/java/com/simonrowe/factory/feedback/` and `linear/config/LinearProperties.java`
- [x] T028 [US3] Sequence issue filing before distillation, carry the Linear reference into PRs, and repair PR attachments in `software-factory/src/main/java/com/simonrowe/factory/feedback/workflow/`
- [x] T029 [US3] Persist Linear issue and proposal-link state in `software-factory/src/main/java/com/simonrowe/factory/feedback/persistence/LearningRecord.java`
- [x] T030 [US3] Gate automatic and manual feedback starts on the enabled flag in `software-factory/src/main/java/com/simonrowe/factory/codereview/webhook/GitHubWebhookController.java` and `feedback/api/FeedbackController.java`
- [x] T031 [US3] Add backend feedback proxy and frontend closed-PR manual action/progress in `backend/src/main/java/com/simonrowe/factoryadmin/` and `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`

## Phase 6: User Story 4 - Run a guarded production redeploy (P2)

**Goal**: Redeploy only the matching current backend/frontend release with typed confirmation.

**Independent Test**: Unknown, mismatched, stale, wrongly confirmed, disabled, and unpolled states
start no workflow; one valid request reaches the existing fixed deploy workflow.

- [x] T032 [P] [US4] Add factory manual deploy endpoint tests in `software-factory/src/test/java/com/simonrowe/factory/deploy/api/DeployControllerTest.java`
- [x] T033 [US4] Separate manual deploy client availability from the automatic webhook flag and add internal endpoints in `software-factory/src/main/java/com/simonrowe/factory/deploy/api/`
- [x] T034 [P] [US4] Add backend commit/confirmation validation tests — landed in
  `backend/src/test/java/com/simonrowe/factoryadmin/FactoryAdminServiceTest.java` rather than a
  separate `FactoryAdminDeployTest`, since they exercise the same class as the readiness and
  error-translation tests and splitting them would duplicate the whole fixture
- [x] T035 [US4] Implement backend/frontend commit agreement and server-side confirmation in `backend/src/main/java/com/simonrowe/factoryadmin/FactoryAdminService.java`
- [x] T036 [US4] Implement the deploy confirmation dialog and progress rail in `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx` using `frontend/src/config/version.ts`

## Phase 7: User Story 5 - Keep platform backups active and runnable (P2)

**Goal**: Activate the nightly schedule and support dry-run/real manual capture without restore.

**Independent Test**: Fresh schedule active, prior pause preserved, concurrent requests rejected,
and manual modes have distinct visible outcomes.

- [x] T037 [P] [US5] Add platform-backup initializer and workflow-service tests in `software-factory/src/test/java/com/simonrowe/factory/platformbackup/`
- [x] T038 [US5] Make first-created platform-backup schedule active while preserving existing pause state in `software-factory/src/main/java/com/simonrowe/factory/platformbackup/schedule/PlatformBackupScheduleInitializer.java`
- [x] T039 [US5] Implement token-protected unique manual backup start/progress endpoints in `software-factory/src/main/java/com/simonrowe/factory/platformbackup/api/`
- [x] T040 [US5] Add backend backup proxy and frontend dry-run/confirmed real-backup actions in `backend/src/main/java/com/simonrowe/factoryadmin/` and `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`

## Phase 8: User Story 6 - Keep privileged controls server-side (P1)

**Goal**: Prove the browser and non-admin users cannot obtain credentials or start work.

**Independent Test**: Security tests deny signed-out/non-admin callers and built/browser traffic
contains no internal credentials while the factory nginx surface is unchanged.

- [x] T041 [P] [US6] Add secret-redaction and no-public-route regression tests in `backend/src/test/java/com/simonrowe/factoryadmin/` and `software-factory/src/test/java/com/simonrowe/factory/admin/`
- [x] T042 [US6] Normalize safe downstream errors and validate all module/action allowlists in `backend/src/main/java/com/simonrowe/factoryadmin/`
- [x] T043 [US6] Verify no factory credential is referenced by frontend source/build configuration in `frontend/src/` and keep nginx exact webhook routing in `config/nginx/nginx-proxy.conf`

## Phase 9: Polish and cross-cutting validation

- [x] T044 [P] Update factory architecture and runbooks in `docs/software-factory.md`, `docs/runbooks/software-factory.md`, `docs/runbooks/cvefix.md`, `docs/runbooks/linear.md`, and `docs/runbooks/platform-backup-restore.md`
- [x] T045 [P] Update operational facts in `CLAUDE.md` and project overview in `README.md`
- [x] T046 Run `./gradlew :software-factory:test` and resolve failures
- [x] T047 Run the backend test/checkstyle/coverage workflow and resolve failures
- [x] T048 Run frontend unit tests, lint, and production build and resolve failures
- [ ] T049 Run browser-driven admin verification against the local environment and capture the final result
  — **not done.** Deferred deliberately: the page's two upstreams are `software-factory:8090`
  and `deployer:8090`, neither of which runs in the local stack, so a local pass would only
  exercise the both-containers-unreachable path. That path is covered by
  `FactoryAdminServiceTest.reportsAllSixModulesEvenWhenNeitherContainerAnswers` and
  `SoftwareFactoryAdmin.test.tsx`. Verify on the Pi after deploy instead.
- [x] T050 Re-run `git diff --check`, validate spec acceptance scenarios, and mark all tasks complete in `specs/040-software-factory-console/tasks.md`

## Dependencies & Execution Order

- Setup -> Foundational -> all story phases -> Polish.
- US1 supplies the page shell used by US2-US5 actions.
- US2 and US3 both depend on the Linear contract but are otherwise independent.
- US4 depends on status aggregation and current build metadata, not on US2/US3.
- US5 depends on status aggregation and the existing deployer worker.
- US6 validates the complete surface and follows action implementation.

## Parallel Opportunities

- T002/T003, T005/T007/T009, and each story's test files can be authored independently.
- After T004-T010, factory workflow changes and frontend ledger work touch separate modules.
- Documentation T044/T045 is independent after behavior stabilizes.

## Implementation Strategy

1. Deliver the read-only status ledger as the independently testable MVP.
2. Replace CVE mutation with issue-only filing before enabling the new default.
3. Add feedback ticket/PR linkage.
4. Add guarded deploy and backup actions.
5. Complete the privilege-boundary tests, docs, full test suites, and browser pass.

## Verification record

Run on 2026-08-28 against the final working tree:

| Command | Result |
| --- | --- |
| `./gradlew :software-factory:check` | pass |
| `./gradlew :backend:test :backend:checkstyleMain :backend:checkstyleTest` | pass |
| `./gradlew :backend:jacocoTestReport :backend:jacocoTestCoverageVerification` | pass (0.78 floor) |
| `npm test -- --run` | 85 files, 658 tests, pass |
| `npm run lint` | 0 errors, 5 pre-existing `react-refresh` warnings |
| `npm run build` | pass |
| `git diff --check` | clean |

New tests added by this feature:

- `software-factory`: `FactoryTokenAuthenticatorTest`, `ModulePrerequisitesTest`,
  `FactoryStatusServiceTest`, `FactoryRunStatusServiceTest`, `FactoryAdminApiTest`,
  `FactoryPublicSurfaceTest`, `CveScanControllerTest`, `PlatformBackupControllerTest`,
  `PlatformBackupWorkflowServiceTest`, `DeployControllerTest`, plus new cases in
  `LinearGatewayWriteTest`, `LinearActivitiesImplTest` and `ReviewFeedbackWorkflowTest`.
- `backend`: `FactoryAdminClientTest`, `FactoryAdminServiceTest`, `FactoryAdminControllerTest`,
  plus five new cases in `SecurityConfigTest`.
- `frontend`: `tests/services/softwareFactoryApi.test.ts`,
  `tests/admin/SoftwareFactoryAdmin.test.tsx`.

## Follow-up added after review

- [x] T051 Add a manual code-review trigger (`POST /api/admin/software-factory/reviews` →
  the factory's existing token-protected `POST /api/reviews`), with dry-run and published modes,
  in `backend/src/main/java/com/simonrowe/factoryadmin/` and
  `frontend/src/pages/admin/SoftwareFactoryAdmin.tsx`

  Requested once the reviewer went out of action: the webhook path cannot replay a review,
  because its workflow id embeds the head SHA under `REJECT_DUPLICATE`. Reverses the original
  US1 decision that code review needed status only. The factory side needed **no change** — both
  `/api/reviews` endpoints and the `progress` query already existed, and the generic
  `GET /api/factory/runs/{id}` already follows review runs because `CodeReviewWorkflow.progress`
  shares the query name. Tests: 3 in `FactoryAdminClientTest` (chiefly that no `expectedHeadSha`
  is ever sent), 4 in `FactoryAdminServiceTest`, 3 in `FactoryAdminControllerTest`, 1 more path in
  `SecurityConfigTest`, 2 in `softwareFactoryApi.test.ts`, 4 in `SoftwareFactoryAdmin.test.tsx`.
