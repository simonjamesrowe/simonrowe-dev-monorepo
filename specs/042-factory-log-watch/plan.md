# Implementation Plan: Log watch — production log health into Linear

**Branch**: `simonrowe/observability-scan-trigger` | **Date**: 2026-08-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/042-factory-log-watch/spec.md`

## Summary

A seventh Software Factory module, `logwatch`, on a new `logwatch` Temporal task queue. It reads
production container logs from Grafana Cloud Loki over a window, reduces lines to timestamp- and
identifier-invariant signatures, and files each distinct problem into Linear through the existing
`linear` sink — which already supplies dedup, cancel-to-suppress and reopen-to-re-arm. Triggers:
a 24-hour schedule, five minutes after a successful deploy, and manual/dry runs from
`/admin/software-factory`.

The module is a **producer for the `linear` sink**, structurally a sibling of `cvefix`: same
workflow/activities/properties/run-record shape, same `IssueFiling` call, same
`ModulePrerequisites` registration. Almost nothing here is a new pattern. The two genuinely new
things are the **signature function** (pure, heavily unit-tested, the whole value of the feature)
and the **source-liveness check** (FR-017–FR-020), which exists because the module's silent
failure mode is otherwise a confident all-clear.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.x, `temporal-spring-boot-starter` 1.36.0,
Spring Data MongoDB, Spring `RestClient` (Loki HTTP), `ClaudeCliRunner` (the factory's existing
LLM path — no Spring AI in this module). **No new dependency in any module.**

**Storage**: MongoDB `software_factory` database — one new collection, `logwatch_runs`, keyed on
the Temporal **run** id. No new Mongo state for dedup: Linear is the source of truth, and
`linear_issues` stays an audit trail (established by 039).

**Testing**: JUnit 5 + Mockito in `software-factory/src/test`. The signature function and the
liveness decision are pure and carry the bulk of the tests. `WireMock`-style stubbing of Loki via
`RestClient` against a `MockRestServiceServer`. No Testcontainers — this module owns no
infrastructure.

**Target Platform**: the `software-factory` container only. Never `deployer`, which holds
`/var/run/docker.sock`.

**Project Type**: backend module in an existing multi-module Gradle build.

**Performance Goals**: one LLM call per run regardless of log volume (NFR-001); a scan bounded by
a configurable line budget so a runaway window cannot exhaust memory or wall-clock.

**Constraints**: the post-deploy trigger MUST NOT be able to fail, delay or roll back a deploy
(FR-012). Grafana credentials MUST NOT reach `deployer` (NFR-002).

**Scale/Scope**: ~20 MB/day of shipped logs across ~18 non-excluded containers in steady state.
A 24-hour window is a few tens of thousands of lines — small enough that the line budget is a
safety valve, not an operating constraint.

### Blocking context (read this before scheduling the work)

**Grafana Cloud Loki currently accepts nothing.** The free-tier monthly logs allowance was
exhausted in August 2026 (55 GB against 50 GB), so the tenant's ingestion rate is `0 bytes/sec`
and Alloy `429`s on every batch. Consequences for this plan, all real:

1. **The allowance resets at 00:00 on 1 September 2026**, at which point ingest resumes by itself.
   Nothing in this plan is blocked beyond that date.
2. **The production log fixtures FR-003 depends on cannot be sampled until then**, and the spec's
   own Open Questions already flag the min-occurrence and per-run-cap defaults as estimates. Phase
   3 (signature tuning) is therefore explicitly gated on real samples; Phases 0–2 are not.
3. **The module must be fully buildable and testable with no live Loki.** Every test stubs the
   HTTP layer. This is not a workaround for the outage — it is the correct design regardless, and
   the outage merely forces the discipline early.
4. **This outage is the direct motivation for FR-017–FR-020.** A `logwatch` built without them and
   deployed today would have reported "zero findings — all clear" every night for three weeks.
   See `docs/runbooks/log-shipping.md`.

### Resolved by research (see research.md)

- How liveness is proven (the spec's one open question) → **two-tier: Alloy component health when
  reachable, container-coverage inference otherwise.**
- Which HTTP client → `RestClient`.
- Where the post-deploy trigger hooks in → `DeployWorkflowImpl.finish`, success statuses only.

## Constitution Check

*GATE: passed before Phase 0. Re-checked after Phase 1 design — still passes.*

| Principle | Assessment |
| --- | --- |
| **I. Monorepo, separate containers** | PASS. No new container; a new package and task queue inside the existing `software-factory` image. |
| **II. Modern Java stack / deploy confinement** | PASS, and load-bearing. `LogWatchActivitiesImpl` carries a class-level `@ConditionalOnProperty(factory.logwatch.enabled)` so the component scanner never registers a Loki-querying activity inside `deployer`. No `ProcessBuilder` is added; the LLM call goes through the existing `ClaudeCliRunner`, which lives in `software-factory`, not `backend` — the `NoHostProcessLaunchTest` prohibition is scoped to `backend/src/main/java` and is not touched. Every flag is a `@ConfigurationProperties` record. |
| **III. Quality gates** | PASS. Checkstyle (Google style) and JaCoCo report on `software-factory`; SonarQube on the PR. No manual gate override. No Testcontainers needed — this module integrates no infrastructure of its own, and the `AbstractIntegrationTest` rule applies to `@SpringBootTest`, which only the worker-registration test uses. |
| **IV. Observability & operability** | PASS, and this feature *is* an instance of it. Note the principle's own words: "Debugging MUST NOT require SSH access or log tailing on hosts" — the August outage required exactly that, which is the gap being closed. Alloy stays the collector; nothing here changes the pipeline. |
| **V. Simplicity & incremental delivery** | PASS with one thing to watch. The five user stories are independently deliverable and P1–P2 ordered. The liveness check adds surface area, so it is deliberately implemented as *one more finding through the existing sink* rather than a parallel alerting path — see the Complexity Tracking table. |
| **VI. Admin CMS UX** | PASS. One more row in the existing `/admin/software-factory` table, same components, Lucide icons. |
| **VII. Interactive tour** | N/A. |

**No violations requiring justification.** The one judgement call is recorded below.

## Project Structure

### Documentation (this feature)

```text
specs/042-factory-log-watch/
├── spec.md              # Feature specification (liveness added 2026-08-31)
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── logwatch-api.md          # the two HTTP endpoints this module adds
│   └── loki-query.md            # the Loki read contract, as consumed
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source code

```text
software-factory/src/main/java/com/simonrowe/factory/logwatch/
├── api/
│   ├── LogWatchController.java          # POST /api/logwatch/scan  (token-protected)
│   ├── LogWatchWorkflowService.java     # starts the workflow; mints ids
│   └── LogWatchScanAccepted.java
├── config/
│   ├── LogWatchProperties.java          # @ConfigurationProperties, every flag defaults off
│   ├── LogWatchTaskQueues.java          # LOG_WATCH = "logwatch"
│   └── LogWatchBeans.java
├── loki/
│   ├── LokiClient.java                  # RestClient; query_range + label reads
│   ├── LokiException.java
│   └── LogLine.java                     # container, timestamp, severity, raw text
├── domain/
│   ├── LogWatchPhase.java
│   ├── LogWatchProgress.java            # {phase, detail, signaturesFound}
│   ├── LogWatchRequest.java
│   ├── LogWatchResult.java
│   ├── LogWatchStatus.java
│   ├── LogSignature.java                # the grouped problem
│   ├── SourceHealth.java                # ALIVE | SILENT | UNREACHABLE + evidence
│   └── Severity.java
├── signature/
│   ├── SignatureExtractor.java          # PURE. The feature. Heavily tested.
│   └── SeverityDetector.java            # PURE. Format-agnostic (nginx/JVM/Kafka/ClickHouse).
├── health/
│   └── SourceHealthChecker.java         # PURE decision over (lines, containers, alloy status)
├── persistence/
│   ├── LogWatchRunRecord.java
│   ├── LogWatchRunRepository.java
│   └── LogWatchIndexInitializer.java
├── schedule/
│   └── LogWatchScheduleInitializer.java # 24h, created ACTIVE, preserves operator pause
└── workflow/
    ├── LogWatchActivities.java
    ├── LogWatchActivitiesImpl.java      # @ConditionalOnProperty(factory.logwatch.enabled)
    ├── LogWatchWorkflow.java
    ├── LogWatchWorkflowImpl.java        # @WorkflowImpl(taskQueues = LOG_WATCH)
    └── LogWatchReportRenderer.java      # deterministic fallback write-up

software-factory/src/test/java/com/simonrowe/factory/logwatch/
├── signature/SignatureExtractorTest.java        # fixture-driven, the bulk of the effort
├── signature/SeverityDetectorTest.java
├── health/SourceHealthCheckerTest.java
├── workflow/LogWatchWorkflowTest.java           # Temporal test environment
├── workflow/LogWatchReportRendererTest.java
├── loki/LokiClientTest.java                     # MockRestServiceServer
├── config/DeployerGrafanaCredentialTest.java    # NFR-002 — reads the compose file
└── config/LogWatchWorkerRegistrationTest.java   # component-scans; see note below
software-factory/src/test/resources/logwatch/fixtures/   # real production log lines

Touched, not created:
  software-factory/.../admin/ModulePrerequisites.java   # + LOGWATCH key, KEYS, prerequisites
  software-factory/.../admin/FactoryStatusService.java  # + the seventh module
  software-factory/.../deploy/workflow/DeployWorkflowImpl.java  # post-deploy trigger
  software-factory/.../deploy/config/DeployProperties.java      # + logWatchTriggerEnabled
  backend/.../factory/  (proxy for the console action)
  frontend/src/services/softwareFactoryApi.ts   # + 'logwatch' to the key union
  frontend/src/pages/admin/SoftwareFactoryAdmin.tsx
  docker-compose.prod.yml                        # FACTORY_LOGWATCH_*, GRAFANA_* on software-factory ONLY
  docs/runbooks/log-shipping.md                  # remove the "nothing detects this" gap
```

**Structure Decision**: mirror `cvefix` exactly. It is the closest sibling — scheduled, Linear-
filing, no git path, one consolidated LLM call — and copying its shape means the reviewer, the
status endpoint, the console and the run-progress plumbing all need no new concepts. The two new
sub-packages, `signature/` and `health/`, are pure functions deliberately separated from anything
that does I/O, so they can be tested exhaustively from fixtures with no stubbing at all.

## Phasing

Ordered so that everything not blocked by the Loki outage lands first.

| Phase | Contents | Blocked by Loki? |
| --- | --- | --- |
| **0. Research** | research.md — resolve liveness mechanism, HTTP client, deploy hook | No |
| **1. Design** | data-model.md, contracts/, quickstart.md | No |
| **2. Core module** | US1 + US2: Loki client, signature function, filing through the sink, run records, schedule, manual + dry run, console row | No — every test stubs HTTP |
| **3. Liveness** | US5: `SourceHealthChecker`, two-tier evidence, filed as an ordinary finding | No |
| **4. Deploy trigger** | US3: five-minutes-after-success hook, with the no-harm guarantees | No |
| **5. Signature tuning** | Replace estimated fixtures with real production lines; settle min-occurrence and per-run cap | **YES** — needs Loki ingesting again (≥ 1 Sep) |
| **6. Rollout** | Enable the flag, dry run, read what it *would* file, then unpause | **YES** |

Phases 2–4 are implementable and mergeable now. Phase 5 is the one that must wait, and it is
tuning rather than construction.

## Complexity Tracking

> One judgement call, recorded because it adds scope beyond the original spec.

| Decision | Why needed | Simpler alternative rejected because |
|---|---|---|
| Source-liveness check (FR-017–FR-020) is part of v1, not a follow-up | Without it the module's failure mode is a confident "all clear". The August 2026 outage is the proof: three weeks of no logs, every health signal green, and a `logwatch` deployed then would have filed nothing and been self-consistently correct every night. Shipping the module without this makes the observability gap *worse* — it puts a green tick on a blind spot. | "Ship v1 and add liveness later" was the obvious cut. Rejected because the module's whole claim is that a quiet night means a healthy system; until liveness exists that claim is unfounded, and an unfounded green is more dangerous than no signal. It is also cheap here (one pure class, one extra finding through an existing sink) and expensive later, once operators have learned to trust the quiet. |
| Liveness failures are filed as an ordinary finding through `linear`, not via a new alert path | Inherits dedup, cancel-to-suppress and reopen-to-re-arm with zero new state. | A bespoke alert path would be a second filing mechanism with its own suppression semantics to get wrong, for a category of one. Constitution V. |

## Notes carried forward from prior features

Recorded so they are not rediscovered the hard way:

- **`@WorkflowImpl` scanning is unconditional**, so *both* `software-factory` and `deployer` will
  register a workflow poller on the `logwatch` queue. Harmless — a workflow only schedules
  activities — and the same shape as the `deploy` queue. **Do not "fix" it.** What confines the
  Loki credential is `LogWatchActivitiesImpl`'s class-level `@ConditionalOnProperty`, evaluated by
  the **component scanner**: declaring that class through an explicit `@Bean` method would
  register it unconditionally and silently ignore the annotation. `LogWatchWorkerRegistrationTest`
  therefore component-scans rather than wiring beans directly (the `DeployWorkerRegistrationTest`
  pattern).
- **The Java-side gate is not sufficient on its own.** `DeployerGrafanaCredentialTest` reads
  `docker-compose.prod.yml` and fails the build if any variable whose name **contains** `GRAFANA`
  appears under `deployer` — containing, not prefixed, for the reason `DeployerLinearCredentialTest`
  documents.
- **`Fingerprint.VERSION` must not be bumped.** FR-008. Bumping it orphans every ticket already
  filed by `deploy` and `cvefix`.
- **The trigger flag travels on the request.** A `@WorkflowImpl` cannot inject Spring properties.
  With `factory.logwatch.enabled=false` nothing polls the `logwatch` queue, so an unguarded
  schedule would stall the *deploy* until schedule-to-close rather than failing in milliseconds —
  the `linearFilingEnabled` pattern.
- **Enabled is not the same as able to work.** `ModulePrerequisites` must learn about
  `GRAFANA_CLOUD_LOKI_ENDPOINT` / `_USER` / `GRAFANA_CLOUD_API_KEY`, and must not fail startup.
- **`logwatch_runs` keys on the Temporal run id, not the workflow id** — the scheduled workflow id
  is stable, so keying on it would collapse all history into one document (the `deploy_runs`
  lesson).
- **Do not run `.specify/scripts/bash/update-agent-context.sh`** on `CLAUDE.md`. It fails with
  `grep: repetition-operator operand invalid` and silently strips the lead line from existing
  entries. The `CLAUDE.md` entry for this feature is written by hand.
