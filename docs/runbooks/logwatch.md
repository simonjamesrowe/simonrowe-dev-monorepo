# Log watch

The Software Factory's seventh module. Reads production container logs from Grafana Cloud Loki,
groups them into distinct problems, and files each one into Linear.

Related: [log-shipping.md](log-shipping.md) (the pipeline this depends on),
[linear.md](linear.md) (the sink it files through),
[software-factory.md](software-factory.md) (the container it runs in).

## What it does

| | |
| --- | --- |
| Task queue | `logwatch` |
| Flag | `FACTORY_LOGWATCH_ENABLED`, **off by default** |
| Schedule | `logwatch-daily`, every 24h, created **active** |
| Post-deploy | Five minutes after a successful deploy, behind
  `FACTORY_DEPLOY_LOG_WATCH_TRIGGER_ENABLED` on **`software-factory`** |
| Manual | **Log watch panel on `/admin/software-factory`**, or `POST /api/logwatch/scans` (token-protected, unrouted by nginx) |
| Files into | Linear, via the existing `linear` sink, producer key `logwatch` |
| Runs in | `software-factory` only — never `deployer` |

It reads `ERROR` and `WARN` lines, reduces each to a signature invariant to timestamps, UUIDs,
hex identifiers, paths, addresses and numbers, groups signatures by container + severity +
emitting code (see [Grouping](#grouping-what-makes-two-lines-the-same-problem) below), discards
**groups** occurring fewer than `minimum-occurrences` times, sorts most-severe-first, and files at
most `max-per-run`. The floor applies to the group, not to any single message text, and that
order matters: a coarser group has a higher combined count than any one of the message templates
inside it, so `minimum-occurrences` is now a looser filter than it looks. Two lines that each
previously stayed below the floor as two separate single-occurrence signatures now clear it
together the moment they share emitting code — the effective noise floor **dropped** when the
grouping got coarser, it did not stay the same.

Deduplication, suppression and reopening are **entirely** the sink's: cancel a ticket and that
signature goes quiet permanently; reopen it and reporting resumes. There is no logwatch-side
state for this, deliberately — `linear_issues` is an audit trail, never the source of truth.

## Grouping: what makes two lines the same problem

Before 046, grouping was `(container, whole-normalised-line)`. `SignatureExtractor.normalise`
masks timestamps, UUIDs, hex identifiers, paths, addresses and numbers, but not free text inside
a message — so `Agent 'ContentAggregation' must have…` and `Agent 'WeeklyDigest' must have…`
fingerprinted differently and filed two Linear tickets for one backend startup failure. Sixteen
tickets in Triage on 2026-09-06 were roughly eleven distinct problems, most visibly
SIM-13/SIM-24/SIM-25 (one Embabel validation failure, phrased three ways) and SIM-16/SIM-23 (one
Alloy log-shipping failure with two different `error=` payloads).

The key is now `(container, severity, discriminatedSource)`, computed by
`SignatureExtractor.group`. Severity is part of the key rather than relying on the level word
sitting inside the normalised text, so a format that logs its level out of band can't merge
`WARN` and `ERROR` by accident. The source is `SourceKeyExtractor.sourceKeyOf`, which identifies
the code that emitted the line across six formats, tried in order:

1. ECS JSON — `log.logger`
2. Temporal's zap JSON — the literal `msg` field
3. Alloy logfmt — `component_id`
4. Spring Boot's console layout — the logger before ` : `
5. a bare Java exception head — the fully-qualified class name
6. anything else — empty

**An unrecognised format falls back to the normalised whole line** — `line:<normalisedLine>`
instead of `logger:<source>` — which is exactly the pre-046 grouping behaviour, so a line
`SourceKeyExtractor` cannot parse is no worse off than before this change. The `logger:`/`line:`
prefix is load-bearing: without it, a source key whose text happened to equal some other line's
normalised form would silently merge two unrelated groups.

The distinct message templates within a group are carried as **variants** (`LogSignature.variants`),
most-frequent-first, and listed in the ticket body capped at `LogSignature.MAX_VARIANTS` (5) —
`LogSignature.distinctVariants` always reports the true, uncapped count, so a group with more
variants than the cap shows never silently looks smaller than it is.

Two deliberate limits:

- **SIM-11 versus SIM-13** — one incident, two different pieces of emitting code
  (`org.springframework.boot.SpringApplication` versus the Embabel validator) — stay two tickets.
  A source key cannot know two loggers belong to the same incident; merging on incident would
  need a different, much fuzzier mechanism. Pinned by a regression test in
  `SourceKeyExtractorTest` (`oneIncidentFromTwoLoggersStaysTwoSources`), so a future
  "improvement" that merges them fails the build instead of shipping.
- **SIM-19 versus SIM-20** — `MailHealthIndicator` and `HealthEndpointSupport` — describe one
  incident from two loggers and likewise stay two tickets, for the same reason. Recorded in
  `specs/046-linear-dedup-grouping/spec.md` as deliberately not addressed, rather than pinned by
  its own test — the mechanism is identical to SIM-11/SIM-13, so one regression test already
  covers it.

**Changing this key orphaned every pre-046 logwatch fingerprint, except the source-health
filing's.** Its key parts, `["source-health", <status>]` (see below), are unchanged, so a pre-046
cancellation of a source-health ticket still suppresses correctly. Every other logwatch
fingerprint is orphaned: re-filing every live problem under its new grouping is not one scan —
`max-per-run` (default 5) caps each run, and the roughly eleven distinct problems the 046
regrouping surfaces spread the one-time re-file over about three nightly runs plus the post-deploy
scan, not a single noisy morning. Any pre-046 cancellation of one of those orphaned tickets
stopped suppressing anything in the meantime — the old fingerprint it suppressed is never
computed again. `Fingerprint.VERSION` was deliberately not bumped: that would additionally have
orphaned `deploy` and `cvefix`, which have no duplicate-ticket problem to fix.

## The part that matters most: it knows when it cannot see

**An empty read is not a clean read.** Before interpreting anything, the scan establishes that its
source is alive, and reports `SOURCE_UNHEALTHY` rather than `NO_FINDINGS` when it cannot.

This is not defensive programming for its own sake. Between roughly 10 and 31 August 2026 Grafana
Cloud accepted no logs at all — the free-tier monthly allowance was spent, so the tenant's
ingestion rate was set to `0 bytes/sec` and Alloy dropped every batch with a `429`. Throughout:

- `alloy` was `Up (healthy)`; its healthcheck is `alloy --version`, which passes while every batch
  is discarded
- the read credential kept working, because ingest and query are separately gated
- a Loki query returned `{"status":"success"}` with an empty body

**A module without this check would have filed nothing and been self-consistently correct every
night for three weeks.** That is worse than having no module, because it puts a green tick on a
blind spot.

Two tiers, in order:

1. **Alloy's component API** (`http://alloy:12345/api/v0/web/components`). Direct evidence — it
   reports the actual `429` or `401`, which distinguishes an exhausted quota from a rejected
   credential from a genuinely quiet stack. All three look identical from the query side.
   Best-effort: Alloy publishes no host port, so this works only on the compose network, and any
   failure falls through to tier 2 rather than failing the scan.
2. **Container coverage.** If fewer than `minimum-containers` (default 3) produced any line over
   the window, the source is judged `SILENT`. Inference from silence, so it is **not applied to
   windows shorter than an hour** — a five-minute post-deploy window over an idle stack
   legitimately contains nothing, and filing a ticket after every quiet deploy would teach an
   operator to ignore exactly this signal.

A source-health failure is filed as an **ordinary finding** through the same sink, with key parts
`["source-health", <status>]`. It therefore dedupes, suppresses and re-arms like anything else,
with no special-case handling. The key parts deliberately exclude the evidence string: a `429`
whose byte counts differ every run must stay one recurring ticket, while a quota problem and a
rejected credential stay separate ones.

It files under `FilingMode.REFRESH`, same as every grouped signature — an unhealthy source
recurring across nights rewrites the same ticket's description rather than piling up a comment a
night. This mode change was made alongside the grouped findings' even though the key parts were
not touched, which the spec's Scope section did not call out in advance.

## Running a scan by hand

**From the admin console** — `/admin/software-factory`, the **Log watch** panel:

- **Dry run scan** — reads, groups and reports what it *would* file. Creates nothing.
- **Scan logs now** — files for real. One click, no confirmation step, matching the vulnerability
  scan: filing a Linear ticket is reversible by cancelling it, unlike the platform backup's
  upload.

Both are disabled unless the module reports `ready`, and the row shows why when it does not. Run
progress appears in the console; a dry run reports there and nowhere else.

The button labels are deliberately more specific than the panel needs — "Dry run" alone collides
with the platform-backup control and "Scan now" with the vulnerability one, and the accessible
name is all a screen reader gets.

**Or directly against the factory** (port **8090**, not 8080 — the factory's HTTP
server is the one nginx proxies `POST /webhooks/github` to):

```bash
# Dry run: reads, groups, reports - creates and comments on NOTHING in Linear.
curl -s -X POST http://software-factory:8090/api/logwatch/scans \
  -H "X-Factory-Token: $FACTORY_TRIGGER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"dryRun": true}'

# A specific window.
curl -s -X POST http://software-factory:8090/api/logwatch/scans \
  -H "X-Factory-Token: $FACTORY_TRIGGER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"windowStart":"2026-09-01T00:00:00Z","windowEnd":"2026-09-02T00:00:00Z"}'
```

A manual scan always gets a fresh workflow id, so re-scanning a window already scanned is never
rejected as a duplicate. An operator asking for a re-scan means it, and a `409` there would be
indistinguishable from the module being wedged.

Follow it with `GET /api/logwatch/scans/{workflowId}`, or on the status endpoint.

## Rolling it out

Order matters, and step 3 is the one not to skip.

1. **Check Loki is actually ingesting.** `docker logs <alloy> | grep 'status=4'` on the Pi should
   be silent. If it shows `429 ... limit: 0 bytes/sec`, stop — see
   [log-shipping.md](log-shipping.md). The module will work, but it will only ever tell you the
   source is dead.
2. Set `FACTORY_LOGWATCH_ENABLED=true`, deploy, and **assert a live poller**:
   `temporal task-queue describe --task-queue logwatch`. A healthy container with no poller runs
   nothing while the console still says enabled.
3. **Dry-run it and read what it would have filed.** The signature rules and the occurrence
   thresholds are estimates until they have been checked against real production lines. This is
   the step the dry run exists for.
4. Adjust `minimum-occurrences` / `max-per-run` from what you see.
5. Let it file for real. Confirm one Linear issue per distinct problem, each with a fingerprint
   attachment.
6. Cancel the noise. Confirm the next scan neither re-files nor comments on a cancelled
   signature.

**Expect the first runs to be loud.** `WARN` is in scope and Elasticsearch, Kafka and MongoDB all
emit connection-retry warnings while the stack boots. The occurrence filter and the per-run cap
bound the volume; cancelling makes each one permanently quiet. This is an accepted one-time cost,
not a defect. If it proves unreasonable, narrowing to `ERROR` is a one-line configuration change
and an expected outcome rather than a failure of the design.

## Gotchas

- **`GRAFANA_CLOUD_LOKI_ENDPOINT` is the *push* URL and already contains `/loki/api/v1`.**
  `LokiClient.queryBase()` strips the trailing `/push`. Appending `/api/v1/...` to the raw value
  yields `/loki/api/v1/api/v1/...` and a bare `404 page not found` — plain text, no JSON, no hint.
  The client says so in the error message for exactly this reason.
- **Loki timestamps are nanoseconds.** A seconds value is accepted and silently returns an empty
  result for a window about fifty years wide in the wrong place — an empty success, the one shape
  this module must never misread as clean.
- **Both containers register a *workflow* poller on the `logwatch` queue.** `@WorkflowImpl`
  scanning is unconditional. Harmless — a workflow only schedules activities — and the same shape
  as the `deploy` queue. Do not "fix" it. What confines the Grafana credential is
  `LogWatchActivitiesImpl`'s class-level `@ConditionalOnProperty`, which is evaluated by the
  **component scanner**: declaring that class through an explicit `@Bean` method would register it
  unconditionally and silently ignore the annotation.
- **The level word is part of the signature.** `WARN slow query` and `ERROR slow query` are two
  problems, deliberately: a message that has started erroring rather than warning is a change
  worth its own ticket.
- **A varying status code collapses into one signature**, because bare numbers normalise. "The
  send failed with a status" is one problem whose status varies; the example line on the ticket
  carries the real code. Splitting on it would file a fresh ticket for every status a flapping
  upstream returns.
- **`Fingerprint.VERSION` must not be bumped.** It would orphan every ticket already filed by
  `deploy` and `cvefix` as well as this module's.
- **`logwatch_runs` keys on the Temporal run id, not the workflow id.** The scheduled workflow id
  is stable, so keying on it would collapse all history into one document.

## Closing tickets again: the absence sweep

A factory that only ever opens tickets is half an automation. The other half runs at the end of
every scan: any problem this module filed and has **not reported for seven days** gets a comment
and is moved to Done.

```
scan -> file what is happening -> sweep what has stopped
```

The comment says what was observed, not what was concluded:

> Closing automatically: this has not appeared in a log scan for 7 day(s).
>
> That is an observation, not a verdict — logs also go quiet when a service is stopped or a
> problem is intermittent. If it happens again, the next scan will file a linked regression
> rather than losing it, so there is nothing to keep this open for.

That last sentence is the reason an automatic close is safe rather than nerve-wracking. The
fingerprint attachment survives closure, so a recurrence resolves through the normal precedence to
`FILED_REGRESSION` — a new ticket, linked to the closed one, saying it came back. **Being early
costs one extra linked ticket, not a lost report.** It is also why the sweep marks issues
**Done** and never **Cancelled**: the sink reads a cancelled issue as "never tell me again", so an
automatic cancel would permanently suppress a problem that had merely paused.

### The four conditions

The sweep runs only when the scan can stand behind its own silence. Each of these is a way it
could otherwise close a ticket about a problem that is still happening:

| Condition | What goes wrong without it |
| --- | --- |
| The source was healthy | An ingest outage reads as universal success, and the sweep closes the whole backlog — including the ticket the same run just filed to say the module cannot see |
| The read was not truncated | A read that hit its line budget examined an unknown part of the window, so a missing signature is missing for want of looking |
| Nothing was dropped by the per-run cap | **The subtle one.** The cap (default 5) limits how many signatures are *filed*, not how many were *seen*. With six live problems the sixth never reaches the sink, its `lastSeenAt` never advances, and it looks exactly like a problem that stopped — so a busy stack closes the tickets about its own busiest failures |
| The window is at least an hour | The post-deploy scan covers about five minutes, in which almost every known problem is absent purely because five minutes is short |

The cap condition has a useful consequence: **while the backlog is over `max-per-run`, the sweep
is inert**, and it comes into effect as the backlog shrinks. That is the right way round.

The window condition is enforced structurally in `LogWatchWorkflowImpl`, not just by the
post-deploy caller passing `resolveWhenClear = false` — so a future trigger added by someone who
has not read that comment still gets the safe behaviour.

### What it will not touch

- **An issue somebody has started.** A candidate in a `started` state is left exactly as it is and
  counted separately. Someone is mid-fix, and the logs going quiet is very often *because* of what
  they are doing — a service stopped, a config reverted, a branch deployed. Triage, backlog and
  unstarted issues are fair game precisely because nobody has claimed them.
- **Another producer's tickets.** The sweep is scoped by producer key, so it can never close a
  `cvefix` or `deploy` ticket. An absent CVE finding means something different, and an absent
  one-shot deploy failure means nothing at all.
- **The description.** Only a comment and a state change; the ticket still says what it said.

### Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `FACTORY_LOGWATCH_RESOLVE_WHEN_CLEAR` | `true` | The only flag in this module that defaults ON |
| `FACTORY_LOGWATCH_RESOLVE_AFTER` | `7d` | Seven consecutive nightly scans |

Do not shorten `resolve-after` much. A problem on a weekly cadence would then be closed and
re-filed every week, which is the duplicate-ticket disease 046 exists to cure.

`factory.linear.dry-run` and a dry-run scan both preview it: the report says what *would* close
and nothing is written. **Two independent flags, and both are needed.** The configured one is the
sink's standing posture; the request-level one is a single caller asking for a preview, which is
what the console's "Dry run scan" button sends. `AbsenceSweep.dryRun` carries the second, and the
sink honours whichever is set — omitting it was a real bug caught in review on #161, where a
manual dry-run scan answered "nothing will be filed" and then closed real tickets, because only
the configured flag was consulted.

The sweep deliberately still *runs* on a dry run rather than being short-circuited in the
workflow: a preview that silently skips half the run is not a preview.

### When it closes nothing

Read the run detail, which distinguishes four cases that all look like "nothing happened":

- silence — nothing was quiet long enough, or everything quiet is already closed;
- `N ticket(s) look resolved but someone is working on them` — the started-state rule;
- `... but the Linear team has no Done state to close them into` — a real misconfiguration;
- `the per-run cap dropped N signature(s), so no ticket was closed as resolved` — the backlog is
  over the cap.

## What it deliberately does not do

- **Any remediation whatsoever.** It observes and files. It never restarts, redeploys, edits code
  or opens a pull request.
- **Container health, restart counts, exited containers.** `scripts/monitor-prod.sh` already
  watches and remediates those every minute.
- **HTTP probing of the public hostnames.**
- **Application-level signals the backend already holds** — Langfuse guardrail scores, failed
  narrations, stuck article summaries.

## The post-deploy scan

A successful deploy schedules a scan to start **five minutes later**, over the window from deploy
completion to whenever it actually runs. Off by default, behind
`FACTORY_DEPLOY_LOG_WATCH_TRIGGER_ENABLED`.

**Two flags, and both live on `software-factory` — for different reasons.**

- `factory.logwatch.enabled` registers the activity that reads Loki. It must never be on
  `deployer`, which holds the Docker socket.
- `factory.deploy.log-watch-trigger-enabled` gates whether a deploy schedules a scan. It is read
  by `DeployWorkflowService` when it **builds the DeployRequest**, and that runs on
  `software-factory` because that is the container terminating the signed webhook.

**Putting the trigger flag on `deployer` is the obvious mistake and it makes the feature
permanently inert** — the flag the code actually reads stays at its `false` default, so no scan is
ever scheduled, with no error anywhere. It is the same mistake `FACTORY_DEPLOY_TRIGGER_ENABLED`
made in 036, which is why that variable is documented in the compose file as deliberately absent
from `deployer`. The deployer needs no copy: the workflow reads the value off the request.
`DeployerGrafanaCredentialTest` asserts the deployer carries **neither** flag and that
`software-factory` carries the trigger one.

**It cannot fail, delay or roll back a deploy** (FR-012). Three mechanisms, all needed:

1. The flag is checked before the activity is scheduled at all — with log watch off nothing polls
   its queue, so an unguarded schedule would stall the deploy until schedule-to-close rather than
   failing in milliseconds.
2. It runs on the workflow's `fast` activity stub, so it is bounded by a short timeout.
3. Every failure is caught and appended to the deploy's own detail, never rethrown.

**Only success schedules anything.** `DEPLOYED_IMAGES_ONLY` counts — a held-back config sync still
put new images into production. A deploy that failed and rolled back schedules nothing, because
the window would describe the rollback rather than the change.

Five minutes rather than immediately because a freshly recreated stack is still settling, and
scanning during that returns boot-noise warnings rather than whatever the change actually broke.
The scan's workflow id is `logwatch-postdeploy-<deploy run id>` under `REJECT_DUPLICATE`, so an
activity retry that already succeeded cannot schedule a second scan for the same deploy.

**The Linear flag is passed through from the deploy request**, not read on the deployer — which
holds no `FACTORY_LINEAR_ENABLED` by design. Reading it locally would resolve to `false` and every
post-deploy scan would run and file nothing, silently. The deploy request's value was resolved on
`software-factory`, which does know.

### Turning it on

Do this **last**, after the module itself has been dry-run and tuned:

```bash
# in the host .env, then recreate software-factory - the container that reads it
FACTORY_DEPLOY_LOG_WATCH_TRIGGER_ENABLED=true
docker compose -f docker-compose.prod.yml up -d --no-deps software-factory
```

It is the trigger most likely to surprise you, because it runs over a window that always contains
a full stack boot.
- **Signature tuning against real fixtures.** The test fixtures are real production lines captured
  with `docker logs` on the Pi, because Loki held nothing while this was written. They should be
  replaced and extended from Loki once it has been ingesting for a while.
