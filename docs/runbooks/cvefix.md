# CVE Fix Production Runbook

`cvefix` is a module inside the existing `software-factory` container. It adds no
container, no port and no HTTP route: the internet-facing surface of that service
stays exactly `POST /webhooks/github`. What it adds is a second Temporal task
queue, `cve-fix`, and a schedule that fires on it once a day.

Read [software-factory.md](software-factory.md) first. Everything there about the
image, the GitHub App, the Claude credential and the `env_file` prohibition
applies unchanged.

## What it does, and what it deliberately does not

Once a day the `CveFixWorkflow` reads OWASP Dependency-Track's current findings
for the two SBOM projects, drops components already recorded as unfixable, hands
the rest to a headless Claude agent that may edit only four files
(`gradle/libs.versions.toml`, `backend/build.gradle.kts`,
`frontend/package.json`, `frontend/package-lock.json`), force-pushes the result
to one fixed branch, opens **one** pull request, and then polls CI — feeding
failure output back to the agent — until the build is green or the repair budget
runs out.

What it does not do, all on purpose:

- **It never merges.** A green run ends with a pull request waiting for a human.
  That is the whole point of the design: the agent's output is reviewed like
  anyone else's.
- **It never opens a second pull request.** The branch is fixed
  (`chore/dependency-cve-fixes`) and the run starts by checking whether that
  branch already has one open. If it does, the run ends immediately as
  `SKIPPED_PR_OPEN`. Combined with the schedule's
  `SCHEDULE_OVERLAP_POLICY_SKIP`, only one run is ever in flight.
- **It builds nothing locally.** The container has no Gradle, no Node and no
  Docker, and the agent is given no `Bash` tool at all. CI is the only build
  environment this feature has, which is why the repair loop is a CI poll rather
  than a local `./gradlew test`.
- **The agent never touches git and never sees a credential.** Cloning,
  changed-path validation, committing and pushing are all Java
  (`RepositoryWorkspaceFactory`); the Dependency-Track key is read by an
  activity, and `ClaudeCliRunner` strips it from the agent's environment
  because it sits outside `SAFE_SECRET_ENVIRONMENT`.

The schedule itself is declared in code (`CveFixScheduleInitializer`) so a deploy
reconciles it, rather than it being hand-made server state that no deploy tracks.
It is `cve-fix-daily` in the Temporal UI, it is **created paused**, and on every
subsequent restart the initializer updates the action, spec and policy while
carrying the current paused flag forward — so a deploy cannot silently re-pause a
schedule you unpaused.

## Credentials: the one live prerequisite

`DEPENDENCYTRACK_API_KEY` must hold a Dependency-Track key whose team has **both**
`VIEW_PORTFOLIO` and `VIEW_VULNERABILITY`. `VIEW_PORTFOLIO` alone lists projects;
reading `/api/v1/finding/project/{uuid}` — the only endpoint this module cares
about — needs `VIEW_VULNERABILITY` as well, and without it the call is refused
and every run fails in its first activity.

**The existing `CI Upload` key is not sufficient, and cannot be reused.** Two
independent reasons:

1. Its team is scoped for SBOM upload and lacks `VIEW_VULNERABILITY`.
2. It exists only as a GitHub Actions secret, and GitHub Actions secrets cannot
   be read back — so even if the permission were added, the value is not
   recoverable.

**As of this writing that key does not exist yet.** Creating a new team with those
two permissions and a key, in the Dependency-Track UI, is an unmet prerequisite
of turning the feature on — not of deploying this change. That is why the compose
variable is defaulted (`${DEPENDENCYTRACK_API_KEY:-}`) rather than required: an
absent key must not break every `docker compose` command against
`docker-compose.prod.yml` while the feature is disabled.

`DEPENDENCYTRACK_BASE_URL` defaults to `http://dependencytrack-apiserver:8080` —
the container on the shared compose network, not `https://dependency-track.simonrowe.dev`.
That keeps the call off the Cloudflare/pinggy path entirely. There is deliberately
no `depends_on` for `dependencytrack-apiserver`: the workflow fails cleanly when
Dependency-Track is down, and coupling the webhook receiver's startup to
Dependency-Track would be a regression in the code-review path.

## Rollout order

The order matters. Each step is a gate on the next.

**(a) Put the key in the deploy `.env` first.**

```dotenv
DEPENDENCYTRACK_API_KEY=odt_...
```

Keep `.env` at `0600`. Do this before enabling anything, so the first run that
executes has a working credential rather than failing and needing a re-drive.

**(b) Deploy with the feature off and confirm the stack is healthy.**

`FACTORY_CVEFIX_ENABLED` defaults to `false`, so a plain deploy ships the code
without activating it:

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
git pull
docker compose -f docker-compose.prod.yml config --quiet
./scripts/restart-prod.sh
docker compose -f docker-compose.prod.yml ps software-factory
```

With the flag off, neither the schedule initializer nor the Mongo index
initializer runs, so nothing is scheduled and no Dependency-Track call is made.
Note what the flag does *not* gate: Temporal workers-auto-discovery registers the
`cvefix` workflow package unconditionally, so the `cve-fix` worker and its
pollers exist either way. That is intentional — a queue with a live poller and no
schedule does nothing at all.

Confirm a pull request still gets an inline review before going further; this
step is about proving the new image did not disturb the existing feature.

**(c) Turn it on and restart.**

```dotenv
FACTORY_CVEFIX_ENABLED=true
```

```bash
./scripts/restart-prod.sh
docker compose -f docker-compose.prod.yml logs --tail=50 software-factory \
  | grep -i 'Temporal schedule'
```

Expect `Created Temporal schedule cve-fix-daily (paused; unpause after a dry run)`
on the first enabled boot, and `Updated existing Temporal schedule cve-fix-daily`
on every boot after that.

**(d) Confirm a live poller on the `cve-fix` task queue.**

This is the step people skip, and it is the one that catches the quiet failure: a
container can be `healthy` — the actuator healthcheck reports the web half only —
while having registered no poller on this queue. The schedule then fires on time
and nothing ever runs.

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal task-queue describe --address temporal:7233 \
  --namespace default --task-queue cve-fix
```

Expect one `workflow` and one `activity` poller. Zero pollers means stop here:
the schedule will fire on time and nothing will pick the work up. Look at the
container logs for a worker-registration failure — and note that because the
worker is not gated on the feature flag, a *present* poller proves the worker
joined the queue, not that the feature is enabled. The schedule log line in step
(c) is what proves the flag arrived.

**(e) Start one dry run — directly, not through the schedule.**

A dry run does everything except open the pull request: it reads findings, runs
the agent, validates the changed paths, force-pushes the branch, and — this is
the part that surprises people — **records this run's unfixable components in
`unfixable_findings`**, exactly as a real run would. (It stops after the push
rather than before it because `ci.yml` triggers on `pull_request` only, so the
branch alone builds nothing, and the diff is then sitting on the branch for you
to read.)

So "dry" means "opens no pull request", not "writes nothing". Those suppression
rows make later *real* runs skip those components until their finding set
changes, which can make your first real run look like it did less than expected.
If a dry run suppressed something you would rather it retried, clear the row —
see [Unfixable findings](#unfixable-findings) below.

The Temporal UI's **Trigger** action cannot do this. `ScheduleHandle.trigger()`
takes no workflow arguments, so Trigger re-runs the schedule's *configured*
request, which has `dryRun = false` — it would open a real pull request. Start the
workflow directly instead:

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal workflow start \
    --address temporal:7233 --namespace default \
    --type CveFixWorkflow \
    --task-queue cve-fix \
    --workflow-id cve-fix-dryrun-$(date +%Y%m%d%H%M) \
    --input '{"dryRun":true,"pollInterval":"PT3M","repairBudget":3,"maxWait":"PT3H"}'
```

The three CI fields are the JSON shape of `CveFixRequest`; ISO-8601 duration
strings are what its `Duration` fields accept. They are optional —
`'{"dryRun":true}'` works too, because the record's compact constructor fills in
the same production defaults (3 minutes / 3 repairs / 3 hours). They are carried
in the request rather than read from configuration because `@WorkflowImpl`
classes are instantiated by the Temporal SDK, not Spring, and cannot inject
`CveFixProperties`.

Then read the history:

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal workflow show --address temporal:7233 --namespace default \
    --workflow-id cve-fix-dryrun-...
```

It must reach `proposeAndPush` and stop before `openPullRequest`, finishing
`DRY_RUN` with a detail of `dry run: pushed N bump(s), no pull request opened`.
`DRY_RUN` is its own terminal status rather than `COMPLETED` because a dry run
still pushes the branch and still records this run's unfixable components as
suppressions.
A dry run that reports `NO_FINDINGS` proves the Dependency-Track credential works
but exercises nothing else — read the activity result to check it actually saw
projects, rather than treating an empty answer as a pass.

**(f) Unpause the schedule.**

Only now. In the Temporal UI: Schedules → `cve-fix-daily` → Unpause. (Or
`temporal schedule unpause --schedule-id cve-fix-daily`, wrapped in the same
`docker run` as above.) The next fire opens a real pull request.

## The stall is by design

A run that pushes bumps and never gets CI green ends `CI_UNRESOLVED` — either the
repair budget ran out or `ci.maxWait` (3h) elapsed first; the two are
distinguished by the result's `detail`, not its status — and it **leaves the pull
request open**.

Every later run then ends immediately as `SKIPPED_PR_OPEN`. That is deliberate,
not a bug: one branch, one pull request, and no autonomous stack of half-working
dependency bumps. The feature stays stalled until a human acts.

**To resume: merge the pull request, or close it.** Nothing else clears the
condition, and no timeout does it for you.

To see where things stand, read the run records — **not** `temporal workflow
list`, which shows the Temporal execution *status* (`Completed`, `Failed`) and
never the `CveFixStatus` the workflow returned. A skipped run is a perfectly
`Completed` execution, so the stall is invisible there:

```bash
docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval \
  'db.cve_fix_runs.find({}, {startedAt: 1, status: 1, prUrl: 1, detail: 1})
     .sort({startedAt: -1}).limit(14).pretty()'
```

A run of consecutive `SKIPPED_PR_OPEN` documents is the signature. For a single
run, `temporal workflow show --workflow-id <id>` also carries the returned
`CveFixResult` in its completion event. Either way, check the open pull request
on `chore/dependency-cve-fixes` and decide.

## Why CI status is read unauthenticated

`CiStatusGateway` sends no credential, and that must not be "fixed" later.

`GitHubCredentials.accessToken` mints one installation token from a single shared
permission payload — `contents`, `issues`, `pull_requests` — that the code-review
and review-feedback flows also depend on. GitHub 422s the **whole**
access-token request if the App was not granted a requested permission. So adding
`checks: read` to that payload to serve this one gateway would break token
minting for code review and feedback as well: a full outage of the review
feature, caused by a read-only convenience in the CVE path.

This repository is public, so
`GET /repos/{owner}/{repo}/commits/{sha}/check-runs` returns full check data with
no credential. The cost is the unauthenticated limit of 60 requests/hour per IP.
The 3-minute poll interval keeps usage near 20/hour typically, and near 40/hour
while polling a red pull request (reading the outcome and reading the failure logs
are separate requests). **Do not lower `factory.cvefix.ci.poll-interval`**, and do
not add auth here to raise the ceiling.

**Escalation path if `simonjamesrowe/simonrowe-dev-monorepo` ever goes private:**
this gateway stops working, and the correct response is to revisit the design —
not to add `checks: read` to the shared mint. Options, in order of preference: a
separate GitHub App (or a fine-grained PAT) used only by this gateway; or moving
the CI signal to a push from CI rather than a poll from here. Widening the shared
token is a deliberate decision about the code-review and feedback paths too, and
must be taken as one.

## Advisory checks

`factory.cvefix.ci.advisory-checks` names check runs whose conclusion can never
make CI read RED. It defaults to `["evaluate"]`, the promptfoo evals job, which
runs with job-level `continue-on-error: true`.

Advisory checks are excluded *before* any GREEN/RED/PENDING decision. The reason
is worth understanding before you touch this list: `continue-on-error` is
documented to stop the workflow *run* from failing, and it is **not** established
that it also rewrites the `conclusion` GitHub reports for that job's own check
run. If it does not, then without this exclusion every poll of an
otherwise-green pull request would read RED, the agent would burn the entire
repair budget trying to fix a promptfoo/OpenAI-spend problem it cannot fix, and
the run would end `CI_UNRESOLVED` — which then blocks every later run through
`SKIPPED_PR_OPEN`. One advisory job would stall the whole feature indefinitely.

So: **when you add a new advisory (continue-on-error) job to `ci.yml`, add its
check-run name here.** Also note the converse — a name in this list is genuinely
ignored, so do not park a real, blocking check here to get a pull request through.

## Unfixable findings

When the agent reports it cannot bump a component (no fixed version published,
for instance), the run records that in Mongo so later runs do not spend the
agent's turns on it again:

- database `software_factory`, collection **`unfixable_findings`**, one document
  per component keyed by purl (also its `_id`).
- the document stores a **fingerprint**: the purl plus the component's sorted
  vulnerability ids. A later run recomputes it and skips the component only when
  it is unchanged, so a new advisory on the same component — or a withdrawn one —
  makes it actionable again automatically.

To force a retry of one component:

```bash
docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval \
  'db.unfixable_findings.deleteOne({_id: "pkg:maven/group/artifact@1.2.3"})'
```

To see what is currently suppressed:

```bash
docker exec simonrowe-dev-monorepo-mongodb-1 mongosh software_factory --eval \
  'db.unfixable_findings.find({}, {reason: 1, vulnerabilityIds: 1}).pretty()'
```

Two invariants to preserve if you ever change this:

- **The fingerprint is computed in Java, from Dependency-Track's data**
  (`ComponentFindings.fingerprint()`, its only producer). It is never emitted by
  the agent. A model-supplied key compared against a Java-computed one would
  never match, and suppression would silently stop working — the loudest symptom
  being a slowly rising agent bill, not an error.
- **The vulnerability ids must stay sorted.** The fingerprint is a string join;
  unsorted input means the same finding set produces different fingerprints on
  different runs, which also silently disables suppression.

Run history lives alongside it in `cve_fix_runs`, one document per Temporal
workflow id, if you need to see what a past run saw and pushed.

## The repair budget default is a guess

`factory.cvefix.ci.repair-budget` defaults to `3`, and `ci.max-wait` to `3h`
(three hours because one repair iteration costs up to the agent's 15-minute
timeout plus a full CI cycle, so a shorter cap would silently truncate the
documented budget).

**Three is an unmeasured guess.** Nobody has yet run the interactive
`dependency-cve-fix` skill against live Dependency-Track findings to see how many
CI iterations a realistic batch of bumps actually needs. Until someone does,
treat the number as provisional: if the first few real runs consistently end
`CI_UNRESOLVED` with the budget exhausted while making visible progress, raise it
(`factory.cvefix.ci.repair-budget`) and raise `max-wait` with it. If they
consistently go green on the first or second attempt, lower it — every wasted
iteration is agent spend plus an hour of a blocked queue.

## Failure boundaries

- **Dependency-Track down or the key unauthorised**: the run fails in its first
  activity with a loud error and no branch is touched. Nothing is left open, so
  the next scheduled run simply tries again.
- **Agent produces no bump**: `NOTHING_FIXABLE`. Nothing is pushed and no pull
  request is opened.
- **Agent touches a file outside the four-path allowlist**: the push never
  happens; the run fails. Inspect the activity failure in Temporal.
- **Claude turn exhaustion**: same shape as code review — the CLI reports why it
  stopped as JSON on stdout, not stderr. See the software-factory runbook's note
  on `CLAUDE_MAX_TURNS`.
- **Temporal unreachable while the feature is enabled**: the schedule
  initializer runs at startup, so a genuine failure there fails the boot loudly
  and Compose restarts the container. With the feature disabled it makes no
  Temporal call at all, which is why the flag gates the initializer rather than
  the initializer swallowing errors.
- **A dry run leaves two things behind, not none.** The pushed branch is
  harmless — the next real run force-pushes over it — but if you want it gone,
  delete `chore/dependency-cve-fixes` on the remote. The `unfixable_findings`
  rows it wrote are the ones that matter: they make later real runs skip those
  components until their finding set changes. Clear a row (see
  [Unfixable findings](#unfixable-findings)) if you want the next run to try it
  again.
