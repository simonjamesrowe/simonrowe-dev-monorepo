# Design: Linear issue sink for the software factory

**Date**: 2026-08-27

**Status**: Approved

**Successor artefact**: `specs/039-linear-issue-sink/` (via speckit)

## Why this, and why first

The software factory has four modules today, and none of them has anywhere to put
a finding a human needs to see.

| Module | Temporal queue | Trigger | What it does |
| --- | --- | --- | --- |
| `codereview` | `code-review` | signed `pull_request` webhook | clones the branch, runs Claude Code, posts one PR comment plus inline findings |
| `feedback` | `review-feedback` | PR close | harvests the review conversation, writes `review_learnings`, opens `agent-feedback` guidance PRs |
| `cvefix` | `cve-fix` | paused 24h Temporal schedule | reads Dependency-Track, bumps dependencies, opens a PR, polls CI |
| `deploy` | `deploy` | signed `workflow_run` webhook | the `deployer` container runs `restart-prod.sh` phases, with rollback, triage and reporting |

(A sixth module, `platformbackup`, landed on `main` while this was being built. It has no
findings to file and is untouched by this work.)

Two of those produce findings with no home. `deploy` opens a GitHub issue on
failure — and `gh issue list --state all` returns nothing, so that path has never
fired and GitHub Issues is not a tracker anyone reads. `cvefix` writes
`UnfixableFindingRecord` to Mongo, which suppresses the finding from future runs
and tells no one.

Linear is now the tracker for this system. Building the sink first, with two
existing producers wired into it, means the hard part — filing exactly once and
knowing when not to file at all — is exercised by real traffic before the
noisiest producer exists.

### The factory's remit, stated deliberately

`scripts/monitor-prod.sh` answers *"is it running?"* every minute, and heals what
it can. The factory answers *"is it correct?"* — bugs, broken features, bad AI
answers, usability problems. Things no restart fixes. This distinction governs
what the successor features are allowed to file.

## Scope

### In

- A new module, `com.simonrowe.factory.linear`: a **sink**, with no trigger,
  schedule or webhook of its own.
- A `linear` Temporal task queue with one activity, `fileIssue`, executed only by
  `software-factory`.
- Fingerprint-to-issue identity via Linear attachments, with issue state read
  from Linear.
- A `linear_issues` Mongo audit collection.
- Producers retrofitted: `deploy` failure (replacing the never-used GitHub issue)
  and `cvefix` unfixable findings.
- Docs: `docs/runbooks/linear.md`; the human prerequisites appended to
  `software-factory-manual-actions.md`; a correction to
  `docs/runbooks/software-factory.md`, whose opening still claims the container
  "hosts only `codereview`"; and the four-module table above. CLAUDE.md gains a
  Recent Changes entry, plus the `feedback` module entry it has never had.

### Out, and each its own spec

- **The scheduled production bug-hunter.** The feature this sink exists for.
  Reads Loki, Langfuse and the site itself looking for defects and usability
  problems, and files them here. Cadence decided 2026-08-27: **every 24 hours,
  and the interval configurable** — so it follows `cve-fix-daily`'s shape (a
  paused-by-default Temporal schedule declared in code, reconciled by a deploy)
  but not its hardcoded `CveFixScheduleInitializer.INTERVAL` constant.
- **Post-deploy production verification.** Smoke tests after an auto-deploy.
  Note `frontend/e2e/chat.prod-smoke.spec.ts` and a `prod-smoke` Playwright
  project already exist; the open question is whether the factory image should
  carry a browser, given it is a slim JRE shared with the `deployer`.
- **Frontend error collection.** There is no Sentry, no `ErrorBoundary` and no
  `window.onerror` anywhere in `frontend/src`, so a React crash on a visitor's
  screen is currently invisible. The bug-hunter needs this signal.
- **A metrics backend.** Alloy ships container logs to Grafana Cloud Loki and
  traces to Langfuse. The Tempo exporter has been disabled since 2026-07-11 and
  nothing scrapes `/actuator/prometheus`. "Look at the metrics" is not currently
  possible.
- **Linear as a work queue.** Reading a ticket and acting on it — auto-picking-up
  CVE tickets, for instance. The seam for it is in this design; the feature is not.
- **A deploy error-class taxonomy.** See the fingerprint section.
- **Five documentation/Javadoc residuals from the final review (2026-08-27).** All real,
  all small, none a code defect. Parked rather than fixed because they surfaced *in* the
  final fix wave's own re-review, and a second wave was not justified; fold them into the
  pull-request loop.
  1. **`DeployRunRecord.issueUrl` and `DeployResult` enumerate three null cases** — sink
     disabled, nothing failed, filing failed — but a *human-declined* ticket is a fourth,
     since a `SUPPRESSED` occurrence now reports no issue. Worse,
     `DeployRunRecord.linearFilingFailed`'s Javadoc says "no ticket was filed — whatever
     the cause", yet a suppressed occurrence attempted filing, filed nothing, and leaves
     the flag **false**. So `issueUrl: null, linearFilingFailed: false` in `deploy_runs`
     currently reads as "the sink is off", when it may mean "a human declined this ticket".
     One clause in each of two Javadocs.
  2. **The load-bearing timeout arithmetic says four sequential Linear calls; the
     `FILED_REGRESSION` path makes five** (`relateIssues`). 5 x 15s = 75s, still inside the
     90s `startToCloseTimeout`, so no duplicate window is open — but the slack is 15s, not
     30s, and that comment is what a future editor will do arithmetic against.
  3. **`FiledIssue`'s Javadoc still says "null ... for a dry run".** The suppressed half is
     now enforced; the dry-run half is not — a dry run over a fingerprint that already
     carries a ticket returns that ticket's identifier.
  4. **`linear.md` rollout steps 5 and 6 never say to recreate the container** after
     editing `.env`. Followed literally the flag has not taken effect, so step 5 files a
     **real** ticket while instructing the operator to confirm none was created. Step 6's
     `deleteOne` also uses a placeholder purl with no instruction for finding the real one.
  5. `DeployerLinearCredentialTest`'s `contains("LINEAR")` is case-sensitive, so a
     lowercase `linear_api_key` mapping key would slip through. Env-var convention makes
     this unlikely.

- **Two deploy failure paths that file nothing** (found in Task 10 review, 2026-08-27).
  A `SyncDecision.FAILED` and a failed `maintenance-on` both exit `DeployWorkflowImpl`
  via `finish`, which reports only for `DEPLOYED_IMAGES_ONLY`. This is **faithful
  parity** — `openIssue` was only ever reachable from `report`, so neither path opened a
  GitHub issue either — but the `sync-config` gap is worth closing. It is the outcome
  where production is untouched while the automation is *wedged* (dirty tree,
  non-ancestor sha, fetch failure), it recurs identically on every subsequent merge, and
  it is invisible outside Mongo and Temporal's retention window. Deliberately not fixed
  here: routing `finish`'s failure statuses through reporting also changes commit-comment
  behaviour and needs its own key-parts decision (`sync_config` + `FAILED` would work).
  **Tracked item, not just a paragraph.**

## Architecture

```
  software-factory                        deployer
  |- webhook receiver                     |- deploy activities (Docker socket)
  |- code-review worker                   `- polls: deploy
  |- cve-fix worker                          (workflow tasks + activities)
  |- review-feedback worker
  |- LINEAR worker  <------------------------ schedules fileIssue on the
  |    holds LINEAR_API_KEY                   `linear` queue, cross-container
  `- polls: code-review, review-feedback,
            cve-fix, deploy (workflow only), linear
```

`DeployWorkflowImpl` already runs its workflow tasks on either container and
dispatches side effects to whichever one holds the activity implementation.
Filing runs that mechanism in the other direction: the producing workflow
schedules `fileIssue` with `ActivityOptions.setTaskQueue("linear")`, and only
`software-factory` can execute it.

**Credential confinement is the point.** `deployer` exists so that the container
holding a root-equivalent Docker socket holds as little else as possible. It
never receives `LINEAR_API_KEY`.

`LinearActivitiesImpl` carries a class-level
`@ConditionalOnProperty(factory.linear.enabled)`, evaluated by the **component
scanner** — declaring the same class through an explicit `@Bean` method silently
ignores the annotation. `LinearWorkerRegistrationTest` therefore component-scans,
mirroring `DeployWorkerRegistrationTest`.

### The architectural risk to settle first

`linear` would be the factory's **first activity-only task queue**. Every
existing queue is named by an `@WorkflowImpl` class, and `application.yml` carries
two long comments about what happens when a queue has no poller: the container is
healthy, the trigger fires, and nothing runs.

Whether `@ActivityImpl(taskQueues = "linear")` alone causes the Spring Temporal
starter to create a worker must be verified, not assumed. If it does not, the
fallback is a trivial `FileIssueWorkflow` on the `linear` queue whose only job is
to make the queue exist and call the activity.

### Team resolution adds no boot-time dependency

`issueCreate` needs a team UUID, but UUIDs in `.env` are unreadable. The module
takes a human team **key** and resolves it to a UUID **lazily on first filing**,
cached in memory. Never at boot: `CveFixScheduleInitializer` documents why an
unreachable third party must not fail the application context and take the
webhook receiver down with it.

## Data model

### Fingerprint

Deterministic, from structured fields only, never from agent prose.
`DeployActivities` already documents `headline` as "one line naming the failing
component and the symptom; the issue title" — that is generated text, and the
same failure phrased differently twice would file twice.

```
fingerprint = sha256("v1:" + producer + ":" + keyParts.join("|"))
```

| Producer | Key parts |
| --- | --- |
| `deploy` | failing phase + deploy status, e.g. `recreate` + `ROLLED_BACK` |
| `cvefix` | component purl — the key `UnfixableFindingRecord` already uses |

The `v1` prefix follows the codebase's `FORMAT_VERSION` idiom and carries the
same warning `NarrationScriptBuilder.FORMAT_VERSION` does: **bumping it orphans
every existing ticket**, so the next occurrence of a known problem files a
duplicate. A deliberate, documented one-time cost.

**Amended 2026-08-27 (Task 10):** the second key part is the **deploy status**, not the
failing service. The service is not structured anywhere — `PhaseOutcome.detail` is only the
trimmed tail of phase output, explicitly not a log store — so deriving it would mean inventing
a parser for failure text nobody has observed. `DeployStatus` is structured and in scope, and
separates `FAILED`, `ROLLED_BACK`, `ROLLBACK_FAILED` and `ROLLBACK_DISABLED`.

**The deploy fingerprint deliberately excludes the commit.** `recreate`/`ROLLED_BACK`
resolves to one ticket however many commits trip it; a recurrence comments,
naming the new commit. The accepted cost is that two unrelated changes breaking
the same phase share a ticket. The alternative — a coarse error class in the key,
splitting `healthcheck-timeout` from `image-pull-failed` — is where this should
end up, but that path has never fired in production even once, so any taxonomy
written today is invented rather than observed. The comment trail is what will
reveal the real classes.

### Linear side

- Issue created in **Triage**, labelled `factory:<producer>`, priority from the
  producer's policy.
- Identity is an `attachmentCreate` whose URL is
  `https://factory.simonrowe.dev/fingerprint/<fingerprint>` — synthetic and
  deliberately non-resolving. It is a key, not a link.
- Lookup is `attachmentsForURL`: exact match, no text search. This is the
  mechanism Linear's own Sentry and GitHub integrations use.

Text search was rejected. A description marker matching the code reviewer's
`<!-- temporal-code-review:... -->` idiom would depend on description-filter
semantics, risk substring collisions, and break the moment someone edits the
description while triaging.

### Mongo `linear_issues`

One document per problem, `_id` = fingerprint, upserted: producer, key parts,
Linear issue id / identifier / URL, `firstFiledAt`, `lastSeenAt`, occurrence
count, `lastKnownStateType`, `attachmentPending`, and a **capped** log of the last
20 decisions, each with the producing workflow id, run id and outcome. Index
`{producer: 1, lastSeenAt: -1}` via a `LinearIndexInitializer`, mirroring
`DeployIndexInitializer`.

**Linear is truth; Mongo is the audit trail.** Identity and state are always read
from Linear, so closing or deleting a ticket by hand cannot leave the factory
believing it is still open. Mongo answers "what has this filed, and did it dedup
correctly?" without paging through the tracker, the way `deploy_runs` and
`cve_fix_runs` already do outside Temporal's retention window.

## The filing decision

Resolve every issue carrying the fingerprint, then apply **precedence:
open > canceled > completed**.

| Resolved state | Decision | Action |
| --- | --- | --- |
| nothing found | `FILED_NEW` | create in Triage, attach fingerprint, label |
| open (`triage`/`backlog`/`unstarted`/`started`) | `COMMENTED_EXISTING` | comment naming the occurrence — commit, time, detail |
| `canceled` | `SUPPRESSED` | nothing |
| `completed` | `FILED_REGRESSION` | new issue, **same** fingerprint URL, linked as a regression of the completed one |

This uses Linear's own close semantics rather than inventing a policy. Declining
from Triage sets `canceled`, which means "not a bug, never tell me again".
`completed` means "fixed", so a recurrence is a regression — genuinely different
information from "this is happening", and exactly the signal that an automated fix
did not hold. Reopening or a time window were both rejected: they discard that
distinction and override a human who closed something deliberately.

Two consequences worth stating:

- The regression path leaves two issues sharing one fingerprint. That is why
  precedence is defined rather than assumed.
- **Reopening a cancelled issue un-suppresses it**, because open outranks
  canceled. That is the reversal gesture, and it needs no config flag.

### The two real duplicate risks

1. **`issueCreate` succeeds, `attachmentCreate` fails.** A retry finds no
   attachment and files a second ticket. Closed by writing the Mongo record
   immediately after create with `attachmentPending: true`; a retry seeing a
   pending record **repairs by attaching**, never by re-creating.
2. **A retry after a fully successful create** finds the attachment and posts a
   spurious "it happened again" comment. Closed by a caller-supplied
   `occurrenceId` — checked against the decision log. Mongo-based rather than
   Linear-based on purpose: the worst case of a lost record is one duplicate
   comment, not a duplicate ticket.

   **The run id alone is not enough for either producer**, and the design's
   original "the producing workflow's run id" was wrong on both sides. One run
   can file several times: `cvefix` files once per unfixable component, and
   `deploy`'s drain loop re-enters `deployOnce` when a newer commit is signalled
   mid-deploy. So the id is the run id plus the thing that varies within the run
   — `runId:purl` for cvefix, `runId:sha` for deploy. With a bare run id the
   second real occurrence in one run is read as a replay of the first and
   silently dropped: no ticket, no comment, and the producer's own run record
   echoes the earlier decision.

## Failure boundaries

The tracker being down must never change a producer's outcome.

- Transient (5xx, rate limit): the activity throws, Temporal retries with capped
  backoff. On exhaustion the producer catches — `RuntimeException`, not just
  `ActivityFailure`, because building the `IssueFiling` payload happens on the
  workflow thread and a fault there is no `TemporalFailure`, so a narrow catch
  fails the workflow *task* and Temporal retries those forever — and continues.
  A failed deploy still rolls back; an unfixable CVE is still recorded.

  **The two producers record the failure asymmetrically, on purpose.** `deploy`
  sets `linearFilingFailed` on `DeployRunRecord` and reports with a null issue
  URL. `cvefix` records nothing about filing on `cve_fix_runs` at all — it
  discards the returned `FiledIssue` and only logs. One run files N components,
  so a single boolean or URL field on the run record could not honestly describe
  the outcome, and `linear_issues` already holds one audited row per
  fingerprint with a per-occurrence decision log. That is the trail for cvefix;
  `cve_fix_runs` is not.
- `401`/`403`: non-retryable, fail fast and loud. A read-only key must not burn a
  retry budget.
- **With `factory.linear.enabled=false`, nothing polls the `linear` queue**, so a
  scheduled activity would sit there until timeout. Two mitigations, both applied:
  the producing request **carries the flag** — `DeployRequest` and
  `CveFixRequest` each gain `linearFilingEnabled`, set by whichever side builds
  the request from its own properties, because `@WorkflowImpl` classes are
  instantiated by the Temporal SDK and cannot inject configuration (this is why
  `CveFixScheduleInitializer` already copies its CI settings into the scheduled
  request) — **and** `fileIssue` gets a short `scheduleToCloseTimeout` so an
  unpolled queue fails in seconds rather than hanging every deploy.

  For `cvefix` the cross-container hop does not arise, since `software-factory`
  executes both the `cve-fix` and `linear` activities. The flag still has to
  travel on the request, for the same reason.

## Configuration

```yaml
factory:
  linear:
    enabled: ${FACTORY_LINEAR_ENABLED:false}
    api-key: ${LINEAR_API_KEY:}
    api-base-url: ${LINEAR_API_URL:https://api.linear.app/graphql}
    team-key: ${FACTORY_LINEAR_TEAM_KEY:}
    fingerprint-base-url: https://factory.simonrowe.dev/fingerprint
    dry-run: ${FACTORY_LINEAR_DRY_RUN:false}   # reads Linear, writes nothing to it
    request-timeout: 30s   # AMENDED 2026-08-27 to 15s — see below
    producers:
      deploy:  { label: "factory:deploy",  priority: 1 }
      cvefix:  { label: "factory:cvefix",  priority: 3 }
```

**Amended 2026-08-27 (final review): `request-timeout` is 15s, not 30s.** The filing
activity's `startToCloseTimeout` is 90s, but the worst case of `IssueFiler.file` on a cold
cache is four sequential HTTP calls — fingerprint lookup, team resolution, `issueCreate`,
`attachmentCreate`. At 30s each that is ~120s, and Temporal does not interrupt a
non-heartbeating activity: attempt 1 keeps running while attempt 2 starts, sees an empty
lookup, and files a second ticket. At 15s the worst case is 60s, comfortably inside 90s.
**Raising either value requires raising the other.** The live values are in
`application.yml` and `docs/runbooks/linear.md`; this document is a dated artefact.

`dry-run` still **reads** Linear — resolving the fingerprint and computing the
decision is the whole point of the mode — and writes the outcome to
`linear_issues`. It performs no `issueCreate`, `attachmentCreate` or
`commentCreate`. A dry run therefore proves the lookup and the decision table
against the real tracker without leaving anything in it.

Off by default, like every other module. The `producers` map is the seam for
treating issue types differently later — a different target state for CVE
tickets, for instance — without touching code.

## Rollout order

1. **Human prerequisites first**, before the image ships. The `feedback` rollout
   records why: a stale external permission plus a new image took down a working
   feature, not just the new one.
2. Deploy with `FACTORY_LINEAR_ENABLED` unset. Nothing changes; confirm a pull
   request still gets an inline review.
3. **Recreate `deployer` by hand** — `up -d --no-deps deployer`. It never
   self-updates and this change touches `software-factory/`. Its Linear flag stays
   `false`; that is the credential confinement working.
4. Set the key, team key and `FACTORY_LINEAR_ENABLED=true` on **`software-factory`
   only**, recreate, then `temporal task-queue describe --task-queue linear`.
   Expect **one activity poller and zero workflow pollers.** That assertion is the
   live test of the activity-only-queue risk; nothing else would tell you.
5. `FACTORY_LINEAR_DRY_RUN=true`, drive a `cvefix` dry run through the manual
   endpoint, confirm `linear_issues` records `FILED_NEW` with no ticket in Linear.
6. Clear dry-run. Fire twice to prove the second occurrence comments rather than
   files.

## Testing

Follows the module's existing patterns; no new dependencies. `DependencyTrackClient`
uses `java.net.http.HttpClient` and its tests stub with the JDK's
`com.sun.net.httpserver.HttpServer`. The Linear gateway does the same.

- **`FilingDecisionTest`** — table-driven over the whole precedence matrix,
  including multi-issue sets. Pure, no I/O. The decision table *is* the feature,
  so this is the test that matters most.
- Idempotency: attachment-pending repair files no second ticket; a replayed
  `occurrenceId` posts no second comment.
- `LinearGatewayTest` against a stubbed GraphQL endpoint, including a `401`
  proving non-retryable classification.
- `LinearWorkerRegistrationTest` — component-scans; the activity registers when
  enabled and is absent when disabled.
- Producer resilience in the Temporal test environment: a `fileIssue` that
  exhausts its retries leaves the deploy's outcome and rollback untouched.

`software-factory`'s JaCoCo is **report-only with no floor**, so coverage here is
a judgement call, not a gate.

## Research items — verify before writing code

1. `attachmentsForURL` returns attachments on issues in **every** state,
   `canceled` included. **This one can invalidate the design**: if it filters,
   suppression silently stops working and declined bugs are re-filed forever. The
   fallback is Mongo-as-index with state fetched per issue id from Linear.
2. Whether the **same attachment URL may be attached to more than one issue**.
   Linear dedupes attachments within an issue; the regression path deliberately
   reuses one URL across two issues, so cross-issue reuse must be permitted and
   `attachmentsForURL` must return both. If it rejects the second attachment, the
   regression issue needs a distinct URL (fingerprint plus an occurrence
   discriminator) and the lookup becomes a prefix query — which
   `attachmentsForURL` will not do, pushing the design to the item-1 fallback.
3. Does `@ActivityImpl(taskQueues = "linear")` alone cause the Spring Temporal
   starter to create a worker, with no `@WorkflowImpl` naming that queue?
4. Does `attachmentCreate` validate that the URL resolves?
5. Personal API key scopes cover `issueCreate`, `attachmentCreate`,
   `commentCreate`, and reading `state.type`.
6. Is filing into Triage an `issueCreate` with the triage state's id, or a
   distinct mechanism?
7. Priority enum mapping (expected: 0 none, 1 urgent, 2 high, 3 normal, 4 low).

## Human prerequisites

To be appended to `docs/runbooks/software-factory-manual-actions.md`:

- Linear team created, and **Triage enabled on that team**. It is a per-team
  toggle, and the whole suppression design depends on it.
- Labels `factory:deploy` and `factory:cvefix` created.
- A Linear API key minted and added to prod `.env`.

Unrelated to production: the Linear **MCP** server needs authorising with `/mcp`
in an interactive session for Claude Code to query Linear directly. The factory
does not use MCP — it calls the GraphQL API with an API key.
