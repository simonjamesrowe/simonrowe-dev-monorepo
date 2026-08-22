# Feature Specification: SonarQube Cloud static analysis and the PR quality loop

**Feature Branch**: `033-sonarqube-static-analysis` (implemented on the workspace branch `simonrowe/sonarqube-static-analysis`)

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Implement the design in docs/superpowers/specs/2026-08-21-static-analysis-design.md — activate SonarQube Cloud static analysis for the monorepo (fix the dead CI SONAR_TOKEN guard, add a sonar CI job, bring frontend coverage + ESLint into CI, add JaCoCo to software-factory, mirror jacocoExcludes as sonar coverage exclusions, runbook, operator checklist) plus the pr-review-loop skill in agent-setup."

**Source design**: `docs/superpowers/specs/2026-08-21-static-analysis-design.md`

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Static analysis actually runs on every pull request (Priority: P1)

The repository owner opens a pull request. Today, three of the four things they
believe are checking their code are not checking it: the Sonar analysis has never
executed once, the frontend linter has never executed in CI, and the
`software-factory` module — the one holding the GitHub App key and terminating
untrusted webhook traffic — has no coverage measurement at all. After this
change, opening a pull request produces a static-analysis result covering the
backend, the frontend and `software-factory`, and the owner can see it without
asking anyone.

**Why this priority**: This is the whole point of the change. Everything else is
either a prerequisite for it or a convenience layered on top. It also carries the
highest risk of quiet failure — the current state *looks* configured and does
nothing, which is worse than being visibly absent.

**Independent Test**: Open a pull request against `main` and confirm a
static-analysis job appears in the checks list, runs to completion, and reports a
result. Before the hosted account exists, confirm instead that the job runs,
skips the analysis step cleanly, and does not fail.

**Acceptance Scenarios**:

1. **Given** no analysis credential is configured on the repository, **When** a
   pull request is opened, **Then** the static-analysis job runs, skips the
   analysis step without error, and reports success — so this change can be
   merged before the hosted account exists.
2. **Given** an analysis credential is configured, **When** a pull request is
   opened, **Then** the analysis runs and reports results for backend Java code,
   frontend TypeScript code and `software-factory` Java code in a single project
   view.
3. **Given** an analysis credential is configured, **When** the analysis
   completes, **Then** the reported coverage figure for backend code agrees with
   the figure the existing backend coverage gate measures over the same code.
4. **Given** the analysis reports a failing quality gate, **When** the checks are
   evaluated for merge, **Then** the pull request is still mergeable — the gate is
   advisory in this change.

---

### User Story 2 - Frontend quality signals are measured, not just configured (Priority: P1)

The frontend has a lint configuration and 67 test files, and CI measures nothing
about either. The owner wants the linter to run and coverage to be produced, so
frontend code is held to the same visible standard as backend code.

**Why this priority**: Frontend coverage is a hard prerequisite for User Story 1
scenario 2 — without it, the analysis reports the frontend as entirely uncovered
and the number is misleading rather than useful. The linter is independent but
lands in the same job and is the cheapest quality win available.

**Independent Test**: Run the frontend coverage command locally and confirm a
non-empty coverage report is produced; run the lint command and record whether
the existing code passes.

**Acceptance Scenarios**:

1. **Given** the frontend test suite, **When** coverage is requested, **Then** a
   machine-readable coverage report is produced in a known, gitignored location
   and is non-empty.
2. **Given** the frontend job in CI, **When** it runs, **Then** the linter runs as
   a step and its result is visible in the job log.
3. **Given** the existing frontend code passes the linter cleanly, **When** the
   lint step is added, **Then** it is added as a blocking step.
4. **Given** the existing frontend code does **not** pass the linter cleanly,
   **When** the lint step is added, **Then** it is added as a non-blocking step,
   the violation count is reported to the operator, and no lint cleanup is
   performed as part of this change.
5. **Given** coverage is produced in the frontend job, **When** the
   static-analysis job runs, **Then** it can read that coverage report without
   re-running the frontend test suite.

---

### User Story 3 - `software-factory` coverage is measured for the first time (Priority: P2)

The owner wants to know what proportion of the `software-factory` module is
covered by tests, because it is the module most exposed to untrusted input and
the only one with no coverage measurement.

**Why this priority**: Valuable and independently deliverable, but the module's
Checkstyle gate already runs and the risk is a known-unknown rather than an
active failure. It also deliberately produces a *measurement*, not a gate, so it
cannot break anything.

**Independent Test**: Run the module's coverage report task and read the
resulting percentage.

**Acceptance Scenarios**:

1. **Given** the `software-factory` module, **When** its coverage report task
   runs, **Then** a machine-readable coverage report is produced.
2. **Given** the coverage report exists, **When** the static analysis runs,
   **Then** the module's coverage is included in the analysis alongside the
   backend's.
3. **Given** the measured coverage percentage is low, **When** CI runs, **Then**
   the build does not fail on it — no coverage floor is introduced for this module
   in this change.
4. **Given** the measurement completes, **When** the change is reported, **Then**
   the actual measured percentage is reported to the operator so a floor can be
   chosen in follow-up work.

---

### User Story 4 - The operator can complete the hosted setup without guesswork (Priority: P2)

Several steps cannot be performed from a development workspace: creating the
hosted organisation and project, installing the hosted service's GitHub App,
choosing the CI-based analysis method, and setting the repository secret. The
owner wants an ordered checklist and a runbook that says what runs where, how to
read the result, and what the known failure modes look like.

**Why this priority**: Without it, the platform work is inert — the analysis
never runs and nobody knows which of the five documented failure modes they are
in. It is P2 only because it is documentation and manual action rather than code,
and the code is safe to merge ahead of it.

**Independent Test**: A reader unfamiliar with the change can follow the runbook
end to end and reach a working analysis, and can diagnose each documented failure
mode from the symptoms listed.

**Acceptance Scenarios**:

1. **Given** the runbook, **When** an operator reads it, **Then** they can
   determine what analysis runs, where it runs, and how to read the gate result.
2. **Given** the runbook, **When** an operator observes a symptom matching one of
   the five documented failure modes, **Then** the runbook names the cause and the
   remedy.
3. **Given** the operator checklist, **When** the operator completes every step,
   **Then** the next pull request produces a decorated analysis result with
   coverage data.
4. **Given** any step in the checklist involves a credential, **When** the
   checklist is followed, **Then** no credential value is pasted into a chat,
   echoed to a terminal, or written to a file.

---

### User Story 5 - The post-PR loop is a named, repeatable procedure (Priority: P3)

Today the sequence *open pull request → wait for reviewer and CI → address
findings → push → wait again* is improvised on every pull request. Adding a third
signal makes improvisation worse. The owner wants one named procedure that owns
the loop, knows the traps in each signal, and stops after a bounded number of
attempts rather than looping forever.

**Why this priority**: It is a workflow improvement rather than a platform
capability, and the platform half delivers value without it. It is in scope
because the design deliberately lands both halves together — adding the Sonar
signal to an improvised loop is the specific problem being avoided.

**Independent Test**: Follow the procedure on a real pull request and confirm it
reaches a terminal state — either all signals green, or a report of what was
tried and what still fails — without manual improvisation.

**Acceptance Scenarios**:

1. **Given** work is ready for review, **When** the procedure runs, **Then** it
   first runs locally what CI will run, so a preventable failure costs seconds
   rather than a CI round trip.
2. **Given** the pull request is opened, **When** it is created, **Then** it is
   never opened as a draft, because the reviewer bot ignores drafts and would
   silently never review it.
3. **Given** the pull request is open, **When** the procedure waits for the
   reviewer's verdict, **Then** it reads the reviewer's pull-request comment
   rather than the formal reviews list, and treats silence as failure rather than
   as approval.
4. **Given** the reviewer posts nothing at all, **When** the procedure detects
   the silence, **Then** it hands off to the existing reviewer-triage procedure
   rather than proceeding as if approved.
5. **Given** the analysis reports findings, **When** the procedure triages them,
   **Then** it acts only on findings attributable to the new code in this pull
   request, and does not drag pre-existing debt into an unrelated change.
6. **Given** a finding the operator judges wrong or not worth fixing, **When** it
   is declined, **Then** the reason is stated in the pull request and the finding
   is **not** silenced in the analysis tool's own UI.
7. **Given** a finding that looks questionable, **When** the procedure addresses
   it, **Then** it verifies the finding rather than obeying it uncritically.
8. **Given** repeated failures, **When** roughly three fix-and-push iterations
   have been attempted, **Then** the procedure stops and reports what was tried
   and what still fails.
9. **Given** advisory checks that are allowed to fail, **When** the procedure
   evaluates CI, **Then** it does not treat those advisory failures as blocking.

---

### Edge Cases

- **The hosted account does not exist yet.** Every code change must be a no-op
  until the credential is present. This is the normal state at merge time.
- **The project key is already taken or differs from the configured key.** The
  configured key follows the account, not the reverse — the account is
  authoritative and the configuration is changed to match.
- **Automatic analysis is left enabled on the hosted side.** Analysis appears to
  work but reports no coverage. A half-working state, not a clean failure.
- **Shallow clone in the analysis job.** New-code attribution is silently wrong,
  because attribution is derived from source-control history.
- **The hosted service's GitHub App is not installed.** Analysis succeeds but
  nothing decorates the pull request, so results are invisible where they matter.
- **Coverage exclusion lists drift apart.** The backend excludes nine packages
  from its coverage view; if the analysis does not exclude the same nine, the two
  reported coverage percentages disagree over the same code and neither is
  trusted.
- **Frontend test files that live beside production source.** 58 of the 67
  frontend test files live in the dedicated test directory, but 9 live alongside
  the code they test. A naive split of "source here, tests there" misclassifies
  those 9 as production code, inflating the analysed surface and depressing
  coverage.
- **Analysis reads that require authentication.** The repository is public, so
  reading findings should not need a credential; the procedure must try
  unauthenticated first and only ask for a credential when refused.
- **A pull request with no reviewer comment.** Distinguishable from an approving
  comment only by knowing that silence means the reviewer failed.
- **A pull request that is re-pushed.** The reviewer re-reviews per pushed commit
  but posts one comment per pull request, so the procedure must not expect one
  comment per push.

## Requirements *(mandatory)*

### Functional Requirements

**Analysis activation**

- **FR-001**: The continuous-integration guard that decides whether to run the
  analysis MUST be able to evaluate to true when the credential exists. Its
  current form can never be true, so the analysis has never run.
- **FR-002**: The guard MUST preserve the existing "skip silently while no
  credential exists" behaviour, so this change alters no CI behaviour until the
  credential is added and is therefore safe to merge before the hosted account
  exists.
- **FR-003**: Static analysis MUST run in its own CI job that begins only after
  the backend and frontend jobs have completed, so it can consume their outputs.
- **FR-004**: The analysis job MUST obtain full source-control history, because
  new-code attribution is derived from it and a truncated history yields silently
  wrong attribution.
- **FR-005**: The analysis job MUST consume coverage reports produced by the
  earlier jobs rather than re-running any test suite, so the slowest work in CI is
  not duplicated.
- **FR-006**: The analysis MUST cover backend Java source, `software-factory`
  Java source and frontend TypeScript source within the existing single project.
- **FR-007**: The analysis MUST NOT be able to fail the CI run on quality-gate
  status. The gate is advisory in this change.
- **FR-008**: The quality gate MUST surface as its own non-required check on the
  pull request.

**Frontend quality signals**

- **FR-009**: The frontend test suite MUST be able to produce a machine-readable
  coverage report, on demand, into a gitignored location.
- **FR-010**: The frontend CI job MUST produce that coverage report and make it
  available to the analysis job.
- **FR-011**: The frontend CI job MUST run the existing lint configuration as a
  visible step.
- **FR-012**: The lint step MUST be blocking if and only if the existing frontend
  code passes it cleanly. If it does not, the step MUST be non-blocking and the
  violation count MUST be reported to the operator.
- **FR-013**: No frontend lint cleanup and no frontend coverage threshold MAY be
  introduced by this change. There is no baseline to derive a threshold from and
  cleanup is separate work.
- **FR-014**: Frontend test files MUST be classified as tests, not as production
  source, regardless of whether they sit in the dedicated test directory or beside
  the code they test.

**`software-factory` coverage**

- **FR-015**: The `software-factory` module MUST produce a machine-readable
  coverage report.
- **FR-016**: That report MUST be included in the analysis alongside the backend's.
- **FR-017**: No coverage floor MAY be introduced for `software-factory` in this
  change — report only, no verification rule.
- **FR-018**: The actual measured `software-factory` coverage percentage MUST be
  reported to the operator so a floor can be chosen in follow-up work.

**Coverage agreement**

- **FR-019**: The set of code excluded from the analysis's coverage view MUST
  mirror the set the backend already excludes from its own coverage view, so the
  two reported percentages agree over the same code.
- **FR-020**: The requirement to keep those two lists in step MUST be documented,
  since it is an accepted ongoing maintenance cost rather than an automated
  invariant.

**Documentation and manual setup**

- **FR-021**: A runbook MUST document what analysis runs, where it runs, how to
  read the gate, which steps are manual-only, and each documented failure mode
  with its symptom and remedy.
- **FR-022**: The runbook MUST follow the conventions of the existing runbooks in
  the repository.
- **FR-023**: An ordered operator checklist MUST cover every step that cannot be
  performed from a development workspace: creating or confirming the hosted
  organisation, creating the project under the existing key, installing the
  hosted service's GitHub App, selecting CI-based analysis and disabling automatic
  analysis, and setting the repository credential.
- **FR-024**: The checklist MUST be recorded in the runbook and also reported to
  the operator at the end of the change.
- **FR-025**: No credential value MAY be pasted into a chat, echoed, or written to
  a file at any point.

**The post-pull-request loop procedure**

- **FR-026**: A named, reusable procedure MUST own the sequence: local pre-flight
  → open the pull request → wait on all three signals → triage → fix → push →
  wait again, bounded → report.
- **FR-027**: The procedure MUST run locally, before opening the pull request,
  the same checks CI will run.
- **FR-028**: The procedure MUST defer to the existing backend-test procedure for
  backend build incantations rather than restating them.
- **FR-029**: The procedure MUST NOT open pull requests as drafts, and MUST record
  why.
- **FR-030**: Pull request title and body MUST follow this organisation's
  conventions: conventional-commit prefix, no ticket reference, no attribution to
  the assistant.
- **FR-031**: The procedure MUST wait on three distinct signals — CI checks, the
  reviewer bot's verdict, and the analysis findings — and MUST document the trap
  specific to reading each one.
- **FR-032**: The procedure MUST read the reviewer's verdict from its
  pull-request comment, not from the formal reviews list, and MUST treat absence
  of a comment as failure requiring handoff to the existing reviewer-triage
  procedure.
- **FR-033**: The procedure MUST identify which CI checks are advisory and MUST
  NOT treat their failure as blocking.
- **FR-034**: The procedure MUST attempt analysis reads without a credential first
  and request one only when refused.
- **FR-035**: The procedure MUST triage only findings attributable to the new code
  in the pull request under review.
- **FR-036**: The procedure MUST either fix a finding or decline it with a stated
  reason in the pull request, and MUST NOT silence a finding in the analysis
  tool's own UI.
- **FR-037**: The procedure MUST reference the existing code-review-receiving
  guidance so questionable findings are verified rather than obeyed.
- **FR-038**: The procedure MUST bound itself to roughly three fix-and-push
  iterations, then stop and report what was tried and what still fails.
- **FR-039**: The procedure's final report MUST state the pull request URL, the CI
  state, findings addressed, findings declined with reasons, and the gate status.
- **FR-040**: The procedure MUST state explicitly that its analysis-API behaviour
  is written against documented behaviour and is unverified until the hosted
  account exists.
- **FR-041**: The procedure MUST live in the repository where this organisation's
  procedures are maintained, not in this repository, so it is provisioned like
  every other procedure.

### Key Entities

- **Analysis project**: The single hosted project representing the whole
  monorepo, identified by a stable key. Holds the analysed source, the coverage
  figures, the findings and the gate result.
- **Quality gate result**: The pass/fail verdict of the analysis against its
  conditions, surfaced as a pull-request check. Advisory in this change.
- **Finding**: One reported issue, attributable either to new code in a pull
  request or to pre-existing code. Only new-code findings are in scope for
  triage.
- **Coverage report**: A machine-readable per-module artefact produced by a test
  run and consumed by the analysis. Three exist: backend, `software-factory`,
  frontend.
- **Coverage exclusion list**: The set of code deliberately omitted from a
  coverage percentage. Must be kept identical between the backend's own gate and
  the analysis, or the two percentages disagree.
- **Signal**: One of the three independent verdicts on a pull request — CI
  checks, reviewer bot comment, analysis findings and gate. Each has its own
  read mechanism and its own misreading trap.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A pull request opened before the hosted account exists produces a
  static-analysis job that completes successfully and changes no other check's
  result — zero CI behaviour change at merge time.
- **SC-002**: Once the operator checklist is complete, the first pull request
  after it produces an analysis result covering all three modules, with non-zero
  coverage reported for each of the three.
- **SC-003**: The coverage percentage the analysis reports for backend code and
  the percentage the backend's own gate reports over the same code differ by no
  more than one percentage point.
- **SC-004**: `software-factory` coverage moves from unmeasured to a known number,
  reported to the operator, with no build failure introduced.
- **SC-005**: The frontend linter executes on every pull request, having
  previously executed on none.
- **SC-006**: The frontend coverage report is produced on every pull request and
  is non-empty.
- **SC-007**: A quality-gate failure never blocks a merge in this change — zero
  pull requests blocked by gate status.
- **SC-008**: An operator following the runbook reaches a working, decorated
  analysis without needing information outside the runbook.
- **SC-009**: Each of the documented failure modes is diagnosable from its symptom
  using the runbook alone.
- **SC-010**: The post-pull-request loop reaches a terminal state — all signals
  green, or a report of what still fails — within roughly three fix-and-push
  iterations, with no unbounded waiting.
- **SC-011**: No credential value appears in any chat transcript, terminal output,
  or committed file.

## Assumptions

- **The hosted service is SonarQube Cloud, and hosting it ourselves is rejected.**
  The repository is public so the hosted tier is free with unlimited lines of code
  and includes pull-request decoration. Self-hosting is rejected because the
  production host already runs eight stateful services and boots with the memory
  cgroup controller disabled, so a memory-hungry analyser's OOM would take
  neighbouring containers with it.
- **The existing project key and analysis configuration block are preserved
  unchanged** where the hosted account permits it. If the key is unavailable, the
  configuration changes to match the account.
- **The advisory-gate decision is deliberate and temporary.** Making the gate
  blocking requires a baseline that does not exist yet, and is explicitly
  follow-up work.
- **The pre-existing debt the first analysis of the trunk surfaces is out of
  scope.** Its volume is unknown until the analysis runs, so triaging it is
  separate work with its own plan.
- **The loop procedure lives in the separate procedures repository**, per this
  repository's own instruction that such procedures are maintained there. Putting
  it in this repository was considered and rejected: it would be the only one
  outside that repository and would not be provisioned to the operator's
  environment. Consequently this change lands as two coordinated pull requests,
  one per repository, merged as a pair.
- **The analysis's own language rules are sufficient.** No additional Java
  analysers are introduced — the existing analysis covers that ground and three
  more analysers would buy overlap and noise. Frontend lint results are likewise
  not imported into the analysis, because the analysis already applies its own
  TypeScript rules to the same code and importing would duplicate findings.
- **Dependency and container vulnerability scanning are already owned elsewhere**
  and are untouched by this change.
- **End-to-end verification is impossible from a development workspace** — it
  needs the hosted account and the credential. The Gradle wiring, the frontend
  coverage report, the `software-factory` coverage report and the CI job graph are
  all verifiable locally or on the pull request itself; the hosted half is
  verified by the operator on the first pull request afterwards.
- **The frontend co-located test files are handled deliberately.** The source
  design counted all 67 frontend test files as living in the dedicated test
  directory; 58 do and 9 sit beside the code they test. Those 9 must be classified
  as tests, which the design's simple two-path split would not achieve on its own.

## Dependencies

- A SonarQube Cloud account and organisation under the `simonjamesrowe` GitHub
  identity — operator action, does not exist yet.
- The SonarQube Cloud GitHub App installed on the repository — operator action,
  required for pull-request decoration.
- A repository secret holding the analysis credential — operator action.
- The existing backend coverage pipeline and its exclusion list, which the
  analysis configuration must mirror.
- The existing reviewer bot and reviewer-triage procedure, which the loop
  procedure reads and hands off to.
- Write access to the separate procedures repository, for the loop procedure half.

## Out of Scope

- Triaging the pre-existing debt the first trunk analysis surfaces.
- Making the quality gate blocking.
- Introducing a `software-factory` coverage floor.
- Introducing a frontend coverage threshold.
- Cleaning up any frontend lint violations this change reveals.
- Additional Java analysers beyond the analysis's own rules.
- Importing frontend lint reports into the analysis.
- Dependency and container vulnerability scanning.
