# Feature Specification: Software Factory Console

**Feature Branch**: `simonrowe/linear-agent-triggers` (Conductor-managed workspace branch)

**Created**: 2026-08-28

**Status**: Draft

**Input**: Add an authenticated Software Factory administration page that reports the real state
of every factory module and safely triggers selected workflows. Turn feedback, vulnerability
ticketing, Linear filing, and platform backup on by default. Feedback must create a detailed
Linear issue and link any resulting guidance pull requests. Vulnerability scans must create
Linear issues instead of attempting fixes. Code review remains automatic only, deployment keeps
strong manual safeguards, and a future Linear-to-fix agent is out of scope.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Understand the factory at a glance (Priority: P1)

The administrator opens a new **Software Factory** item in the administration navigation and sees
all six factory modules in one operational view. Each module reports whether it is configured to
run, whether a worker can accept work, how it is normally triggered, and whether its schedule is
active when it has one. A disabled, paused, unreachable, or misconfigured module must be described
as such rather than collapsed into a generic unhealthy state.

**Why this priority**: The current flags, worker pollers, and persistent schedules can disagree.
Without a truthful overview, manual trigger buttons create confidence without proving that
anything can execute them.

**Independent Test**: Open the page with a mixture of enabled, disabled, paused, and unreachable
modules and confirm each condition is distinguished and accompanied by a useful next action.

**Acceptance Scenarios**:

1. **Given** an authenticated administrator, **When** they open the administration console,
   **Then** a left-navigation item named **Software Factory** appears in the existing navigation
   style and opens the module overview.
2. **Given** all factory services are reachable, **When** the page loads, **Then** it shows code
   review, feedback, vulnerability scanning, deploy, Linear filing, and platform backup with their
   configuration, worker, trigger, and schedule state where applicable.
3. **Given** a module is configured on but has no worker able to perform its activities, **When**
   the page loads, **Then** it is shown as unable to execute rather than enabled or healthy.
4. **Given** a scheduled module is enabled but its schedule is paused, **When** the page loads,
   **Then** the paused state is shown independently from worker state.
5. **Given** the factory or workflow service cannot be reached, **When** the page loads, **Then**
   the rest of the administration console remains usable and the page identifies which status
   could not be obtained.

---

### User Story 2 - Turn vulnerability findings into owned work (Priority: P1)

Vulnerability scanning runs daily by default and can also be started from the administration
page. It reads the current Dependency-Track findings and creates or updates actionable Linear
one consolidated Linear issue for the repository. It does not edit dependencies, push a branch,
open a pull request, or wait for CI. The issue contains every current vulnerability with enough
component, recommendation, and source context for a future agent or a human to perform the repair.

**Why this priority**: The desired operating model is issue-first. Automatically changing a
dependency before work has been prioritised in Linear skips the intended ownership and workflow.

**Independent Test**: Supply findings for new, repeated, declined, and previously completed
component problems, run a scan, and confirm the correct Linear filing decision without any source
repository mutation.

**Acceptance Scenarios**:

1. **Given** vulnerability scanning and Linear filing are configured, **When** the daily scan runs,
   **Then** one structured Linear issue covers the repository's complete current vulnerability
   set across every affected component.
2. **Given** an administrator on the Software Factory page, **When** they choose **Scan now**,
   **Then** the same scan-and-file flow starts and the page shows its accepted workflow identity
   and progress.
3. **Given** multiple vulnerabilities affect one or more component versions, **When** a scan runs,
   **Then** they are grouped into the single repository report rather than split by advisory or
   component.
4. **Given** the repository already has an open vulnerability report issue, **When** findings
   recur or change, **Then** the existing issue receives a complete current snapshot and no
   duplicate issue is created.
5. **Given** a matching issue was cancelled or marked duplicate, **When** the problem recurs,
   **Then** the occurrence is suppressed in accordance with the existing Linear filing policy.
6. **Given** a matching issue was completed, **When** the problem recurs,
   **Then** a new regression issue is created and related to the completed issue.
7. **Given** any vulnerability scan, **When** it completes or fails,
   **Then** no dependency file, source branch, pull request, or CI run has been created by the
   vulnerability module.
8. **Given** Linear is unavailable or misconfigured, **When** a scan cannot file its findings,
   **Then** the run fails visibly with the findings retained in its durable workflow history rather
   than falsely reporting success or silently discarding them.

---

### User Story 3 - Turn review feedback into a traceable proposal (Priority: P1)

Feedback processing runs automatically by default when an eligible pull request closes and can be
started manually for a closed pull request. When useful lessons exist, the flow creates one Linear
issue that preserves the source review evidence, distilled guidance, proposed design, scope, and
acceptance criteria. If the flow can also produce one or more guidance pull requests, every pull
request is attached to that Linear issue.

**Why this priority**: Feedback currently produces repository changes without a first-class piece
of work that explains why they exist. The Linear issue supplies ownership, context, and a durable
place for future automation.

**Independent Test**: Process a closed pull request with qualifying feedback and confirm one
structured Linear issue is filed, any generated guidance pull requests are linked to it, and a
re-drive does not create duplicate issues or proposals.

**Acceptance Scenarios**:

1. **Given** feedback processing is enabled, **When** an eligible pull request closes,
   **Then** the conversation is harvested and useful lessons result in one Linear issue for that
   source pull request.
2. **Given** the harvested conversation contains no useful feedback signal, **When** processing
   completes, **Then** no Linear issue or guidance pull request is created.
3. **Given** useful lessons, **When** a Linear issue is created,
   **Then** it names and links the source pull request and contains evidence, proposed guidance or
   design changes, affected scope, and verifiable acceptance criteria.
4. **Given** the flow creates guidance pull requests, **When** each pull request is opened,
   **Then** its URL is attached to the Linear issue and the pull request references the Linear
   issue.
5. **Given** an administrator enters a valid closed pull-request number and starts feedback
   processing, **When** the request is accepted,
   **Then** the page shows the workflow identity and progress using the same behavior as the
   automatic path.
6. **Given** feedback has already completed successfully for a pull request, **When** the same
   pull request is submitted manually again,
   **Then** the page explains that it is already complete and no duplicate Linear issue or pull
   request is created.
7. **Given** the feedback feature flag is off, **When** a pull request closes or an administrator
   attempts a manual run,
   **Then** no feedback workflow starts and the page reports that the module is disabled.

---

### User Story 4 - Run a guarded production redeploy (Priority: P2)

The administrator can request a redeploy of the exact commit already reported as running in
production. The page explains what will happen, requires typed confirmation that includes the
commit identifier, and refuses arbitrary commits. The request uses the existing durable deploy
workflow and shows progress after acceptance.

**Why this priority**: A manual rehearsal and recovery trigger is valuable, but production deploy
is the highest-impact action on the page and must not become a generic one-click redeploy button.

**Independent Test**: Attempt the action with missing, incorrect, stale, and correct confirmation
values and verify only the exact currently-running commit can be accepted.

**Acceptance Scenarios**:

1. **Given** deploy execution is enabled and both running application versions agree on a commit,
   **When** the administrator enters the required confirmation and requests a redeploy,
   **Then** that exact commit is sent to the durable deploy workflow.
2. **Given** the confirmation does not exactly match the displayed phrase and commit,
   **When** the administrator submits it,
   **Then** no deploy request is sent.
3. **Given** the reported production versions disagree or cannot be read,
   **When** the administrator views deploy controls,
   **Then** the redeploy action is unavailable and the reason is shown.
4. **Given** deploy execution is disabled or has no activity worker,
   **When** the administrator views the module,
   **Then** the action is unavailable rather than placing work onto a queue that cannot execute.
5. **Given** a deploy request is accepted,
   **When** it runs,
   **Then** the page shows phase progress and links to the durable workflow record.

---

### User Story 5 - Keep platform backups active and runnable (Priority: P2)

Platform datastore backup is enabled by default and its nightly schedule is active at 02:00
Europe/London. The Software Factory page shows the next and previous scheduled runs and lets the
administrator start either a dry run or a real backup immediately. Restore remains a host recovery
operation and is not added to this page.

**Why this priority**: A backup implementation that remains paused is not protection. The page
also removes the need to reach for a shell when taking a pre-change backup.

**Independent Test**: Verify a fresh configuration has an active nightly schedule, then manually
run both dry-run and real modes and confirm their distinct outcomes are visible.

**Acceptance Scenarios**:

1. **Given** a fresh deployment with required backup credentials, **When** the factory starts,
   **Then** the platform backup worker is enabled and the nightly schedule is active rather than
   paused.
2. **Given** an existing schedule that an operator deliberately paused, **When** the factory
   restarts,
   **Then** the operator's pause decision is preserved and reported on the page.
3. **Given** the administrator selects **Dry run**, **When** the request completes,
   **Then** the capture plan is exercised without creating or uploading an archive.
4. **Given** the administrator confirms **Back up now**, **When** the request completes,
   **Then** a real archive is uploaded and the result is visible from the administration console.
5. **Given** a backup is already running, **When** another scheduled or manual request arrives,
   **Then** no concurrent capture starts and the page explains whether the new request was skipped
   or rejected.
6. **Given** a backup archive exists, **When** the administrator needs to restore it,
   **Then** the page directs them to the established host recovery procedure and offers no restore
   button.

---

### User Story 6 - Keep privileged controls server-side (Priority: P1)

Only a signed-in administrator can read factory operational details or trigger work. Factory
credentials and internal workflow authority never reach browser code, browser storage, URLs, or
user-visible errors. Code review remains visible for diagnosis but has no manual trigger.

**Why this priority**: The page can request production deployment and platform capture. Its
authentication and credential boundaries are part of the feature, not follow-up hardening.

**Independent Test**: Exercise every new endpoint signed out, with a non-admin identity, and as an
administrator; inspect browser requests and built assets to confirm no factory credential is
present.

**Acceptance Scenarios**:

1. **Given** a signed-out or non-admin user, **When** they request any Software Factory status or
   action endpoint, **Then** access is denied and no workflow is started.
2. **Given** an administrator uses the page, **When** browser traffic and storage are inspected,
   **Then** no factory trigger token, Linear credential, or workflow-system credential is present.
3. **Given** code review is healthy, **When** the administrator views its module,
   **Then** its automatic trigger and worker state are shown but no manual review action exists.
4. **Given** an internal downstream call fails with sensitive diagnostic material,
   **When** the page displays the failure,
   **Then** the message is actionable but contains no credential or secret value.

### Edge Cases

- A module's configuration says enabled while its worker has no activity poller.
- A Temporal schedule exists from an earlier configuration even though the corresponding feature
  is now disabled.
- A schedule is paused deliberately and the service restarts.
- A manual request is submitted twice because the administrator refreshes or double-clicks.
- The page loses connectivity after a workflow was accepted but before the response reaches the
  browser.
- Linear accepts an issue but attaching its fingerprint or pull-request link fails on the same
  attempt.
- A feedback run creates one guidance pull request and fails while creating a second.
- A pull request closes without being merged, is reopened, or already carries the feedback loop's
  skip label.
- Dependency-Track returns the same advisory multiple times or changes the advisory set for an
  already-known component.
- A vulnerability disappears and later returns after its issue was completed, cancelled, or
  marked duplicate.
- The reported backend and frontend production commits differ during or immediately after deploy.
- A manual backup collides with the nightly schedule or exceeds its expected duration.
- Required Linear, Dependency-Track, backup, or factory credentials are absent while a module's
  enable flag defaults on.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The administration console MUST include a left-navigation item named **Software
  Factory** using the existing administration navigation behavior and styling.
- **FR-002**: The Software Factory page MUST list exactly the six current modules: code review,
  feedback, vulnerability scanning, deploy, Linear filing, and platform backup.
- **FR-003**: Every module MUST report its configured state, workflow-worker availability,
  activity-worker availability, normal trigger, and most recent run outcome when those concepts
  apply.
- **FR-004**: Scheduled modules MUST additionally report schedule existence, paused or active
  state, next action time, previous action time, and overlap policy.
- **FR-005**: Status retrieval failures MUST be isolated per service or module and MUST NOT prevent
  the remaining statuses from rendering.
- **FR-006**: The page MUST provide links to the relevant durable workflow records without
  exposing internal credentials.
- **FR-007**: Code review MUST NOT expose a manual trigger in the administration page or its new
  administration API.
- **FR-008**: Feedback processing MUST default to enabled.
- **FR-009**: Feedback processing MUST retain a feature flag that disables both automatic and
  manual starts while leaving unrelated factory modules operational.
- **FR-010**: An administrator MUST be able to start feedback processing for a valid closed pull
  request.
- **FR-011**: A feedback manual start MUST be idempotent for a source pull request and MUST report
  an already-running or already-successful run without duplicating output.
- **FR-012**: A feedback run with useful lessons MUST create exactly one Linear issue identified
  by the source repository and pull-request number.
- **FR-013**: A feedback Linear issue MUST include the source pull-request link, relevant review
  evidence, distilled lessons, affected scope, proposed guidance or design, and testable
  acceptance criteria.
- **FR-014**: A feedback run MAY continue producing guidance pull requests using its existing
  bounded and allowlisted behavior.
- **FR-015**: Every guidance pull request created by a feedback run MUST reference its Linear issue,
  and its URL MUST be attached to that issue.
- **FR-016**: A partial feedback outcome MUST preserve links to every successfully created issue or
  pull request so a retry can repair missing relationships rather than duplicate work.
- **FR-017**: Vulnerability scanning MUST default to enabled.
- **FR-018**: Vulnerability scanning MUST retain a feature flag that disables scheduled and manual
  scans without disabling other modules.
- **FR-019**: The vulnerability schedule MUST run once every 24 hours and MUST be active by default
  on first creation.
- **FR-020**: A subsequent service restart MUST preserve an operator's existing paused or active
  schedule choice.
- **FR-021**: An administrator MUST be able to start an immediate vulnerability scan.
- **FR-022**: A vulnerability scan MUST read current Dependency-Track findings and group them by
  affected component and current vulnerability set.
- **FR-023**: Each actionable component group MUST create or update one Linear issue containing the
  package identity, component name and version, advisory identifiers, severity, recommendations,
  affected projects, scan time, and links back to available source findings.
- **FR-024**: Vulnerability issue identity MUST remain stable when Dependency-Track returns the
  same component findings in a different order.
- **FR-025**: Repeated, declined, and regressed vulnerability findings MUST follow the existing
  Linear issue filing precedence: open issues are updated, cancelled or duplicate issues are
  suppressed, and completed issues produce related regression issues.
- **FR-026**: The vulnerability module MUST NOT invoke a repair agent, modify a checkout, push a
  branch, open a pull request, or poll CI.
- **FR-027**: The system MUST remove or retire vulnerability-fix behavior that could mutate the
  repository so no alternate scheduled or manual path can still invoke it.
- **FR-028**: Linear filing MUST default to enabled and MUST remain independently disableable.
- **FR-029**: Linear filing MUST support feedback as a producer with its own configurable label and
  priority, in addition to the existing deploy and vulnerability producers.
- **FR-030**: Missing or invalid Linear configuration MUST be reported as a module configuration
  fault and MUST NOT prevent code review or the administration backend from starting.
- **FR-031**: Platform backup execution MUST default to enabled.
- **FR-032**: The platform backup schedule MUST run nightly at 02:00 Europe/London and MUST be
  active by default on first creation.
- **FR-033**: A subsequent service restart MUST preserve an operator's existing platform-backup
  pause choice.
- **FR-034**: An administrator MUST be able to start either a platform-backup dry run or a real
  backup.
- **FR-035**: Platform backup MUST prevent overlapping captures across scheduled and manual starts.
- **FR-036**: Platform restore MUST remain outside this page and MUST continue to use the
  established host recovery procedure.
- **FR-037**: Deploy execution and automatic deployment triggering MUST continue to default off and
  remain independently controlled.
- **FR-038**: The only manual deploy action in this release MUST redeploy the exact commit currently
  and consistently reported by the production application services.
- **FR-039**: Manual deploy MUST require a confirmation phrase containing the displayed commit
  identifier, validated again at the trusted server boundary immediately before starting work.
- **FR-040**: Manual deploy MUST be unavailable when production versions disagree, version status
  is stale or unavailable, deploy execution is disabled, or no deploy activity worker is present.
- **FR-041**: Accepted manual actions MUST return a durable workflow identifier and enough state for
  the page to follow progress without keeping the initiating request open.
- **FR-042**: Repeated submissions MUST use stable operation identities or conflict detection so
  double-clicking cannot create duplicate side effects.
- **FR-043**: All new status and action operations MUST require the existing administrator role.
- **FR-044**: Internal trigger tokens and third-party credentials MUST remain exclusively on the
  trusted server side and MUST never appear in browser code, responses, URLs, storage, or logs.
- **FR-045**: The public routing surface of the Software Factory service MUST NOT be widened by this
  feature.
- **FR-046**: The page MUST use the existing administration typography, spacing, colors, controls,
  responsive behavior, and icon system.
- **FR-047**: Status, warning, disabled, running, successful, and failed states MUST be conveyed by
  text and icon as well as color.
- **FR-048**: Every manual action MUST expose pending, accepted, running, completed, failed, and
  conflict feedback without requiring a full-page refresh.
- **FR-049**: Turning on feedback, vulnerability scanning, Linear filing, or platform backup by
  default MUST include startup validation that distinguishes missing prerequisites from a healthy
  enabled state, without taking unrelated modules down.
- **FR-050**: The future flow that consumes Linear issues, transitions them, changes dependencies,
  and opens repair pull requests MUST remain out of scope.
- **FR-051**: Administration controls for changing environment feature flags or pausing and
  unpausing schedules MUST remain out of scope; this release reports those states and triggers
  individual runs only.

### Key Entities

- **Factory Module Status**: One module's configured state, workflow and activity worker presence,
  trigger type, schedule state where applicable, last run, current run, and diagnostic message.
- **Manual Factory Run**: An administrator-requested workflow with module, mode, stable identity,
  requested time, accepted workflow identity, progress, terminal outcome, and safe error detail.
- **Vulnerability Ticket**: One Linear issue keyed by repository identity, containing the complete
  current vulnerability set and governed by the existing recurrence decision policy.
- **Feedback Ticket**: A Linear issue keyed by source repository and pull-request number,
  containing evidence, lessons, proposed design and acceptance criteria, with zero or more attached
  guidance pull requests.
- **Proposal Link**: A recoverable relationship between a feedback ticket and a generated guidance
  pull request, including whether attachment completed.
- **Factory Schedule Status**: The schedule identity, active or paused state, timing, previous and
  next action, and overlap behavior for a scheduled module.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An administrator can determine the configured, worker, trigger, and schedule state of
  all six factory modules from one page in under 30 seconds.
- **SC-002**: Every Dependency-Track finding appears in the consolidated Linear report on the next
  scheduled or manual scan, while repeated scans create zero duplicate issues.
- **SC-003**: Across all vulnerability scan tests and production runs, the vulnerability module
  creates zero source branches, dependency edits, pull requests, or CI repair loops.
- **SC-004**: Every feedback run with useful lessons creates exactly one source-PR Linear issue,
  and 100% of guidance pull requests produced by the run are cross-linked with that issue.
- **SC-005**: A fresh production configuration schedules platform backup for 02:00 Europe/London
  without a separate unpause step, while a deliberately paused existing schedule stays paused
  across restart.
- **SC-006**: Unauthorized and non-admin requests start zero workflows, and browser-delivered
  assets and traffic contain zero factory or third-party secret values.
- **SC-007**: A manual deployment cannot be accepted for an arbitrary, stale, unavailable, or
  inconsistently reported commit in automated and browser-driven tests.
- **SC-008**: Every accepted manual action displays its durable workflow identity within five
  seconds and reaches a visible terminal state without holding the original request open.
- **SC-009**: The Software Factory page remains usable at mobile and desktop widths, all actions
  are keyboard operable, and no operational state is communicated by color alone.
- **SC-010**: A missing Linear, Dependency-Track, backup, or factory prerequisite produces an
  actionable module-level status while code review and unrelated administration pages remain
  available.

## Assumptions

- The single intended user is an Auth0 identity carrying the existing administration role.
- "Default on" means the owning production container's composition default is enabled when the
  corresponding environment variable is absent. Application-level defaults remain fail-closed
  because the same image also runs as a differently privileged container. An explicit false value
  still disables the module.
- "Platform backup turned on" means a newly-created nightly schedule is active, not merely present
  and paused. An existing operator pause remains authoritative across restarts.
- Vulnerability issue granularity is one Linear issue for the repository. Every scan posts a
  complete current snapshot, regardless of which components or advisories changed.
- Feedback issue granularity is one Linear issue per source pull request, not one issue per lesson
  or target repository.
- Feedback continues to generate its existing allowlisted guidance pull requests when the
  distillation can make a concrete change; ticket creation does not replace that capability.
- Manual feedback accepts a closed pull-request number in the configured repository; arbitrary
  repository selection is unnecessary for this administration console.
- Manual vulnerability scan files real Linear issues. The existing Linear-wide dry-run setting
  remains the operational mechanism for testing without writes.
- Schedule pause and unpause controls, environment editing, platform restore, manual code review,
  arbitrary-SHA deploy, and a Linear-triggered repair agent are outside this release.
- Existing Temporal workflow history and the software-factory Mongo audit records remain the
  durable operational record; the administration page does not introduce a competing history
  database.
- The Linear team, Triage state, labels, API credential, Dependency-Track credential, and Google
  Drive backup credential are deployment prerequisites and may be validated without exposing
  their values.
