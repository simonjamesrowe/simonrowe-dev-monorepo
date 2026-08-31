# Linear Issue Sink Runbook

> The admin console calls this module **Issue tracking**; `linear` remains the task queue,
> the flag prefix and the name used throughout this runbook.

`linear` is a module inside the existing `software-factory` container. It adds
no container, no port and no HTTP route — the internet-facing surface stays
exactly `POST /webhooks/github`. What it adds is a **sink**: a Temporal task
queue, `linear`, with one activity, `fileIssue`, that files a finding into
Linear exactly once per distinct problem and stays quiet once a human has
declined it.

Read [software-factory.md](software-factory.md) first. Everything there about
the image, the GitHub App, the Claude credential and the `env_file` prohibition
applies unchanged.

## What this is, and what it is not

- **It is a sink, not a producer.** It has no trigger, schedule or webhook of
  its own. Three modules file into it: a failed [deploy](deploy.md), the
  repository's [current vulnerabilities](cvefix.md), and a closed pull request's
  [review feedback](software-factory.md#review-feedback-loop).
- **It is not a work queue.** Nothing in the factory reads a Linear ticket back
  and acts on it — there is no "pick up this CVE issue and retry it"
  mechanism. The seam for that (`LinearProperties.producers`) exists; the
  feature does not. That consuming flow is the stated next step for
  vulnerabilities, and it is explicitly out of scope of
  040-software-factory-console.
- **Linear is truth; Mongo is the audit trail.** Identity and issue state are
  always read back from Linear itself via `attachmentsForURL`, never assumed
  from the local record. Closing or deleting a ticket by hand cannot leave the
  factory believing it is still open — the next occurrence looks the state up
  fresh.
- **It is on by default now.** `FACTORY_LINEAR_ENABLED` defaults to `true` in
  `docker-compose.prod.yml`, but `LINEAR_API_KEY` and `FACTORY_LINEAR_TEAM_KEY`
  still default to empty — enabled with no credential is neither off nor
  healthy. `ModulePrerequisites` reports exactly that state, per module, on
  `GET /api/factory/status` and once in the logs at startup, so the
  [admin console](software-factory.md#the-software-factory-admin-console) shows
  "Enabled but not usable: Linear API key is not set" rather than a green tick
  followed by a runtime failure.
- **It is the factory's first activity-only Temporal task queue.** Every other
  queue (`code-review`, `review-feedback`, `cve-fix`, `deploy`) is named by a
  `@WorkflowImpl` and gets a worker because of that. `linear` has no
  `@WorkflowImpl` at all — `LinearActivitiesImpl` alone, discovered by
  `register-activity-beans: true`, is what creates its worker. There is
  deliberately no `com.simonrowe.factory.linear.workflow` entry in
  `workflow-packages` and no stub workflow: verified against the live Temporal
  Spring starter, an `@ActivityImpl`-only queue gets a worker with no workflow
  poller needed. See [Verifying the poller](#verifying-the-poller) — **one
  activity poller and zero workflow pollers is the expected, correct shape**,
  not a fault.

## The filing decision

Every occurrence is fingerprinted from structured fields only — never from
agent-written prose, which would file the same failure twice under two
phrasings. The sink looks up every Linear issue already carrying that
fingerprint (via the synthetic, non-resolving attachment URL
`https://factory.simonrowe.dev/fingerprint/<fingerprint>`) and applies
precedence:

| Resolved state | Decision | Action |
| --- | --- | --- |
| nothing found | `FILED_NEW` | create in Triage, attach the fingerprint, label |
| open (`triage`/`backlog`/`unstarted`/`started`, or an unrecognised type) | `COMMENTED_EXISTING` | comment naming the occurrence — commit, time, detail |
| `canceled` **or** `duplicate` | `SUPPRESSED` | nothing |
| `completed` | `FILED_REGRESSION` | new issue, same fingerprint URL, linked as a regression of the completed one |
| nothing found, or only `completed`, **and the filing is `commentOnly`** | `SKIPPED_NO_ISSUE` | nothing — never creates an issue |

**`commentOnly` is a per-filing flag on `IssueFiling`, not a per-decision one.** It means: this
occurrence is a status update about a problem the producer already knows about, not a new
occurrence to track. Set it and the sink never creates an issue under any circumstances — the two
decisions that would otherwise create one (`FILED_NEW`, `FILED_REGRESSION`) are both downgraded to
`SKIPPED_NO_ISSUE` instead, logged and otherwise a no-op. `COMMENTED_EXISTING` and `SUPPRESSED` are
untouched by the flag: an open issue still gets the comment (using the producer's
`occurrenceDetail` verbatim, without the `Seen again: ` prefix, since a status update is not a
recurrence), and a declined issue still stays quiet. `FiledIssue` reports no issue id, identifier
or URL for `SKIPPED_NO_ISSUE`, on the same grounds as `SUPPRESSED`: Mongo's audit trail may still
point at an old, unrelated issue for this fingerprint, and handing that back to the producer would
misattribute it. The repository's [CVE report](cvefix.md) is the only current caller: its
dirty-to-clean transition comment sets `commentOnly` so that a newly-clean repository can never
have a fresh "current vulnerabilities" issue filed in its name. The `deploy` producer leaves the
flag unset, and its behaviour is unchanged.

**Precedence is open > (canceled or duplicate) > completed.** Two things worth
knowing before touching this table:

- **`duplicate` suppresses, exactly like `canceled`.** Linear ships a
  `duplicate` state type out of the box (verified live against team `SIM`:
  `specs/039-linear-issue-sink/research.md`), and moving an issue there sets
  `canceledAt`, not `completedAt`. Declining a machine-filed ticket as a
  duplicate means "already tracked elsewhere", which is the same decline as
  "not a bug, never tell me again" — so it shares the suppression band rather
  than being classified as open (which is what happens to any state type the
  code does not recognise, and would have happened to `duplicate` before this
  band was added).
- **Reopening a cancelled or duplicate issue un-suppresses it.** Open outranks
  that band, so the moment a human reopens the ticket, the next occurrence
  comments on it again rather than staying quiet. That is the reversal
  gesture, and it needs no configuration flag — there is nothing to toggle
  back.
- **A regression is a genuinely new issue**, not a reopen of the old one. The
  same fingerprint URL ends up attached to two issues (verified live:
  `attachmentsForURL` returns both), which is why precedence has to be defined
  rather than assumed — a naive "find the issue for this fingerprint" query
  would be ambiguous the moment a regression exists.

### What each producer keys on

The fingerprint is `sha256("v1:" + producer + ":" + keyParts)`, and the key parts
decide what counts as "the same problem":

| Producer | Key parts | One ticket per… |
| --- | --- | --- |
| `deploy` | failing phase + `DeployStatus` | kind of deploy failure |
| `cvefix` | repository + the literal `current-vulnerabilities` | repository, forever |
| `feedback` | owner + repository + pull-request number | closed pull request |

**`cvefix` deliberately keys on nothing finding-specific.** It files *one*
consolidated report listing every component Dependency-Track currently reports,
rather than one ticket per CVE — which at any realistic finding count is an
unreadable backlog nobody triages. The consequence to keep in mind: because the
key never varies, a scan that finds a different set of vulnerabilities
`COMMENTED_EXISTING` on the same long-lived ticket, and the comment carries the
full current set. Closing that ticket as completed and then scanning again files
a regression; cancelling it suppresses future scans entirely until someone
reopens it.

## Human prerequisites

Before the image ships with `FACTORY_LINEAR_ENABLED=true` anywhere:

1. A Linear team exists, with **Triage enabled on it**. This is a per-team
   toggle, off by default, and the whole suppression design depends on it: a
   team with no `triage`-type state cannot receive an `issueCreate` naming
   one, and the sink fails loudly and non-retryably rather than silently
   filing into the backlog (which would strand tickets outside the inbox the
   design assumes).
2. Labels `factory:deploy`, `factory:cvefix` and `factory:feedback` exist on
   that team.
3. A Linear API key is minted and added to the prod `.env` as `LINEAR_API_KEY`.
   **Use a narrowly-scoped key, not the one used for research.** The key used
   to verify this design carries **Read + Write + Create issues + Create
   comments**, team-limited — and it also successfully ran `teamUpdate`, which
   means it carries **Admin** scope, more than the sink ever calls. Mint a
   second key for production with the same four permissions and no Admin scope.
   See [software-factory-manual-actions.md](software-factory-manual-actions.md)
   for status.

## Rollout order

1. Complete the three human prerequisites above.
2. Deploy with `FACTORY_LINEAR_ENABLED` unset (off). Nothing changes; confirm a
   pull request still gets an inline review.
3. **Recreate `deployer` by hand** — it never self-updates, and this change
   touches `software-factory/`:
   ```bash
   docker compose -f docker-compose.prod.yml up -d --no-deps deployer
   ```
   Its Linear flag stays `false`. That is the credential confinement working,
   not an oversight — see [Credential confinement](#credential-confinement).
4. Set `LINEAR_API_KEY`, `FACTORY_LINEAR_TEAM_KEY` and
   `FACTORY_LINEAR_ENABLED=true` on **`software-factory` only**, recreate, then
   run the [poller check](#verifying-the-poller). This is the live test of the
   activity-only-queue risk — nothing else proves it.
5. `FACTORY_LINEAR_DRY_RUN=true`, then drive a `cvefix` dry run by starting the
   workflow directly — step (e) of [cvefix.md's rollout](cvefix.md#rollout-order).
   **Pass `linearFilingEnabled` explicitly.** `CveFixRequest` deliberately gives
   it no default (unlike the three CI settings), so a hand-written input that
   omits it deserializes `false`, `CveFixWorkflowImpl.fileUnfixable` returns
   immediately, and **nothing reaches `linear_issues` at all** — which reads
   exactly like a broken sink:

   ```bash
   docker run --rm --network simonrowe-dev-monorepo_default \
     temporalio/admin-tools:1.31.2 \
     temporal workflow start \
       --address temporal:7233 --namespace default \
       --type CveFixWorkflow \
       --task-queue cve-fix \
       --workflow-id cve-fix-dryrun-$(date +%Y%m%d%H%M) \
       --input '{"dryRun":true,"linearFilingEnabled":true}'
   ```

   Confirm `linear_issues` records `FILED_NEW` with **no** ticket actually
   created in Linear (dry-run still reads Linear and computes the decision — it
   performs no `issueCreate`/`attachmentCreate`/`commentCreate`).
6. Clear dry-run. Fire the same occurrence twice and confirm the second time
   comments on the existing ticket rather than filing a second one — but
   **clear the component's `unfixable_findings` row before each fire.** Step 5
   has a real side effect that otherwise blocks this: a `cvefix` dry run writes
   suppression rows for every component it could not fix, and
   `FindingSuppressor.retainActionable` is applied at *fetch* time, so the
   component is dropped before the agent ever sees it. The next run then reports
   nothing newly recorded, `fileUnfixable` iterates an empty list, and the first
   live filing never happens:

   ```bash
   docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval \
     'db.unfixable_findings.deleteOne({_id: "pkg:maven/group/artifact@1.2.3"})'
   ```

   See [Unfixable findings](cvefix.md#unfixable-findings) for what those rows
   are and why a dry run writes them.

## Credential confinement

**`deployer` never receives `LINEAR_API_KEY`, and must not.** It holds
`/var/run/docker.sock`, root-equivalent on the host, and the whole point of
splitting it from `software-factory` was to keep it holding as little else as
possible.

Two layers enforce this:

- `LinearActivitiesImpl` carries a class-level
  `@ConditionalOnProperty(factory.linear.enabled)`. It is evaluated by the
  component scanner — declaring the class through an explicit `@Bean` method
  instead would register it unconditionally and silently ignore the
  annotation. `deployer` always runs with `factory.linear.enabled=false`, so it
  never registers the activity implementation and is never handed the key.
- `docker-compose.prod.yml` declares `FACTORY_LINEAR_*`/`LINEAR_API_KEY` only
  under the `software-factory` service. `deployer`'s block carries a comment
  recording that the omission is deliberate, and
  `DeployerLinearCredentialTest` (`software-factory/src/test/java/com/simonrowe/factory/linear/config/`)
  reads the compose file and fails the build if any variable whose name
  **contains** `LINEAR` ever appears under `deployer` — because a config gate in
  Java does nothing to stop a compose edit handing the credential to the
  socket-holding container directly. **Containing, not prefixed**: a
  `LINEAR_`-prefix match would catch `LINEAR_API_KEY` and miss
  `FACTORY_LINEAR_ENABLED`, and it is that flag which registers
  `LinearActivitiesImpl` in the socket-holding JVM and makes `deployer` poll the
  `linear` queue in the first place.

## Reading `linear_issues`

Outside Temporal's retention window, one document per fingerprint:

```bash
docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval \
  'db.linear_issues.find().sort({lastSeenAt:-1}).limit(5).pretty()'
```

Each record holds the producer, its structured key parts, the Linear issue id
/ identifier / URL, `attachmentPending` (true only in the narrow window between
`issueCreate` and `attachmentCreate` — see the class Javadoc on
`LinearIssueRecord` for why that ordering matters), `firstFiledAt`,
`lastSeenAt`, an occurrence count, the last state type Linear reported, and a
capped log of the last 20 decisions (each with the producing workflow id, run
id and outcome).

## Verifying the poller

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal task-queue describe --address temporal:7233 \
  --namespace default --task-queue linear
```

**Expect exactly one `activity` poller and ZERO `workflow` pollers.** Every
other task-queue check in this codebase expects one of each — do not "fix" this
one to match. `linear` has no `@WorkflowImpl` anywhere on purpose (see
[What this is, and what it is not](#what-this-is-and-what-it-is-not)), so a
workflow poller on this queue would mean something was misconfigured, not that
something was working. A registered activity poller with zero workflow
pollers is this feature healthy; zero pollers of either kind means nothing
will ever file anything, silently.

## Failure modes

- **Filing failed, but the producer's own outcome did not change.** A failed
  deploy still rolls back; an unfixable CVE finding is still recorded in
  `unfixable_findings`. On the deploy side this shows up as
  `linearFilingFailed: true` on the `deploy_runs` record with no `issueUrl`;
  check the workflow's own logs for "Could not file the deploy failure into
  Linear" and Temporal's Activity history for why (a 401/403 is
  non-retryable and fails fast; a 5xx or rate limit retries with backoff before
  giving up).
- **Triage not enabled on the team.** `issueCreate` targeting the triage state
  fails loudly and non-retryably rather than falling back to the backlog — this
  is deliberate (see [Human prerequisites](#human-prerequisites)), so the fix is
  to enable Triage on the team, not to change the code.
- **A missing label costs nothing but is easy to miss.** Unlike Triage, a
  `factory:deploy`/`factory:cvefix` label that does not exist on the team does
  **not** fail the filing: the ticket is created unlabelled and a `WARN` naming
  the label and the team key is the only signal. `LinearGateway.teamContext()`
  also caches **positively for the process lifetime** — team id, triage state id
  and label ids are resolved once on first filing and never re-read — so a label
  created *after* the flag was enabled needs a `docker restart` of
  `software-factory` before the sink will use it.
- **Bumping `Fingerprint.VERSION` (currently `v1`) orphans every existing
  ticket.** The fingerprint is `sha256("v1:" + producer + ":" + keyParts)`; a
  version bump changes every fingerprint at once, so the next occurrence of a
  problem Linear already has a ticket for looks brand new and files a
  duplicate. This is a deliberate, one-time, documented cost when it is truly
  needed — not something to do casually, and not something a routine change to
  this module should ever touch.
- **`factory.linear.request-timeout` is 15s for an arithmetic reason, and
  raising it alone reintroduces a duplicate ticket.** One filing on a cold team
  cache makes four sequential Linear calls — fingerprint lookup, team/label/
  triage resolution, `issueCreate`, `attachmentCreate` — and both producers give
  `fileIssue` a 90-second `startToCloseTimeout`. Temporal does not interrupt a
  non-heartbeating activity when that elapses; it starts attempt 2 while attempt
  1 is still running, and attempt 2 sees an empty lookup and files a **second
  ticket**. 4 x 15s = 60s fits inside 90s. If you ever need a longer per-call
  timeout, raise the activity's `startToCloseTimeout` in `DeployWorkflowImpl`
  and `CveFixWorkflowImpl` in the same change.
- **A misconfigured sink costs up to two minutes per filing, not a hang.** Both
  producers schedule `fileIssue` on the `linear` queue with a 2-minute
  `scheduleToCloseTimeout`, specifically because with `factory.linear.enabled`
  false nothing polls that queue at all. The request-level
  `linearFilingEnabled` flag (set by whichever producer built the request from
  its own configuration, since a `@WorkflowImpl` cannot inject Spring
  properties) is the primary guard that stops the activity from ever being
  scheduled; the timeout is the backstop for the case where the flag says yes
  but nothing is actually listening. Either way, the deploy or CVE run is
  delayed by at most two minutes, never blocked indefinitely.

### Tracked, not fixed here

Two gaps recorded during design review rather than silently left for someone
to rediscover:

- **Two deploy failure paths file nothing into Linear at all: a
  `sync-config` failure, and a failed `maintenance-on`.** Both exit
  `DeployWorkflowImpl` via `finish`, which only ever reports for
  `DEPLOYED_IMAGES_ONLY` — this is faithful parity with the old behaviour (the
  `openIssue` activity that this sink replaced, and which no longer exists in
  the codebase, was only ever reachable from the failure path that calls
  `report`, so neither of these paths opened a GitHub issue before the sink
  existed either), but it is the worse gap of the two by far: `sync-config` failing
  means **production is untouched while the automation itself is wedged**
  (dirty tree, a non-ancestor sha, a fetch failure), it recurs identically on
  every subsequent merge until a human intervenes, and today it is invisible
  outside Mongo and Temporal's retention window. Routing `finish`'s failure
  statuses through the reporting path would also change commit-comment
  behaviour and needs its own key-parts decision for the fingerprint
  (`sync_config` + `FAILED` would work) — deliberately deferred, not
  forgotten.
- **While `FACTORY_LINEAR_ENABLED` is off, a failed deploy's diagnosis is
  computed and then thrown away.** `renderFailure` — which produces the
  title and body an agent wrote from the triage — sits *inside* the
  `linearFilingEnabled` guard in `DeployWorkflowImpl`, and `DeployRunRecord`
  persists no triage field at all. So with the sink disabled, the agent still
  runs `triage.diagnosis()`, `confidence()`, `errorClass()`, `logExcerpts()`
  and the phases/config-sync table on every failed deploy, and none of it is
  kept anywhere — it used to go into the GitHub issue body this sink replaced.
  This is the accepted default-off posture, but it is a real reduction in
  operator information while the flag is off, not a no-op: the only
  human-readable output of a failed deploy today is the short commit comment
  (headline plus next step). Enabling the flag is what restores the long-form
  diagnosis, into Linear instead of GitHub.
