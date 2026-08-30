# Feature Specification: Log watch — production log health into Linear

**Feature Branch**: `042-factory-log-watch`

**Created**: 2026-08-29

**Status**: Draft

**Input**: User description: "Is there a module within the software factory that allows for logs to
be observed, and if there are actually issues then it will find them and raise a Linear ticket? For
example if they've got lots of error logs or warning logs. I do want to have a healthy system. It
would be good for such a thing to run about five minutes after deploy and also every 24 hours."

## Summary

A seventh Software Factory module, `logwatch`, on a new `logwatch` Temporal task queue. It reads
production container logs from Grafana Cloud Loki, groups them into distinct problems, and files
each one into Linear through the existing issue sink. It runs on a 24-hour schedule, five minutes
after every successful deploy, and on demand from the Software Factory admin console.

It is a **producer for the `linear` sink**, not a new filing mechanism. Everything about
deduplication, suppression and reopening is inherited from that sink and is deliberately not
reimplemented here.

## Scope

**In scope**: container logs shipped to Grafana Cloud Loki by Alloy, at severity `ERROR` and `WARN`.

**Out of scope**, and deliberately so:

- Container health, restart counts and exited containers. `scripts/monitor-prod.sh` already watches
  and remediates these every minute.
- HTTP probing of the public hostnames.
- Application-level signals the backend already holds (Langfuse guardrail scores, failed narrations,
  stuck article summaries).
- Any remediation whatsoever. This module observes and files. It never restarts, redeploys, edits
  code or opens a pull request.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A recurring production error becomes a ticket (Priority: P1)

A backend change introduces an exception that fires several times an hour. Within 24 hours — or
within five minutes of the deploy that introduced it — a Linear ticket appears in the `SIM` team
describing what is failing, in which container, how often, and over what window, with a link to the
matching Grafana query.

**Why this priority**: This is the entire feature. Without it, a fault that never takes the site
down and never trips a healthcheck stays invisible indefinitely.

**Independent Test**: Cause a repeated logged error in a non-production environment, run a scan
against that window, and confirm exactly one Linear issue is created carrying the fingerprint
attachment.

**Acceptance Scenarios**:

1. **Given** a signature occurring twice or more in the window, **When** a scan runs, **Then** one
   Linear issue is filed for it, carrying a fingerprint attachment.
2. **Given** the same signature still occurring, **When** the next scan runs, **Then** no second
   issue is created and the existing issue receives a comment with the updated counts.
3. **Given** a signature occurring exactly once in the window, **When** a scan runs, **Then**
   nothing is filed for it.
4. **Given** log lines differing only by timestamp, UUID, numeric identifier or file path, **When**
   they are grouped, **Then** they resolve to a single signature.

### User Story 2 - Accepted noise goes quiet permanently (Priority: P1)

An operator reads a filed ticket, decides the warning is expected and not worth fixing, and cancels
the ticket. No later scan files or comments on that signature again. If it is ever worth revisiting,
reopening the ticket resumes reporting.

**Why this priority**: With `WARN` in scope, the first runs will file boot noise. Without a
suppression gesture the module becomes something to ignore, which is worse than not having it.

**Independent Test**: File an issue via a scan, cancel it in Linear, re-run the scan against the same
window, and confirm nothing is filed or commented. Reopen it, re-run, and confirm a comment appears.

**Acceptance Scenarios**:

1. **Given** a cancelled issue for a signature, **When** a scan finds that signature again, **Then**
   nothing is filed and nothing is commented.
2. **Given** an issue closed as duplicate, **When** a scan finds that signature again, **Then** it is
   suppressed exactly as a cancellation is.
3. **Given** a cancelled issue that an operator reopens, **When** a scan finds that signature again,
   **Then** the reopened issue receives a comment.
4. **Given** a completed issue for a signature that later recurs, **When** a scan finds it, **Then** a
   new issue is filed and linked to the completed one.

### User Story 3 - A bad deploy is noticed in minutes, not tomorrow (Priority: P1)

A merge deploys an image that throws on startup or logs errors under normal traffic. Five minutes
after the deploy verifies green, a scan runs over the window since deploy completion and files what
it finds.

**Why this priority**: The 24-hour schedule alone means a fault introduced just after a scan is
invisible for almost a full day.

**Independent Test**: Complete a deploy in a test environment and confirm a scan workflow starts five
minutes later, bounded to the window beginning at deploy completion.

**Acceptance Scenarios**:

1. **Given** a deploy that reaches its success path, **When** it completes, **Then** a scan is
   scheduled to begin five minutes later over the window from deploy completion to scan time.
2. **Given** the post-deploy trigger is disabled, **When** a deploy completes, **Then** no scan is
   scheduled and the deploy is not delayed.
3. **Given** the scan trigger fails for any reason, **When** the deploy completes, **Then** the
   failure is recorded in the deploy's own progress and the deploy still reports success.
4. **Given** a deploy that fails and rolls back, **When** it completes, **Then** no scan is scheduled.

### User Story 4 - An operator checks what it would file, before it files anything (Priority: P2)

From `/admin/software-factory`, an operator starts a dry-run scan. It reads and groups logs, reports
what it would have filed in the run progress, and creates nothing in Linear.

**Why this priority**: The signature rules can only be validated against real production log lines.
A dry run is how they get validated without filing a first round of tickets to clean up.

**Acceptance Scenarios**:

1. **Given** a dry run, **When** it completes, **Then** its findings appear in run progress and no
   Linear issue is created or commented on.
2. **Given** a real run started from the console, **When** it completes, **Then** it behaves exactly
   as a scheduled run.

### Edge Cases

- **Loki is unreachable or rejects the credentials.** The run fails visibly in run progress. No other
  module is affected and no deploy is affected.
- **The log line budget is exhausted.** The scan reports how many lines it could not read. It never
  presents a truncated read as a complete one.
- **More distinct problems than the per-run cap.** The most severe and most frequent are filed and
  the ticket body states how many were dropped.
- **The language model call fails.** Filing proceeds using a deterministic title and body. A ticket
  must never be lost because a write-up could not be generated.
- **Linear filing is disabled.** The run completes and reports that nothing was filed. It does not
  stall waiting on a queue nothing polls.
- **A scan finds nothing.** The run completes reporting zero findings. Nothing is filed, and no
  "all clear" ticket is created.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST read container logs from Grafana Cloud Loki over a configurable window.
- **FR-002**: The system MUST consider log lines at `ERROR` and `WARN` severity, detected in a way
  that does not assume a single log format, because nginx, Kafka, ClickHouse and the JVM services
  each log differently.
- **FR-003**: The system MUST reduce log lines to a stable signature that is invariant to timestamps,
  UUIDs, hexadecimal identifiers, bare numbers, quoted file paths and terminal control codes.
- **FR-004**: The system MUST discard any signature occurring fewer than a configured minimum number
  of times in the window (default 2).
- **FR-005**: The system MUST order surviving signatures by severity first (`ERROR` before `WARN`),
  then by occurrence count, and file at most a configured maximum per run (default 5).
- **FR-006**: The system MUST state, in the filed ticket and in run progress, how many signatures were
  dropped by the cap and how many log lines went unread.
- **FR-007**: The system MUST file each signature through the existing `linear` task queue, using a
  fingerprint derived from the signature under a new `logwatch` producer.
- **FR-008**: The system MUST NOT change the existing fingerprint version, which would orphan every
  ticket already filed by the deploy and CVE producers.
- **FR-009**: The system MUST generate ticket titles and bodies with a single language-model call per
  run, and MUST fall back to a deterministic write-up if that call fails.
- **FR-010**: The system MUST run on a 24-hour Temporal schedule that is created active and preserves
  an operator's pause across restarts.
- **FR-011**: The system MUST run five minutes after a deploy reaches its success path, over the
  window beginning at deploy completion.
- **FR-012**: The post-deploy trigger MUST NOT be able to fail, delay or roll back a deploy.
- **FR-013**: The system MUST offer a manual run and a dry run from the Software Factory admin page.
- **FR-014**: A dry run MUST create and comment on nothing in Linear.
- **FR-015**: The system MUST report itself on the Software Factory status endpoint as a seventh
  module, including its missing prerequisites when it is enabled but cannot work.
- **FR-016**: The system MUST persist a run record per Temporal run id for the console to follow.

### Non-Functional Requirements

- **NFR-001**: Language-model cost MUST be bounded at one call per run, independent of log volume.
- **NFR-002**: The Grafana credentials MUST NOT be present in the `deployer` container, which holds
  the Docker socket. A build-time check MUST fail if any variable whose name contains `GRAFANA` is
  declared under that service.
- **NFR-003**: The activity implementation MUST be registered only in the `software-factory`
  container, by a class-level conditional evaluated by the component scanner.
- **NFR-004**: Signature derivation MUST be pure and unit-testable, with fixtures drawn from real
  production log lines.

## Key Design Decisions

These are the decisions that are load-bearing, with the reasoning that produced them.

- **Deduplication and suppression are delegated entirely to the `linear` sink.** The sink already
  fingerprints each distinct problem, resolves it through Linear's `attachmentsForURL`, and applies
  `open > (canceled or duplicate) > completed` precedence. That gives, with no new state: a repeat
  comments rather than re-files; a cancellation suppresses permanently; a reopen re-arms; and a
  regression files a fresh issue linked to the completed one. Tracking seen signatures in Mongo would
  be a second source of truth for something Linear already knows, cutting against the rule that
  `linear_issues` is an audit trail and never authoritative.

- **The filing bar is "any distinct signature", not a spike or a threshold.** Spike detection is blind
  to steady-state faults, and the two worst recent production incidents — thirteen inert Kafka
  consumers, and a `healthy` container with a dead listener — were both constant rather than spiking.
  A threshold guesses what an operator will tolerate; cancelling a ticket records what they actually
  decided.

- **Per-problem tickets, not one rolled-up report.** The consolidated `current-vulnerabilities` ticket
  works for CVEs because a vulnerability inventory genuinely is a set. Log errors are not: a new
  exception in one feature and a flaky Elasticsearch timeout are separate problems with separate
  fixes, and rolling them together buries whichever matters.

- **`WARN` is in scope, and the first runs will be loud.** Elasticsearch, Kafka and MongoDB all emit
  connection-retry warnings while the stack boots, and post-deploy scans will see them. The minimum
  occurrence filter and the per-run cap bound the volume; cancellation makes each one permanently
  quiet. This is an accepted, one-time cost, not a defect.

- **The signature function is the feature.** Everything else is plumbing around it. If it is too
  strict, every line looks new forever and the module is pure noise; if it is too loose, unrelated
  faults merge and a real problem hides inside an accepted one. It is a pure function over strings,
  so it carries the bulk of the test effort, and its fixtures must be real production log lines.

- **The module runs in `software-factory`, reading Loki over HTTP.** Loki holds history and the
  `container`, `image` and `service` labels; `docker logs` holds neither and would require the Docker
  socket. A Grafana read key is one more credential, and the container holding the socket must hold as
  few as possible — the same argument that keeps `LINEAR_API_KEY` off the `deployer`.

- **Two flags, not one.** `factory.logwatch.enabled` registers the activities and is declared only
  under `software-factory`; `factory.deploy.log-watch-trigger-enabled` gates whether a deploy
  schedules a scan and is declared only under `deployer`. A single flag would have registered a
  Loki-querying activity poller inside the socket-holding container. This mirrors
  `factory.deploy.enabled` against `trigger-enabled`, for the same reason.

- **The trigger flag travels on the deploy request.** A `@WorkflowImpl` cannot inject Spring
  properties, and the flag is the primary guard rather than the activity timeout: with the module
  disabled nothing polls the `logwatch` queue, so an unguarded schedule would stall the deploy until
  schedule-to-close instead of failing in milliseconds. The same pattern as `linearFilingEnabled`.

- **Both containers will register a workflow poller on the `logwatch` queue**, because `@WorkflowImpl`
  scanning is unconditional. This is harmless — a workflow only schedules activities — and is the same
  shape as the `deploy` queue. It should not be "fixed".

- **The post-deploy scan is diagnostic and cannot affect the deploy.** Its trigger activity is
  bounded by a short schedule-to-close, and its failure is recorded in the deploy's progress and never
  propagated. A log scan has no business failing a deploy that already verified green.

- **The language model writes, it does not decide.** Reading, grouping and the filing decision are
  deterministic and unit-tested. One call per run turns grouped signatures into readable prose, and
  filing proceeds without it when it fails.

## Success Criteria

- **SC-001**: A repeated production error results in exactly one Linear ticket, whichever trigger
  finds it first.
- **SC-002**: A signature an operator has cancelled produces no further tickets or comments.
- **SC-003**: A steady-state production week with no new faults produces zero new tickets.
- **SC-004**: A fault introduced by a deploy is filed within ten minutes of that deploy completing.
- **SC-005**: No scan run can fail, delay or roll back a deploy.
- **SC-006**: Every run reports its own truncation and cap losses; no run silently under-reports.

## Open Questions

- **Production log fixtures are not yet available.** The signature rules in FR-003 must be validated
  against real `ERROR` and `WARN` lines from the running stack. Until those are sampled, the default
  values for the minimum occurrence count and the per-run cap are estimates.
- **Whether `WARN` should remain in scope after the first month.** If cancellation volume proves
  unreasonable, narrowing to `ERROR` is a one-line configuration change and should be treated as an
  expected outcome rather than a failure of the design.
