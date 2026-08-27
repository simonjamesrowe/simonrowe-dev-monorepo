# Feature Specification: Auto-deploy on merge

**Feature Branch**: `036-auto-deploy-on-merge`

**Created**: 2026-08-26

**Status**: Draft

**Input**: Approved design at `docs/superpowers/specs/2026-08-26-auto-deploy-on-merge-design.md`

A merge to `main` should end with the change live in production, without a human
running anything. Today the image build is automated and the last mile is not:
three container images are published on every merge and nothing deploys them. The
gap has already caused one recorded incident — the automated code reviewer ran a
months-old image because its published image was never deployed.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A merged change reaches production by itself (Priority: P1)

The maintainer merges a reviewed pull request and does nothing else. Once the
publish build finishes, production picks up exactly that commit: the new
application images and the host-side configuration from the same commit. While
the swap happens, visitors see a branded "update in progress" page rather than a
broken site or a raw proxy error. Afterwards the maintainer can see, in a durable
record, which commit was deployed, when, and whether each phase passed.

**Why this priority**: This is the feature. Everything else exists to make this
safe. Without it the maintainer must notice a finished build and act manually,
which is exactly the step that has already been forgotten for months at a time.

**Independent Test**: Merge a trivial change to `main` and, with no further human
action, confirm within a bounded time that the public site and API serve the new
build, the deployed commit matches the merge commit, and a deploy record exists
naming that commit with every phase passed.

**Acceptance Scenarios**:

1. **Given** a successful publish build for a commit on `main`, **When** the
   build completes, **Then** a deploy of that exact commit begins without human
   action.
2. **Given** a deploy is running, **When** a visitor loads the public site or
   API, **Then** they receive the branded maintenance page with a retry hint,
   not a proxy error and not a partially-updated site.
3. **Given** a deploy completed successfully, **When** the maintainer inspects
   the deploy history, **Then** they see the deployed commit, the trigger, the
   outcome of every phase, and the verification results.
4. **Given** a deploy completed successfully, **When** the public site and API
   are requested, **Then** both respond normally and the maintenance page is
   gone.
5. **Given** a publish build that failed, was for a branch other than `main`, or
   was a different build, **When** its completion is reported, **Then** no
   deploy is started.

---

### User Story 2 - A bad deploy undoes itself and explains why (Priority: P1)

A merge turns out to break production. Verification fails, and rather than
leaving the site broken until a human notices, the system puts the previous
known-good version back — both the images and the host-side configuration — and
verifies the restored version the same way. It then produces a written diagnosis
from the captured evidence and reports it where the maintainer will see it,
attached to the commit that broke and as a tracked issue. If the restore itself
does not verify, the maintenance page deliberately stays up rather than exposing
a broken site.

**Why this priority**: Automating the last mile removes the human who would have
caught a bad deploy. Rollback is what makes the automation acceptable, so it
ships with the trigger, not after it.

**Independent Test**: Deploy a commit whose build is deliberately broken and
confirm that production returns to the previous version, the restored version
verifies, and a diagnosis naming the failing component is posted to the commit
and raised as an issue — all without human action.

**Acceptance Scenarios**:

1. **Given** a deploy whose verification fails, **When** the failure is
   detected, **Then** the maintenance page is re-asserted, the previous images
   and the previous host configuration are restored, and the restored version is
   verified.
2. **Given** a rollback that verified clean, **When** it finishes, **Then** the
   maintenance page comes down and the site serves the previous version.
3. **Given** a rollback that itself failed verification, **When** it finishes,
   **Then** the maintenance page stays up and the failure is reported.
4. **Given** any failed deploy, **When** reporting runs, **Then** a written
   diagnosis based on the failing component's logs, the container states and the
   commit range is attached to the deployed commit and raised as an issue.
5. **Given** a restored deploy, **When** the automatic health watchdog next
   runs, **Then** it does not re-introduce the broken version.

---

### User Story 3 - Host-side configuration deploys with its own commit (Priority: P2)

Some of what production runs is not inside an image: the service definitions, the
proxy configuration and the operational scripts are read from the deploy
directory on the host. A merge that changes those must not deploy half way. So
the deploy advances the host's checkout to the same commit the images came from,
and it refuses to do so unless that is provably safe: the checkout has no local
modifications, the target commit is genuinely on the mainline, the move is a
fast-forward only, and every service the change would affect is one the system is
permitted to recreate.

**Why this priority**: Without it the feature is not end-to-end and would report
success on a half-applied change — the failure mode the design is most concerned
with. It is P2 only because an images-only deploy is still useful and is the
documented fallback when the checks decline.

**Independent Test**: Point the flow at a scratch checkout and confirm each fence
independently: a locally-modified checkout is refused, a commit not on the
mainline is refused, the target commit is used rather than the newer mainline tip,
and a change affecting a service outside the permitted set leaves the checkout
untouched.

**Acceptance Scenarios**:

1. **Given** a clean checkout and a target commit on the mainline, **When**
   configuration sync runs, **Then** the checkout advances to exactly that
   commit — not to a newer mainline tip — by fast-forward only.
2. **Given** a checkout with locally modified tracked files, **When**
   configuration sync runs, **Then** the checkout is left untouched, the deploy
   continues with images only, and the record says so.
3. **Given** a target commit that is not an ancestor of the fetched mainline,
   **When** configuration sync runs, **Then** the sync is refused and the
   checkout is left untouched.
4. **Given** a configuration change that would affect a service outside the
   permitted-to-recreate set, **When** configuration sync runs, **Then** the
   checkout is left untouched, the deploy proceeds with images only, and the
   report names both the held-back services and the manual command to apply
   them.
5. **Given** a configuration change referencing an environment variable the host
   does not define, **When** configuration sync runs, **Then** the deploy reports
   the missing variable rather than leaving the host unable to run further
   service commands.
6. **Given** an untracked or ignored file on the host (such as its local
   environment file), **When** configuration sync runs, **Then** its presence
   does not block the sync and it is never overwritten.

---

### User Story 4 - An unplanned outage looks intentional (Priority: P3)

Independently of deploys, when a public service is down the visitor currently
gets a raw proxy error. Instead they should get a branded "temporarily
unavailable" page that matches the site, states what is happening, and retries
itself. Operational tooling — the container console, the workflow UI, the
observability tools and the health endpoint — must stay reachable throughout,
because that is how the outage gets fixed.

**Why this priority**: A real improvement that is nearly free once the
maintenance page exists, but the site works without it.

**Independent Test**: Stop a public upstream with no deploy in progress and
confirm the visitor gets the branded unavailable page while every operational
hostname and the health endpoint still respond normally.

**Acceptance Scenarios**:

1. **Given** no deploy in progress and a stopped public upstream, **When** a
   visitor loads that hostname, **Then** they get the branded unavailable page,
   not a raw proxy error.
2. **Given** a deploy in progress, **When** the operational hostnames are
   requested, **Then** they respond normally and are not shown the maintenance
   page.
3. **Given** a deploy in progress, **When** the proxy's own health endpoint and
   the webhook receiving endpoint are requested, **Then** both respond normally.
4. **Given** either page is served, **When** it renders, **Then** it needs no
   external asset to look correct and it tells the visitor when to retry.

---

### User Story 5 - The public API no longer holds host-level power (Priority: P2)

The existing self-redeploy capability inside the public application is removed
entirely, along with the host access it required. The container that serves the
public API stops holding host-level container control and stops carrying a copy
of the deployment configuration and the environment secrets. The admin interface
loses the redeploy control, because the capability no longer exists.

**Why this priority**: It is a consequence of this feature rather than its
purpose, but leaving both paths in place would keep the risk while adding a
second mechanism. It ships in the same change.

**Independent Test**: Confirm the redeploy request is no longer served, the admin
interface no longer offers it, and the public application no longer has host
container access or a copy of the deployment configuration.

**Acceptance Scenarios**:

1. **Given** the change is deployed, **When** the old redeploy request is made,
   **Then** it is not served.
2. **Given** the admin interface, **When** the maintainer views the operations
   page, **Then** no redeploy control is offered and every remaining operation
   still works.
3. **Given** the public application, **When** its granted host access is
   inspected, **Then** it has no host container control, no deployment
   configuration copy and no environment-secret copy.

---

### User Story 6 - Nothing changes until an operator opts in (Priority: P1)

Merging this feature must not, by itself, start deploying anything. Both halves —
receiving the trigger and executing the deploy — are independently switchable and
both start disabled. The operator can enable the executor, exercise a full deploy
by hand against the version already running, and only then enable the trigger.
Each half can be silenced without silencing the other, so a broken deploy executor does
not take the automated code reviewer down with it.

**Why this priority**: The first automated deploy must be a deliberate act. This
is also what makes the change reviewable and mergeable without a production
freeze.

**Independent Test**: Merge with defaults, confirm production behaviour is
unchanged and no deploy occurs on the next merge; then enable the executor alone
and confirm a hand-started deploy runs end to end while automatic triggering
still does nothing.

**Acceptance Scenarios**:

1. **Given** default settings, **When** a publish build for `main` succeeds,
   **Then** nothing is deployed and nothing errors.
2. **Given** the executor enabled and the trigger disabled, **When** a deploy is
   started by hand for the version already running, **Then** every phase runs
   and production is unchanged.
3. **Given** the executor disabled, **When** the trigger is enabled and a build
   completes, **Then** the request is durably queued rather than lost, and the
   automated code reviewer continues to work.
4. **Given** the executor is enabled, **When** the operator checks it, **Then**
   they can confirm it is actually listening for work and not merely running.

---

### Edge Cases

- **Duplicate notification of the same finished build** — must result in one
  deploy, not two.
- **Two merges minutes apart** — must result in one deploy, of the newer commit,
  rather than two overlapping deploys of the same services. The earlier commit
  deliberately gets no deploy run of its own.
- **Executor restarted mid-deploy** — the deploy restarts the component that
  received the trigger, so the in-flight deploy must survive that restart and
  resume, not be lost.
- **A phase replaces the very script that later phases run** — later phases must
  pick up the new version; the already-running phase must be unaffected.
- **A retried phase** — every phase must be safe to run again with the same
  result, since phases are retried automatically.
- **Rollback when the broken change was the deploy script itself** — the restore
  must run the previous version of the script, not the broken one.
- **The automatic health watchdog running during or just after a deploy** — it
  must not silently change which application version is running, neither during
  a deploy nor after a rollback.
- **The proxy restarting mid-deploy** — a brief window with nothing serving at
  all is accepted, but the health endpoint must not be made to fail in a way
  that takes the outbound tunnel and therefore every hostname offline.
- **The verification check treating the maintenance response as a failure** —
  the flag-sensitive part of verification must run only after the flag is
  cleared, or every deploy fails.
- **A newly-added service that nobody has classified** — must be held for a
  human by default rather than recreated automatically.
- **The executor's own definition changing** — the executor never recreates
  itself; keeping it current is a documented manual step.
- **A configuration change needing a new host environment variable** — must be
  reported and must not leave the host in a state where further service commands
  fail.
- **The trigger arriving for a repository that is not this one** — ignored.
- **An unsigned or malformed trigger** — rejected before anything else happens.

## Requirements *(mandatory)*

### Functional Requirements

#### Trigger and coalescing

- **FR-001**: The system MUST start a production deploy when, and only when, it
  is notified that the publish build named `Publish` completed with a successful
  conclusion on branch `main` for an allowlisted repository.
- **FR-002**: The system MUST ignore, with the existing accepted-but-ignored
  response, any notification that fails any of those conditions.
- **FR-003**: The system MUST continue to reject unsigned and malformed
  notifications before evaluating them, exactly as today.
- **FR-004**: The system MUST NOT use pull-request merge as the trigger, because
  merge precedes image availability and would deploy the previous build while
  reporting success.
- **FR-005**: The system MUST deploy the exact commit the notification names,
  identified by commit SHA rather than by a moving tag.
- **FR-006**: Concurrent or repeated deploy requests MUST coalesce into a single
  in-flight deploy carrying the newest requested commit; duplicate notifications
  MUST therefore be idempotent.
- **FR-007**: The deploy request MUST be delivered to the executor over a durable
  channel that survives the executor process being restarted, and MUST NOT
  require any new network-reachable endpoint or shared token.

#### Deploy execution

- **FR-008**: The deploy MUST run as an ordered sequence of individually
  retryable phases: sync configuration, maintenance page on, pull images,
  recreate services, verify, maintenance page off, verify public.
- **FR-009**: Every phase MUST be safe to run more than once with the same
  outcome.
- **FR-010**: The deploy MUST be performed by the same single script that a human
  uses by hand; the human path MUST keep behaving exactly as it does today when
  invoked with no phase argument.
- **FR-011**: Invoking the script with no phase argument MUST NOT touch the
  host's checkout; configuration sync MUST be opt-in.
- **FR-012**: The set of services whose images are pulled MUST be configurable
  without rebuilding anything, defaulting to the three published application
  images.
- **FR-013**: The image version deployed MUST be resolved in a way that every
  other command on the host also resolves, so that an automatic reconcile
  elsewhere cannot silently substitute a different version.
- **FR-014**: An automatic reconcile performed by the health watchdog MUST NOT
  change which application version is running.
- **FR-015**: The component executing the deploy MUST never recreate itself.
- **FR-016**: The component executing the deploy MUST have no public ingress and
  MUST NOT be the component that terminates untrusted internet traffic.
- **FR-017**: The executing component MUST NOT be granted the full production
  environment-secret set; it MUST declare only what it needs.

#### Verification

- **FR-018**: Verification MUST confirm every recreated service reaches a healthy
  state, waiting for each service's own health signal rather than for the proxy.
- **FR-019**: Verification MUST confirm the operational hostnames respond, and
  MUST do so while the maintenance page is still up.
- **FR-020**: Verification of the public hostnames MUST run only after the
  maintenance page is cleared, so that the maintenance response is never
  mistaken for a failure.
- **FR-021**: A failure in either verification stage MUST enter the rollback
  path, re-asserting the maintenance page first.

#### Configuration sync

- **FR-022**: The deploy MUST advance the host's checkout to the same commit the
  images came from, before any later phase runs, so that later phases use the
  newly-synced configuration and script.
- **FR-023**: Configuration sync MUST advance to the deployed commit
  specifically, never to the current mainline tip.
- **FR-024**: Configuration sync MUST record the current commit first, as the
  rollback target.
- **FR-025**: Configuration sync MUST refuse to proceed if any tracked file in
  the checkout is modified; untracked and ignored files MUST NOT block it.
- **FR-026**: Configuration sync MUST fetch from a URL fixed in configuration
  rather than from the checkout's own configured remote, and MUST require no
  credential and no write access.
- **FR-027**: Configuration sync MUST assert the target commit is an ancestor of
  the fetched mainline, and MUST move the checkout by fast-forward only.
- **FR-028**: Configuration sync MUST determine which services the change affects
  *before* moving the checkout, and MUST NOT move it at all if any affected
  service — including a service that does not yet exist — is outside the
  permitted-to-recreate set.
- **FR-029**: The permitted-to-recreate set MUST be an allowlist, configurable
  without a code change, from which the executing component itself is absent.
- **FR-030**: When configuration sync declines for any reason, the deploy MUST
  continue with images only and MUST report which services were held back and
  the manual command to apply them.
- **FR-031**: The host's local environment file MUST never be synced; the deploy
  MUST report when the new configuration references a variable the host does not
  define.
- **FR-032**: Configuration sync MUST be independently switchable off, leaving
  images-only deploys working.

#### Rollback and reporting

- **FR-033**: On verification failure the system MUST restore the previously
  recorded image versions and, if the checkout was moved, restore the recorded
  previous commit — so the restore runs the previous version of the deploy
  script.
- **FR-034**: The restored version MUST be verified by the same checks as the
  deploy.
- **FR-035**: If the restore verifies clean, the maintenance page MUST come down;
  if the restore also fails, the maintenance page MUST stay up.
- **FR-036**: Rollback MUST be independently switchable off, for the case where
  rollback itself is the problem.
- **FR-037**: On failure the system MUST produce a written diagnosis from
  captured evidence — the failing component's logs, the container states and the
  commit range — and MUST post it both as a comment on the deployed commit and
  as a tracked issue.
- **FR-038**: The component producing the diagnosis MUST be given only captured
  output; it MUST have no ability to run commands and MUST touch no credential,
  container runtime or checkout.

#### Maintenance and unavailable pages

- **FR-039**: The public site and API hostnames MUST serve a branded maintenance
  response while a flag is set, and MUST return to normal when it is cleared.
- **FR-040**: With the flag absent and a public upstream unavailable, those
  hostnames MUST serve a branded unavailable page instead of a raw proxy error.
- **FR-041**: Both pages MUST be self-contained, requiring no asset served by the
  very component that may be down, MUST match the site's visual identity, and
  MUST tell the visitor when to retry and refresh themselves.
- **FR-042**: The flag MUST NOT affect the proxy's own health endpoint, the
  endpoint that receives deploy triggers, or the four operational hostnames.
- **FR-043**: The flag MUST be settable by the executing component and readable —
  but not writable — by the proxy.

#### Persistence and observability

- **FR-044**: Every deploy run MUST be recorded durably outside the workflow
  engine's retention window and outside container logs, capturing at least: the
  commit, the trigger, each phase's outcome, the verification results, whether a
  rollback was taken, and the issue reference.
- **FR-045**: The operator MUST be able to confirm the executor is actually
  listening for deploy work, distinctly from it merely running.
- **FR-046**: The operator MUST be able to start a deploy by hand for a chosen
  commit, without merging anything.

#### Removals

- **FR-047**: The application's self-redeploy request, its supporting service and
  configuration, and its admin-interface control MUST be removed entirely.
- **FR-048**: The public application MUST lose host container control, the
  deployment configuration copy and the environment-secret copy that the removed
  capability required.
- **FR-049**: No code in the public application MUST remain that launches a host
  process.

#### Rollout safety

- **FR-050**: Receiving the trigger and executing the deploy MUST be gated by two
  separate switches, both defaulting to off, each silenceable without affecting
  the other or the automated code reviewer.
- **FR-051**: The one action only a human can perform — subscribing to the build
  completion notification — MUST be recorded in the manual-actions runbook,
  because without it the feature is inert with no error anywhere.
- **FR-052**: Keeping the executing component current MUST be documented both in
  the runbooks and in the deploy skill, since it does not update itself.

### Key Entities

- **Deploy run record**: One durable record per deploy attempt. Deployed commit,
  what triggered it, the ordered phase outcomes, verification results, whether a
  rollback was taken and whether it verified, held-back services if
  configuration sync declined, and the reported issue reference.
- **Deploy request**: The commit to deploy plus its trigger provenance,
  coalesced so that at most one is in flight and the newest requested commit
  wins.
- **Maintenance flag**: A single piece of shared state, written by the executor
  and read by the proxy, whose presence means "serve the maintenance page".
- **Recreate allowlist**: The configurable set of services the automation is
  permitted to recreate. Everything not named is held for a human.
- **Rollback point**: The previous image version of each targeted service plus
  the previous host commit, recorded before anything changes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A merge to `main` reaches production with zero human actions, in
  100% of successful publish builds, once both switches are on.
- **SC-002**: The window in which the public site and API do not serve normal
  content during a deploy is under 10 minutes, and for all but a couple of
  seconds of it visitors see the branded maintenance page rather than an error.
- **SC-003**: A deploy that fails verification returns production to the
  previous working version automatically, with no human action, and the previous
  version stays in place — it is not re-broken by any automatic process
  afterwards.
- **SC-004**: Every failed deploy produces a written diagnosis reachable from
  both the deployed commit and a tracked issue, within the same run.
- **SC-005**: A merge that changes host-side configuration deploys that
  configuration and the images from the same commit, or deploys neither and says
  which services it held back — never a mixture reported as success.
- **SC-006**: Zero classes of production service outside the permitted set are
  ever recreated by the automation.
- **SC-007**: The container serving the public API holds no host-level container
  control and no copy of the deployment configuration or environment secrets.
- **SC-008**: Merging the change alters no production behaviour until an
  operator turns a switch on.
- **SC-009**: An unplanned outage of a public upstream shows a branded page, and
  every operational hostname stays reachable during both a deploy and an outage.
- **SC-010**: The maintainer can answer "what is deployed, when did it deploy,
  and did it pass" from a durable record, for any deploy in history, not only
  those still inside the workflow engine's retention window.

## Assumptions

- Production remains a single node, so no zero-downtime scheme is attempted and a
  short maintenance window is the accepted cost of a deploy.
- The workflow engine and the container-management console already running in
  production are reused; no new infrastructure is introduced.
- The repository is public, so fetching the target commit onto the host needs no
  credential and no write access.
- The publish build already tags each image with the commit SHA, so an exact
  commit can be deployed without changing the build.
- Only the three application images the publish build produces are deployed;
  deploying anything else is out of scope.
- The executing component runs the same image as the webhook receiver, since the
  failure path needs both an agent runtime and an installation credential; a
  second minimal image would duplicate that build almost exactly.
- A one-off manual step is required on the host at rollout, because the
  executor's own definition cannot deploy itself.
- Data-layer services (the database, the search index and the message broker) and
  the tools with known recreation hazards are outside the permitted set from the
  start.
- The environment file is host-managed and out of source control, so a change
  requiring a new variable still requires a human.
- Reverting a data migration automatically is out of scope; rollback covers
  images and host configuration only.
- Keeping the pinned agent binary version current is a separate feature and is
  not part of this one.
