# Quickstart: Log watch

For whoever implements or operates this. Assumes familiarity with `cvefix`, which this mirrors.

## Before you start

**Check Loki is ingesting.** As of 2026-08-31 it is not — the free-tier monthly allowance is spent
and the tenant's ingest rate is `0 bytes/sec`. It resets at 00:00 on 1 September.

```bash
docker logs --since 30m simonrowe-dev-monorepo-alloy-1 2>&1 \
  | grep 'final error sending batch' | tail -1 | sed 's/.*host=//'
```

Silence from that command is the good outcome. A `429 ... limit: 0 bytes/sec` means the module can
be built and unit-tested but **cannot be tuned or rolled out** — Phases 2–4 proceed, Phase 5 waits.
See `docs/runbooks/log-shipping.md`.

## Build order

Phases 2–4 are mergeable now; each is independently testable.

### Phase 2 — core module

1. `LogWatchProperties`, `LogWatchTaskQueues`. **Every flag defaults off** (Constitution II).
2. `LokiClient` — strip `/push`, nanosecond timestamps, basic auth. `MockRestServiceServer` tests.
3. `SeverityDetector` and `SignatureExtractor` — **pure, no Spring, no clock, no client.** This is
   where most of the test effort goes; drive them from `src/test/resources/logwatch/fixtures/`.
4. `LogWatchWorkflowImpl` + `LogWatchActivitiesImpl`. Copy `CveFixWorkflowImpl`'s structure,
   including the `LinearActivities` stub with `setTaskQueue(LinearTaskQueues.LINEAR)`.
5. Run record, index initializer, schedule initializer.
6. `ModulePrerequisites` (+`LOGWATCH` key and `KEYS` entry), `FactoryStatusService`, the backend
   proxy, the frontend union and console row.

### Phase 3 — liveness

7. `SourceHealthChecker` — pure, over `(lineCount, distinctContainers, windowDuration,
   alloyComponentStatus)`.
8. Wire `CHECKING_SOURCE` as the **first** phase.
9. File a `SILENT`/`UNREACHABLE` result as an ordinary `IssueFiling` with
   `keyParts = ["source-health", status]`.

### Phase 4 — deploy trigger

10. `DeployProperties.logWatchTriggerEnabled`; travels on the deploy request.
11. Hook `DeployWorkflowImpl.finish` on success statuses only, five-minute delay, failure recorded
    in the deploy's progress and **never rethrown**.

### Phase 5 — tuning (blocked until Loki ingests)

12. Replace provisional fixtures with real Loki lines; settle `minimum-occurrences` and `max-per-run`.

## Gotchas that will cost you a day each

- **`${SUDO:-sudo}` vs `${SUDO-sudo}`** — not this module, but the same class of bug bit twice
  during the log-shipping fix. The colon form treats an explicitly empty value as unset.
- **A class-level `@ConditionalOnProperty` is evaluated by the component scanner.** Declare
  `LogWatchActivitiesImpl` through an explicit `@Bean` method and the annotation is silently
  ignored — which would put the Grafana credential in the socket-holding `deployer`. This is why
  `LogWatchWorkerRegistrationTest` component-scans instead of wiring beans.
- **Both containers will register a *workflow* poller on the `logwatch` queue.** `@WorkflowImpl`
  scanning is unconditional. Harmless, same as `deploy`. **Do not "fix" it.**
- **`temporal task-queue describe --task-queue logwatch`** should show workflow *and* activity
  pollers on `software-factory`. A healthy container with **no poller** runs nothing while the
  console still says enabled — always assert the poller, not the healthcheck.
- **The Loki endpoint already contains `/loki/api/v1`.** See `contracts/loki-query.md`.
- **Nanosecond timestamps.** Seconds are accepted and silently return empty.
- **Never bump `Fingerprint.VERSION`.** It orphans every `deploy` and `cvefix` ticket.
- **`logwatch_runs` keys on the run id, not the workflow id.**
- **Do not run `.specify/scripts/bash/update-agent-context.sh`** on `CLAUDE.md`.

## Verifying a real run

```bash
# poller present?
temporal task-queue describe --task-queue logwatch

# dry run from the console, or directly:
curl -s -X POST https://<factory>/api/logwatch/scan \
  -H "Authorization: Bearer $FACTORY_TRIGGER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"dryRun": true}'
```

Then read the run in `/admin/software-factory`. A dry run posts **nothing** anywhere, so its
outcome is visible only in run progress.

**What a first real run should look like**: loud. `WARN` is in scope and Elasticsearch, Kafka and
MongoDB all emit connection-retry warnings while the stack boots, so post-deploy scans will see
them. The minimum-occurrence filter and per-run cap bound the volume; cancelling each ticket makes
it permanently quiet. This is an accepted one-time cost, not a defect.

## Rollout

1. Merge with `FACTORY_LOGWATCH_ENABLED=false`. Nothing changes.
2. Confirm Loki is ingesting again.
3. Set the flag true; deploy; confirm pollers on the `logwatch` queue.
4. **Dry run first**, and read what it would have filed. This is the step the whole dry-run feature
   exists for — the signature rules cannot be validated any other way.
5. Adjust `minimum-occurrences` / `max-per-run` from what you see.
6. Real manual run. Confirm exactly one Linear issue per distinct problem, each carrying a
   fingerprint attachment.
7. Cancel the noise. Confirm the next run neither re-files nor comments on a cancelled signature.
8. Only then set `FACTORY_DEPLOY_LOG_WATCH_TRIGGER_ENABLED=true` on the **deployer**.

Step 8 last, deliberately: the post-deploy scan is the trigger most likely to surprise you, because
it runs over a window that always contains a full stack boot.

## The one thing not to lose sight of

This module exists because a three-week total logging outage produced **no signal anywhere**. If
the liveness check (FR-017–FR-020) gets dropped for scope, the module becomes a nightly green tick
over a blind spot — worse than not having it, because it manufactures confidence. If something has
to be cut, cut a trigger, not the liveness check.
