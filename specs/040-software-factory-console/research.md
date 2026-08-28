# Research: Software Factory Console

## Decision 1: Keep Temporal authority in software-factory

**Decision**: The browser calls admin-role-protected backend endpoints. The backend calls internal
factory HTTP endpoints with the existing factory trigger token. `software-factory` starts and
queries workflows; the backend does not gain a Temporal dependency.

**Rationale**: Workflow construction and feature configuration already live in the factory. This
keeps one source of workflow inputs, avoids duplicating Temporal configuration in the public API,
and keeps the credential out of browser assets and traffic.

**Alternatives considered**:

- Add a Temporal client to the backend: rejected because it duplicates workflow policy and gives
  the public API direct queue authority without buying a security boundary.
- Route factory endpoints through nginx: rejected because it widens the public surface and would
  expose a second authentication mechanism to the internet.
- Put Docker operations back in the backend: constitutionally prohibited and unnecessary.

## Decision 2: Report readiness from facts, not container health

**Decision**: A read-only internal status endpoint combines local feature configuration with
Temporal `DescribeTaskQueue` calls for workflow and activity pollers and schedule descriptions for
schedule state, recent action, and next action. The backend calls both `software-factory` and
`deployer` because their local flags differ.

**Rationale**: Actuator health has twice hidden missing workers in this system. Temporal 1.36.0's
service stub can describe a queue separately for workflow and activity task types, and its
schedule handle exposes `describe()`, including pause state and action times.

**Alternatives considered**:

- Infer worker state from configuration: rejected because a configured worker can fail to
  register.
- Report only Actuator/container health: rejected because it proves the web server, not queue
  execution.
- Ask the deployer to expose authenticated actions: rejected; its HTTP surface remains read-only
  and it continues to accept side effects only through Temporal.

## Decision 3: Replace CVE repair with component ticketing

**Decision**: Keep the established `cve-fix` queue and schedule identifiers for operational
continuity, but simplify the workflow to fetch all Dependency-Track findings, group by component
PURL, file one Linear occurrence per component, and persist a scan result. Delete the agent, git,
PR, and CI-repair execution path.

**Rationale**: Queue and schedule renames would strand operational knowledge and add migration
work with no user value. The package name can remain an internal legacy name while the UI and docs
use “Vulnerability scan.” PURL is already the Linear producer's stable identity; vulnerability
set changes become occurrence detail on the same owned issue.

**Alternatives considered**:

- Keep the fixer behind another flag: rejected because the requested model says no alternate path
  should still mutate the repository.
- One issue per advisory: rejected because several advisories commonly share one dependency bump
  and would fragment ownership.
- One issue per scan: rejected because it destroys stable recurrence and suppression semantics.

## Decision 4: Make ticket creation precede feedback PRs

**Decision**: After feedback lessons are harvested, file the source-PR Linear issue first. Pass its
identifier and URL into distillation so generated PR bodies reference it. Extend the Linear
activity contract to return the internal issue id and to attach arbitrary URLs idempotently after
PR creation.

**Rationale**: Issue-first sequencing ensures a PR never exists without its owning work item. The
existing `attachmentPending` repair pattern provides the model for surviving a split Linear
operation. Adding a nullable field to a serialized result is backward-compatible with old
histories; removing or reordering existing fields is avoided.

**Alternatives considered**:

- Create the issue after PRs: rejected because partial PR success could leave an unowned change.
- Put links only in Markdown comments: rejected because Linear attachments are queryable and
  visibly associated with the issue.
- One issue per lesson: rejected because the source PR is the natural idempotency boundary and its
  lessons often describe one correction.

## Decision 5: Default-on means active for new schedules, preservation afterwards

**Decision**: Feedback, CVE scanning, Linear, and platform-backup defaults become true on their
owning production compose service. Shared application defaults stay false so the same image cannot
enable a credentialed module in the wrong container. Newly created CVE and platform-backup
schedules are unpaused. Existing schedules continue to carry their server-side pause state through
reconciliation.

**Rationale**: This is the only interpretation in which “turned on” actually runs scheduled work,
while preserving the explicit operator gesture to pause an existing schedule.

**Alternatives considered**:

- Enable workers but create schedules paused: rejected because the feature remains inert and the
  page would immediately report the requested default-on state as paused.
- Force-unpause on every restart: rejected because it silently reverses incident response.

## Decision 6: Validate deploy against backend and frontend build commits

**Decision**: The frontend sends its compile-time commit and the currently displayed confirmation;
the backend compares that commit to its own build commit immediately before proxying the request.
Only an exact known match is eligible. Factory/deployer versions are displayed but do not gate the
target because deployer intentionally lags until manually recreated.

**Rationale**: The backend already knows its build commit and the frontend already embeds its own.
Their agreement proves the user-facing application release without adding a new frontend endpoint.
Rechecking at submission prevents a page opened before a deploy from submitting stale state.

**Alternatives considered**:

- Accept any typed SHA: rejected as too powerful for the requested recovery/rehearsal action.
- Gate on deployer version: rejected because deployer staleness is an accepted design property.
- Trust only the displayed browser value: rejected because it is client-controlled.

## Decision 7: Keep feature and schedule controls out of the UI

**Decision**: The page reports flags and paused state but does not edit environment variables or
pause/unpause schedules. It starts individual runs only.

**Rationale**: Runtime flags require container reconciliation and are deployment configuration;
pretending they are live toggles would be misleading. Schedule controls add an incident-response
surface not requested for this release.

**Alternatives considered**:

- Editable switches: rejected because a UI toggle cannot safely persist and reconcile the host
  environment without reintroducing deployment authority.
- Temporal pause buttons: deferred until there is a concrete use case and audit requirement.
