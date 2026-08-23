---

description: "Task list for 033-sonarqube-static-analysis"
---

# Tasks: SonarQube Cloud static analysis and the PR quality loop

**Input**: Design documents from `/specs/033-sonarqube-static-analysis/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md — all present

**Tests**: No TDD tasks. This feature adds no application code, so there is nothing to unit-test. What it *does* have is a set of verification assertions defined in `quickstart.md` and `contracts/ci-artifact-contract.md`; those appear below as explicit verification tasks, because for a change of this shape they are the tests.

**Organization**: Grouped by user story. Both P1 stories are independently deliverable; US2 is sequenced before US1 within the P1 tier because US1 consumes the artifacts US2 produces (see Dependencies).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US5)
- Exact file paths in every description

## Path Conventions

Two repositories:

- **This monorepo**: `/Users/simonrowe/conductor/workspaces/simonrowe-dev-monorepo/washington-v1`
- **Skills repository**: `~/workspace/simonjamesrowe/agent-setup`

All monorepo paths below are repo-relative. Commands run from the repository root unless stated.

---

## Phase 1: Setup (Baseline Capture)

**Purpose**: Record the starting state, because three of this feature's claims are claims *about* the current state and must be evidenced before anything changes.

- [X] T001 [P] Capture the frontend lint baseline: run `npm run lint` from `frontend/`, record the exit code and the warning/error counts. Confirms the FR-012 precondition (exit 0 → the lint step lands blocking). If errors appear, STOP and re-plan against FR-012's non-blocking branch.
- [X] T002 [P] Confirm the dead guard in `.github/workflows/ci.yml`: verify the `SonarCloud analysis` step has `if: env.SONAR_TOKEN != ''` with `SONAR_TOKEN` declared only in that step's own `env:`, and that neither the job nor the workflow declares `env:`.
- [X] T003 [P] Confirm the frontend test-file split: count `frontend/tests/**` test files and `frontend/src/**/*.test.ts(x)` files. Expect 58 and 9. This is the correction to the source design recorded in research R6 — if the counts differ, the `sonar.exclusions` / `sonar.test.inclusions` patterns in T014 need revisiting.

**Checkpoint**: Baselines recorded. Any deviation from 58/9 or a non-zero lint exit code changes the plan, not just the tasks.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None. Recorded deliberately rather than omitted.

This feature has no shared prerequisite layer. There is no schema, no auth, no routing, no base entity — it persists nothing (data-model.md preamble) and adds no application code. Both Gradle plugins it needs are already in `gradle/libs.versions.toml` (`sonarqube = "6.0.1.5171"`, `jacoco = "0.8.12"`), `coverage/` is already in `.gitignore:69` and in `frontend/eslint.config.js`'s ignore list, and the `org.sonarqube` plugin is already applied at the root.

**Checkpoint**: Nothing to do. User story work begins immediately after Phase 1.

---

## Phase 3: User Story 2 — Frontend quality signals are measured (Priority: P1)

**Goal**: The frontend linter runs in CI for the first time, and the frontend test suite produces a machine-readable coverage report that a later job can consume.

**Independent Test**: Run `npm run test:coverage` locally and confirm `frontend/coverage/lcov.info` is non-empty; run `npm run lint` and confirm the recorded exit code. Neither needs Sonar, an account, or a credential.

**Why sequenced first**: US1 consumes the LCOV report this story produces. Delivering US1 first would leave `sonar.javascript.lcov.reportPaths` pointing at a file that does not exist — which Sonar treats as 0% coverage and does not report as an error (data-model invariant CR-1). That is the feature's likeliest silent failure, so the producer lands first.

- [X] T004 [US2] Add `@vitest/coverage-v8` at `^3.0.0` to `devDependencies` and a `"test:coverage": "vitest run --coverage"` script to `frontend/package.json`. The major must match the installed `vitest ^3.0.0` — a mismatched major refuses to load (research R9). Leave the existing `"test": "vitest run"` script unchanged so `npm test` keeps meaning what it means locally.
- [X] T005 [US2] Run `npm install` in `frontend/` to regenerate `frontend/package-lock.json` with the new devDependency.
- [X] T006 [US2] Add a `coverage` block to the existing `test:` section of `frontend/vite.config.ts`: `provider: 'v8'`, `reporter: ['text', 'lcov']`, `reportsDirectory: 'coverage'`. Do not touch the existing `exclude` entry that keeps Playwright specs in `e2e/**` out of Vitest.
- [X] T007 [US2] Verify per quickstart §1: run `npm run test:coverage` from `frontend/` and assert `frontend/coverage/lcov.info` exists and is non-empty. Confirm all 67 test files still run and still pass.
- [X] T008 [US2] In `.github/workflows/ci.yml`, add a blocking `Run lint` step to the `frontend` job invoking `npm run lint`, placed before the test step. **No `--max-warnings` flag** — adding one converts the 5 pre-existing `react-refresh/only-export-components` warnings into a red build, i.e. the ESLint cleanup that FR-013 puts out of scope.
- [X] T009 [US2] In `.github/workflows/ci.yml`, change the `frontend` job's test step to `npm run test:coverage` and add an `actions/upload-artifact@v4` step publishing `frontend/coverage/` as artifact `frontend-coverage`. Note `working-directory: frontend` is a job-level default but `upload-artifact` paths are workspace-relative, so the artifact path must be `frontend/coverage/`, not `coverage/`.

**Checkpoint**: The frontend job lints, measures coverage, and hands it to a later job. Independently valuable even if nothing else in this feature lands.

---

## Phase 4: User Story 3 — `software-factory` coverage is measured for the first time (Priority: P2)

**Goal**: Produce the first-ever coverage measurement for the module that holds the GitHub App key and terminates untrusted webhook traffic — as a **number**, not a gate.

**Independent Test**: Run `./gradlew :software-factory:jacocoTestReport` and read the percentage out of the XML. No Sonar, no account, no credential.

- [X] T010 [US3] Add the `jacoco` plugin to the `plugins {}` block of `software-factory/build.gradle.kts`, and a `jacoco { toolVersion = libs.versions.jacoco.get() }` block matching the pattern already used in `backend/build.gradle.kts`.
- [X] T011 [US3] Add a `tasks.jacocoTestReport` block to `software-factory/build.gradle.kts` with `dependsOn(tasks.test)` and `xml.required.set(true)` (plus HTML, matching backend). **Do NOT add `jacocoTestCoverageVerification`, do NOT wire anything into `tasks.check`, and do NOT add coverage exclusions** — FR-017 is report-only, and data-model invariant CE-2 records why no exclusion list is invented here.
- [X] T012 [US3] Verify per quickstart §3: run `./gradlew :software-factory:jacocoTestReport`, assert `software-factory/build/reports/jacoco/test/jacocoTestReport.xml` exists and is non-empty, then extract and record the actual INSTRUCTION coverage percentage. Confirm the build succeeds regardless of the number.
- [X] T013 [US3] In `.github/workflows/ci.yml`, add a `Generate JaCoCo report` step to the `software-factory` job running `./gradlew :software-factory:jacocoTestReport`, plus an `actions/upload-artifact@v4` step publishing `software-factory/build/reports/jacoco/` as artifact `software-factory-jacoco-report`. Place both after the existing `:software-factory:check` step.

**Checkpoint**: `software-factory` coverage moves from unmeasured to a known number, with zero risk of failing a build.

---

## Phase 5: User Story 1 — Static analysis actually runs on every pull request (Priority: P1) 🎯 MVP

**Goal**: Fix the guard that has never been able to evaluate true, and give the analysis its own job that consumes the three coverage artifacts without re-running any test suite.

**Independent Test**: `./gradlew sonar --dry-run` resolves; every path referenced by the `sonar {}` block exists after its producing task has run; and on the pull request for this change the `sonar` job runs, skips the analysis step cleanly, and does not fail.

- [X] T014 [US1] Extend the root `sonar { properties { } }` block in `build.gradle.kts` per `contracts/sonar-properties.md`: append the `software-factory` JaCoCo XML path to the existing `sonar.coverage.jacoco.xmlReportPaths`, and add `sonar.sources`, `sonar.tests`, `sonar.exclusions`, `sonar.test.inclusions`, `sonar.javascript.lcov.reportPaths` and `sonar.typescript.tsconfigPaths`. `sonar.tests` deliberately overlaps `sonar.sources` on `frontend/src`; the two filters are what keep every file indexed exactly once (data-model invariant MS-2). **Do NOT add `sonar.qualitygate.wait`** — invariant P-4.
- [X] T015 [US1] Add `sonar.coverage.exclusions` to the same block: the nine-entry mirror of `backend/build.gradle.kts`'s `jacocoExcludes`, **translated** from JaCoCo's class-file dialect into Sonar's source-file dialect — `**/`-anchored, with `.java` suffixes on the six single-type patterns. The exact list is tabulated in `data-model.md` §4. A literal copy of `jacocoExcludes` would match nothing while looking configured (research R7). Leave `backend/build.gradle.kts` untouched — it is the source of truth.
- [X] T016 [US1] Verify per quickstart §5 and §7: `./gradlew sonar --dry-run` resolves (expect `:sonar SKIPPED` alone, confirming no compile tasks are pulled in), and both exclusion lists still count nine entries.
- [X] T017 [US1] In `.github/workflows/ci.yml`, remove the `SonarCloud analysis` step from the `backend` job. Leave every other backend step, including the existing `jacoco-report` artifact upload, exactly as-is.
- [X] T018 [US1] In `.github/workflows/ci.yml`, add the new `sonar` job per `contracts/ci-artifact-contract.md`: `needs: [backend, frontend, software-factory]` (**all three** — the design said two, which races the `software-factory` artifact, research R10), `continue-on-error: true`, job-level `env: SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}`, `actions/checkout@v4` with `fetch-depth: 0`, JDK 21 + Gradle setup matching the other Java jobs.
- [X] T019 [US1] In the new `sonar` job, add three `actions/download-artifact@v4` steps with explicit `path:` values reconstructing the locations the Sonar properties expect: `jacoco-report` → `backend/build/reports/jacoco`, `software-factory-jacoco-report` → `software-factory/build/reports/jacoco`, `frontend-coverage` → `frontend/coverage`. `download-artifact` restores contents, not the original path prefix (invariant CR-2).
- [X] T020 [US1] In the new `sonar` job, add the analysis step: `if: env.SONAR_TOKEN != ''` running `./gradlew classes testClasses sonar`. The guard now works because the `env:` is job-level (research R1). `classes testClasses` is required and explicit because the `sonar` task pulls in no compile tasks (research R3) and sonar-java analyses bytecode. **Never `./gradlew build sonar`** — that re-runs the Testcontainers suite.
- [X] T021 [US1] In the new `sonar` job, add an unconditional path-assertion step **before** the analysis step, implementing assertions A-1 to A-4 of `contracts/ci-artifact-contract.md` (quickstart §6). It must run even when the analysis is skipped, so a broken artifact hand-off is visible in the log rather than silently degrading coverage to 0%.

**Checkpoint 🎯 MVP**: The analysis mechanism exists, is wired to real coverage from all three modules, is incapable of failing CI (guard + `continue-on-error`), and is inert until the operator adds the secret. This is the deliverable increment.

---

## Phase 6: User Story 4 — The operator can complete the hosted setup without guesswork (Priority: P2)

**Goal**: A runbook that says what runs where, how to read the gate, what only a human can do, and how to recognise each of the five documented failure modes.

**Independent Test**: A reader unfamiliar with the change can follow it end to end to a working analysis, and can diagnose each failure mode from its symptom without leaving the document.

- [X] T022 [US4] Create `docs/runbooks/static-analysis.md` following the conventions of the existing runbooks (`software-factory.md`, `dependency-track.md`, `cvefix.md`): what runs where (the four-job graph), which artifact feeds which property, how to read the gate, and an explicit statement that the gate is advisory and why.
- [X] T023 [US4] Add the ordered operator checklist to `docs/runbooks/static-analysis.md`, per FR-023: (1) sign in to SonarQube Cloud with GitHub and create/confirm the `simonjamesrowe` organisation; (2) create the project under the **existing** key `simonjamesrowe_simonrowe-dev-monorepo`, noting that if the key is unavailable the key in `build.gradle.kts` changes to match the account, not the reverse; (3) install the SonarQube Cloud GitHub App on the repository — no decoration without it; (4) set the analysis method to CI-based and **turn Automatic Analysis off**; (5) `gh secret set SONAR_TOKEN`. State that the token value is never pasted into chat, echoed, or written to a file (FR-025).
- [X] T024 [US4] Add the five failure modes to `docs/runbooks/static-analysis.md`, each as symptom → cause → remedy: Automatic Analysis left on (analysis appears to work, no coverage); shallow clone (new-code attribution silently wrong); GitHub App missing (analysis succeeds, no PR decoration); project key mismatch (analysis fails, project not found); Sonar and JaCoCo percentages disagreeing (the exclusion mirror has drifted). Add the measured fact from research R2 — a tokenless `sonar` run takes ~10 minutes then fails hard, which is why the guard matters — and the FR-020 maintenance obligation to keep `sonar.coverage.exclusions` in step with `jacocoExcludes`.
- [X] T025 [US4] Add one `Recent Changes` entry and one `Active Technologies` entry to `CLAUDE.md`, per repository convention. **Do not run `.specify/scripts/bash/update-agent-context.sh`** — it truncates the `Technical Context` values mid-sentence and deletes the `MANUAL ADDITIONS` block and prior `Recent Changes` entries. Hand-edit only.

**Checkpoint**: The manual half of the feature is documented and actionable.

---

## Phase 7: User Story 5 — The post-PR loop is a named, repeatable procedure (Priority: P3)

**Goal**: One named procedure owning open PR → wait on three signals → triage → fix → push → re-wait, bounded → report. Lands in the second repository.

**Independent Test**: Follow it on a real pull request and confirm it reaches a terminal state — all signals green, or a report of what was tried and what still fails — without improvisation.

- [X] T026 [US5] Create `~/workspace/simonjamesrowe/agent-setup/components/skills/pr-review-loop/SKILL.md` with frontmatter `name: pr-review-loop` and a `description` naming both the trigger (work ready for review on simonrowe.dev) and the three signals, following the single-file shape of `dependency-cve-fix/SKILL.md`.
- [X] T027 [US5] Write the pre-flight and PR-opening sections: run locally what CI will run (backend Checkstyle/test/coverage, `:software-factory:check`, `npm run lint`, `npm test`), **deferring to the `backend-test` skill for the Gradle incantations rather than restating them** (FR-028); then open the PR **never as a draft**, recording why — the reviewer bot ignores drafts so a draft is silently never reviewed, and drafts save no CI because `pull_request` fires for them anyway. Title and body per this org's conventions: conventional-commit prefix, no ticket reference, no attribution to the assistant.
- [X] T028 [US5] Write the three-signal wait section from `contracts/signal-reads.md`, giving each signal its correct read, its wrong-but-plausible read, and its terminal states: CI via `gh pr checks --watch` (with `evaluate` and `sonar` advisory, and `evaluate` frequently *absent* because it is `paths:`-filtered); the reviewer via `gh api .../issues/{pr}/comments` filtered to `simonrowe-code-reviewer[bot]` — **not** `/pulls/{pr}/reviews`, which is empty even on success — with silence meaning failure and handing off to the `code-review-triage` skill; and Sonar via `api/issues/search` and `api/qualitygates/project_status`, tried unauthenticated first because the project is public.
- [X] T029 [US5] Write the triage, bound and report sections: new-code findings only (FR-035); fix or decline **with a stated reason in the pull request**, never by resolving in the Sonar UI (FR-036); reference `superpowers:receiving-code-review` so questionable findings are verified rather than obeyed (FR-037); bound at roughly three fix-and-push iterations then stop and report (FR-038); note that the reviewer re-reviews per pushed commit but posts one comment per PR, so iteration count cannot be inferred from comment count; final report states PR URL, CI state, findings addressed, findings declined with reasons, and gate status (FR-039). State explicitly that the Sonar API calls are written against documented behaviour and are **unverified** until the account exists (FR-040).

**Checkpoint**: The loop has an owner. Second pull request ready.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T030 Run the full offline verification pass from `quickstart.md` §1–§8 end to end in one sitting, on the final state of the branch, and record each result. Evidence before claims.
- [X] T031 Re-read the complete `.github/workflows/ci.yml` diff and confirm all six assertions A-1 to A-6 of `contracts/ci-artifact-contract.md` hold, in particular A-5 (analysis step skips rather than fails while `SONAR_TOKEN` is unset) and A-6 (the `sonar` job cannot change the workflow conclusion).
- [X] T032 Confirm the deliberate omissions are all still absent: no `sonar.qualitygate.wait`, no `sonar.eslint.reportPaths`, no `--max-warnings`, no `software-factory` `jacocoTestCoverageVerification` and no `tasks.check` wiring, no frontend coverage threshold, and `backend/build.gradle.kts` untouched.
- [X] T033 Confirm no credential value appears anywhere in the diff, in any command output captured, or in any created file (FR-025, SC-011).
- [X] T034 Report to the operator: the measured `software-factory` coverage percentage from T012, the frontend lint result from T001 (exit code and warning count, with the five files named), the five-step operator checklist, and an explicit statement of what remains unverified until the account exists — the real analysis, server-side property interpretation, Sonar/JaCoCo agreement, PR decoration, and the skill's Sonar API calls.

---

## Dependencies

### Story completion order

```
Phase 1 (baselines)
   │
   ├──► US2 (P1, frontend)        ─┐
   ├──► US3 (P2, software-factory) ─┼──► US1 (P1, analysis job)  🎯 MVP
   │                                │
   ├──► US4 (P2, runbook) ──────────┘  (independent; documents US1)
   └──► US5 (P3, skill)                (independent; different repository)
                                            │
                                            ▼
                                   Phase 8 (polish)
```

### Why US2 and US3 precede US1

US1 is the consumer. `sonar.javascript.lcov.reportPaths` and the second entry in
`sonar.coverage.jacoco.xmlReportPaths` point at files that US2 and US3 produce. A
missing coverage report is **not an analysis error** — Sonar reports 0% and
carries on (invariant CR-1), so building the consumer first would produce a
green-looking analysis measuring nothing. US1 remains independently *deliverable*
(its own test is the dry-run, the path assertion and the CI job behaviour), but it
is not independently *meaningful*.

### Task-level dependencies

| Task | Blocked by | Reason |
| --- | --- | --- |
| T004–T006 | T001 | Lint baseline decides whether T008 lands blocking |
| T005 | T004 | Lockfile regeneration needs the new dependency declared |
| T007 | T005, T006 | Needs the dependency installed and configured |
| T009 | T007 | Do not publish an artifact whose producer is unverified |
| T012 | T010, T011 | Needs the plugin and the report task |
| T013 | T012 | Same reason as T009 |
| T014 | T003 | The 58/9 split determines the filter patterns |
| T016 | T014, T015 | Verifies both property additions |
| T019 | T009, T013 | Artifact names must match their producers exactly |
| T021 | T019 | Asserts on the downloaded paths |
| T024 | T012 | The failure-mode section cites the measured behaviour |
| T030–T034 | everything | Final verification pass |

### Parallel opportunities

**Phase 1** — all three are independent reads:

```
T001  (frontend lint baseline)
T002  (CI guard inspection)
T003  (test-file split count)
```

**Across stories** — three independent file sets, no shared file until the CI
workflow edits:

```
US2 → frontend/package.json, frontend/vite.config.ts
US3 → software-factory/build.gradle.kts
US4 → docs/runbooks/static-analysis.md, CLAUDE.md
US5 → agent-setup/.../pr-review-loop/SKILL.md   (different repository entirely)
```

**Serialisation point**: `.github/workflows/ci.yml` is touched by T008, T009,
T013, T017, T018, T019, T020 and T021 across three different stories. Those edits
must be serialised even though their stories are otherwise parallel.

**Fully parallel with everything**: T026–T029 (US5) share no file with the
monorepo half and target a second repository, so they can proceed at any point.

---

## Implementation Strategy

### MVP scope

**Phase 1 + US2 + US3 + US1** (T001–T021). That is the whole platform half: the
analysis runs, it measures all three modules with real coverage, and it cannot
fail CI. US4 makes it operable; US5 is a workflow improvement that delivers value
independently of all of it.

### Incremental delivery

1. **T001–T003** — establish the baselines. If the lint baseline is not exit 0, or
   the test split is not 58/9, stop and re-plan rather than proceeding on a stale
   assumption.
2. **US2, then US3** — the two coverage producers. Each is independently
   verifiable offline and independently valuable.
3. **US1** — the consumer. After this, the mechanism is complete and inert.
4. **US4** — documentation and the operator checklist.
5. **US5** — the second pull request, in `agent-setup`.
6. **Phase 8** — one full verification pass over the final state, then report.

### Two pull requests, merged as a pair

The monorepo half (US1–US4) and the skill half (US5) land as separate pull
requests because skills are maintained in `simonjamesrowe/agent-setup`. The
monorepo half is safe to merge first: it alters no CI outcome until the operator
adds the secret (SC-001).

### Where this can go wrong quietly

Worth holding in mind throughout, because none of these fails loudly:

- A coverage report that does not arrive → 0%, no error (CR-1).
- A `sonar.coverage.exclusions` entry in the wrong dialect → matches nothing, looks configured (R7).
- A co-located test file indexed as production source → inflated surface, depressed coverage (R6).
- A shallow clone → wrong new-code attribution, no warning (R11).
- A guard that fails *open* → ten minutes and a red job on every pull request (R2).

T021 and T030–T033 exist specifically to catch these.
