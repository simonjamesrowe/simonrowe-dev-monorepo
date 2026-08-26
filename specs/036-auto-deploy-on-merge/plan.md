# Implementation Plan: Auto-deploy on merge

**Branch**: `simonrowe/auto-deploy-on-merge` (Conductor workspace branch; feature
number `035`) | **Date**: 2026-08-26 | **Spec**: [spec.md](./spec.md)

**Input**: [spec.md](./spec.md), and the approved technical design at
`docs/superpowers/specs/2026-08-26-auto-deploy-on-merge-design.md` which is
authoritative for the *how*.

## Summary

A merge to `main` ends with the change live on the Pi. `software-factory` gains a
`workflow_run` branch on its existing signed webhook endpoint; on a successful
`Publish` for `main` it signal-with-starts a Temporal workflow on the fixed id
`deploy-prod`, carrying the head SHA. A new `deployer` container — the same
`FACTORY_IMAGE`, no ingress, holding the Docker socket and a read-write mount of
the deploy directory — polls the `deploy` task queue and runs phases of
`scripts/restart-prod.sh`: `sync-config` → `maintenance-on` → `pull` →
`recreate` → `verify` → `maintenance-off` → `verify-public`. On verification
failure it re-asserts the maintenance page, restores the recorded image IDs and
the recorded commit, verifies the restore, has a `Bash`-less Claude call diagnose
the captured evidence, and posts that as a commit comment and a GitHub issue.
nginx serves themed maintenance and unavailable pages driven by a flag file in a
`deploy-state` volume. The backend's `RedeployService` and its Docker socket
mounts are deleted. Both feature flags default off.

## Technical Context

**Language/Version**: Java 21 (`software-factory`, `backend`), Bash
(`scripts/restart-prod.sh`), TypeScript 5.x / React 19 (`frontend` — deletions
only), nginx conf, HTML/CSS (maintenance pages).

**Primary Dependencies**: Temporal Java SDK 1.36.0 via
`temporal-spring-boot-starter` (already present), Spring Boot 3.5.x,
Spring Data MongoDB, the existing `ClaudeCliRunner` / `ProcessRunner` /
`GitHubCredentials` components. **No new Gradle or npm dependency in any
module.** The `deployer` runtime image gains two apt packages (`curl`, `jq`).

**Storage**: MongoDB `software_factory` database — new `deploy_runs` collection.
Indexes created by a `@ConditionalOnProperty`-gated `ApplicationRunner`, matching
`CveFixIndexInitializer` (Mongock stays backend-owned).

**Testing**: JUnit 5 + `TestWorkflowEnvironment` (Temporal's test service) for the
workflow; `@WebMvcTest`-style controller tests for the webhook branch;
Testcontainers Mongo via the module's existing pattern for the repository; bash
test scripts under `scripts/test/` run with `DRY_RUN=1` and a throwaway
`STATE_DIR`; a scratch `git init`/`clone` fixture for `sync-config`; Vitest for
the frontend deletion; `nginx -t` plus a container-level check for the proxy conf.

**Target Platform**: Raspberry Pi (ARM64) running `docker-compose.prod.yml`,
Docker Compose v2, single node.

**Project Type**: Multi-module monorepo — Spring Boot services + React frontend +
host-side shell/compose/nginx configuration.

**Performance Goals**: A deploy completes inside the existing 420s container
settle budget plus pull time; the public window in which normal content is not
served stays under 10 minutes (SC-002). The nginx flag check is a per-request
`stat` on a tiny file — no measurable cost.

**Constraints**: Single node, so no zero-downtime scheme. Every phase must be
idempotent because Temporal retries activities. Bare
`./scripts/restart-prod.sh` must behave exactly as it does today. The deployer
must never recreate itself. The kernel memory cgroup is disabled on the Pi, so
`mem_limit` on the new service is declared for the future and enforces nothing
today.

**Scale/Scope**: One deploy at a time, a handful per week. ~9 new Java classes in
a new `com.simonrowe.factory.deploy` package, one rewritten shell script, one new
compose service, two static HTML pages, one nginx conf change, and the removal of
~5 backend/frontend redeploy artefacts.

## Constitution Check

*GATE: evaluated before Phase 0 and re-evaluated after Phase 1.*

| Principle | Status | Note |
| --- | --- | --- |
| I. Monorepo with separate containers | PASS | `deployer` is a new compose service reusing `FACTORY_IMAGE`; no new build. Compose remains the orchestration mechanism. |
| II. Modern Java & React stack | **VIOLATION — amendment required** | The principle mandates the admin-UI Docker redeploy and the backend `ProcessBuilder`. This feature deletes both. See Complexity Tracking. |
| III. Quality gates | PASS | Checkstyle (Google style, `maxWarnings = 0`), JaCoCo report on `software-factory`, Sonar, CycloneDX all unchanged and applied to the new code. Testcontainers used for the repository test. Deleting `RedeployServicesTest` *raises* backend coverage risk on the 0.78 floor — see the gate note below. |
| IV. Observability & operability | PASS, and improved | Deploy history becomes queryable in Mongo and in the Temporal UI rather than living in container logs. nginx routing rules unchanged in shape. |
| V. Simplicity & incremental delivery | PASS | One script not two, no new image, no new HTTP endpoint, no new dependency. Persistence is justified by a concrete read requirement (SC-010). |
| VI. Admin CMS UX standards | PASS | Only a card is removed; remaining cards keep their patterns. |
| VII. Interactive site tour | N/A | Untouched. |
| VIII. Backup & restore | N/A | Untouched. `deploy_runs` lives in `software_factory`, not the backend database, so `BackupService.BACKUP_COLLECTIONS` needs no change. |
| IX. Shell scripting standards | PASS | `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` resolution — all already present in `restart-prod.sh` and preserved by the rewrite. |

**Backend coverage gate**: `backend` enforces a JaCoCo floor of 0.78. Removing
`RedeployService` removes both covered and uncovered lines, so the floor could
move either way. The plan treats `./gradlew :backend:jacocoTestCoverageVerification`
as a required check after the deletion task rather than assuming it passes.

**Re-evaluation after Phase 1**: unchanged. No new violation was introduced by
the design artefacts; the Principle II violation is resolved by amendment
(Task 1), not carried.

## Project Structure

### Documentation (this feature)

```text
specs/036-auto-deploy-on-merge/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── restart-prod-phases.md      # the script's phase contract (the real "API")
│   ├── webhook-workflow-run.md     # the trigger contract
│   └── deploy-triage-schema.json   # the agent's structured-output contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit.tasks)
```

### Source Code (repository root)

```text
software-factory/src/main/java/com/simonrowe/factory/
├── codereview/webhook/GitHubWebhookController.java   # MODIFIED: workflow_run branch
└── deploy/                                           # NEW package
    ├── agent/DeployTriageEngine.java                 # ClaudeCliRunner, Read-only, no Bash
    ├── agent/TriageEngine.java
    ├── api/DeployAccepted.java
    ├── api/DeployWorkflowService.java                # signalWithStart on "deploy-prod"
    ├── config/DeployProperties.java                  # @ConfigurationProperties("factory.deploy")
    ├── config/DeployTaskQueues.java                  # DEPLOY = "deploy"
    ├── domain/DeployPhase.java
    ├── domain/DeployProgress.java
    ├── domain/DeployRequest.java
    ├── domain/DeployResult.java
    ├── domain/DeployStatus.java
    ├── domain/PhaseOutcome.java
    ├── domain/SyncOutcome.java
    ├── github/DeployReportGateway.java               # issue + commit comment
    ├── github/DeployReportRenderer.java
    ├── persistence/DeployIndexInitializer.java
    ├── persistence/DeployRunRecord.java
    ├── persistence/DeployRunRepository.java
    ├── shell/PhaseRunner.java                        # ProcessRunner wrapper for the script
    └── workflow/
        ├── DeployWorkflow.java
        ├── DeployWorkflowImpl.java                   # @WorkflowImpl(taskQueues = DEPLOY)
        ├── DeployActivities.java
        └── DeployActivitiesImpl.java                 # @ConditionalOnProperty(factory.deploy.enabled)

software-factory/src/main/resources/
├── application.yml                                   # MODIFIED: factory.deploy block + workflow package
└── deploy-triage-schema.json                         # NEW

software-factory/src/test/java/com/simonrowe/factory/
├── codereview/webhook/GitHubWebhookControllerTest.java   # MODIFIED
└── deploy/
    ├── agent/DeployTriageEngineTest.java
    ├── config/DeployPropertiesTest.java
    ├── config/DeployWorkerRegistrationTest.java       # the flag-gates-activities assertion
    ├── github/DeployReportGatewayTest.java
    ├── persistence/DeployRunRepositoryTest.java
    └── workflow/
        ├── DeployWorkflowTest.java
        └── DeployActivitiesImplTest.java

scripts/
├── restart-prod.sh                                   # REWRITTEN: phase dispatch, jq, run_cmd
└── test/
    ├── run-tests.sh                                  # NEW entrypoint
    ├── test-restart-prod-phases.sh                   # NEW
    └── test-restart-prod-sync-config.sh              # NEW (scratch clone)

config/nginx/
├── nginx-proxy.conf                                  # MODIFIED: flag + error_page on www/api
└── maintenance/
    ├── maintenance.html                              # NEW
    └── unavailable.html                              # NEW

docker-compose.prod.yml                               # MODIFIED: deployer service, nginx mounts,
                                                      # deploy-state volume, pull_policy, backend
                                                      # mount removal
Dockerfile.software-factory                           # MODIFIED: curl + jq in the runtime stage

backend/src/main/java/com/simonrowe/dataops/
├── RedeployService.java                              # DELETED
├── RedeployProperties.java                           # DELETED
└── DataOperationsController.java                     # MODIFIED: /redeploy removed
backend/src/main/resources/application.yml            # MODIFIED: redeploy: block removed
backend/src/test/java/com/simonrowe/dataops/
└── RedeployServicesTest.java                         # DELETED

frontend/src/services/dataOperationsApi.ts            # MODIFIED: startRedeploy removed
frontend/src/pages/admin/DataOperationsAdmin.tsx      # MODIFIED: card, dialog, isRedeploy branch

.specify/memory/constitution.md                       # MODIFIED: Principle II amendment (2.0.0)
docs/runbooks/deploy.md                               # NEW
docs/runbooks/software-factory-manual-actions.md      # MODIFIED: workflow_run subscription
.claude/skills/prod-deploy/SKILL.md                   # MODIFIED: deployer staleness step
CLAUDE.md                                             # MODIFIED: Recent Changes + prod section
.github/workflows/ci.yml                              # MODIFIED: shell test step
```

**Structure Decision**: the feature spans the existing `software-factory` Gradle
module (new `deploy` package, sibling to `codereview`, `cvefix` and `feedback`),
the host-side configuration at the repository root (`docker-compose.prod.yml`,
`config/nginx/`, `scripts/`), and deletions in `backend` and `frontend`. No new
module and no new deployable artefact — the `deployer` service is a second
instance of the image `Dockerfile.software-factory` already builds.

## Phase ordering and why

The tasks are ordered so that no intermediate state is worse than the current
one, which matters because this branch will be merged before the operator turns
anything on.

1. **Constitution amendment** — required by the governance procedure *before*
   implementation begins, and it is the change that authorises the deletions.
2. **`restart-prod.sh` rewrite + its shell tests** — everything else calls it,
   and it is the piece with no test safety net today. Doing it first, verified
   under `DRY_RUN=1`, means the Java side is built against a script whose
   contract is already pinned.
3. **nginx maintenance pages + conf** — independent of the Java side and
   independently verifiable. Landing it before the deployer means step 3 of the
   rollout (render the pages by hand) can be done without any Java change live.
4. **Java: config, persistence, gateways, agent** — the leaves.
5. **Java: activities, then workflow, then the webhook branch** — the trigger
   goes last so no path can start a workflow before there is one to start.
6. **Compose: `deployer` service, nginx mounts, volume, `pull_policy`** — the
   wiring, once the things it wires exist.
7. **Deletions: backend redeploy, then its mounts, then the frontend card** —
   after the replacement exists, so there is never a window with neither.
8. **Docs, runbook, skill, CLAUDE.md, CI step.**

## Key risks this plan carries

- **Two JVMs, one image, one queue.** Resolved by gating the *activities* bean;
  see research §11. The gate has a dedicated test because its failure mode is
  intermittent and silent.
- **`sync-config` mutates the host.** The only genuinely new capability. Fenced
  by clean-tree, pinned-URL anonymous fetch, ancestor assertion, `--ff-only`, and
  the recreate allowlist — and its tests run against a scratch clone, never the
  real deploy directory.
- **`restart-prod.sh` is the human deploy path.** A regression here breaks the
  documented manual recovery route. The `all` phase must be byte-for-byte
  equivalent in behaviour, which is what the phase tests assert.
- **Deleting the backend redeploy could move the JaCoCo floor.** Verified
  explicitly rather than assumed.
- **The deployer does not self-update.** Accepted; mitigated by putting the
  manual step in the runbook *and* the `prod-deploy` skill.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| Constitution Principle II mandates the admin-UI Docker redeploy and the backend `ProcessBuilder`; this feature deletes both | The principle encodes a mechanism that was never trusted and that forces the container serving the public API to hold `/var/run/docker.sock` — host-root capability behind the public API — plus a copy of `docker-compose.prod.yml` and `.env`. Removing it is the largest single security improvement in this change (SC-007, FR-047–FR-049). | Keeping both paths was rejected: it retains the socket on `backend` (so the risk is unchanged) while adding a second deploy mechanism, which is worse than either alone. Amending the principle rather than silently violating it is what the governance section requires, so the amendment is Task 1, versioned 2.0.0 (a principle redefinition), and states the replacement rule positively so the next feature to reach for a backend `ProcessBuilder` is refused by the constitution rather than by memory. |
| A second container from the same image, rather than a minimal deploy image | The failure path needs the Claude binary and the GitHub App key; a minimal alpine+docker-CLI image has neither, and adding them would duplicate `Dockerfile.software-factory` almost exactly. | A second Dockerfile was rejected as duplication; putting the socket on `software-factory` was rejected because that container terminates untrusted internet traffic. |
| Temporal used as the trigger channel rather than an HTTP call to the deployer | The deploy restarts `software-factory`, the process that received the trigger; only a durable workflow survives that. It also means the deployer needs no HTTP server, no port and no shared token. | An HTTP endpoint on the deployer was rejected: it would need a port, a token to protect, and would lose the request when the restart crossed it. |
