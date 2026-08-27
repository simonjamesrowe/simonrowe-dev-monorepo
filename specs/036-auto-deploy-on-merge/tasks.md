---

description: "Task list for 036-auto-deploy-on-merge"
---

# Tasks: Auto-deploy on merge

**Input**: Design documents from `/specs/036-auto-deploy-on-merge/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/,
quickstart.md — plus the authoritative design at
`docs/superpowers/specs/2026-08-26-auto-deploy-on-merge-design.md`.

**Branch**: `simonrowe/auto-deploy-on-merge` (Conductor workspace branch — do not
create or switch branches).

**Tests**: INCLUDED. The design has an explicit Testing section, and two areas are
called out there as non-optional: phase-level shell coverage under `DRY_RUN=1`,
and `sync-config` against a scratch clone. Those are not "nice to have" — running
the script for real performs restarts, and `sync-config` is the only part of this
feature that mutates the host.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: `[US1]`…`[US6]`, mapping to the user stories in spec.md

## Path Conventions

Multi-module monorepo. `software-factory/src/{main,test}/java/com/simonrowe/factory/`,
`backend/src/`, `frontend/src/`, plus host-side `scripts/`, `config/nginx/`,
`docker-compose.prod.yml` at the repository root.

## Ordering note

US1, US2 and US6 are all P1. They are not implementable in that order, because
US6's tasks are *gates over* the classes US1 introduces. The execution order is
therefore Foundational → US1 → US2 → US6 → US3 → US5 → US4, and the safety US6
guarantees is preserved throughout by setting both flags to `false` in
**Foundational** (T017) rather than in US6 — so no intermediate commit on this
branch can deploy anything.

---

## Phase 1: Setup

**Purpose**: The governance change that authorises the deletions, and the two
image/harness prerequisites everything else assumes.

- [X] T001 Amend Constitution Principle II in `.specify/memory/constitution.md`: remove the "Docker redeploy MUST be available from the admin UI / backend MUST use `ProcessBuilder`" bullet and replace it with the positive rule (deployment is performed by a dedicated container holding the Docker socket, triggered over a durable workflow; the container serving the public API MUST hold no host-level container access and no copy of the deployment configuration or environment secrets). Bump version 1.11.0 → 2.0.0 (MAJOR — principle redefinition), update `Last Amended`, and write the Sync Impact Report comment at the top of the file in the existing style. Also drop the now-false `Docker redeploy` row from the Technology Stack Constraints table if present.

- [X] T002 [P] Add `curl` and `jq` to the **runtime** stage of `Dockerfile.software-factory` (the `FROM eclipse-temurin:21-jre` stage, alongside the existing `ca-certificates git`). `jq` currently exists only in the throwaway `claude` download stage. Comment why: the deployer runs `restart-prod.sh`, whose settle loop needs `jq` and whose hostname checks need `curl`, and the design deliberately drops the `python3` dependency.

- [X] T003 [P] Create `scripts/test/run-tests.sh` — `#!/usr/bin/env bash`, `set -euo pipefail`, discovers and runs every `scripts/test/test-*.sh`, reports pass/fail counts, exits non-zero on any failure. Exports `DRY_RUN=1` for all children so a test that forgets it cannot perform a real restart. Plain bash rather than `bats`: adding a shell test framework as a dependency for three files is unjustified under Constitution Principle V.

**Checkpoint**: `./scripts/test/run-tests.sh` runs and reports zero tests.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The deploy script's phase contract, and the Java leaves every later
phase depends on. Nothing here can deploy anything: both flags land `false`.

### The deploy script

- [X] T004 Rewrite `scripts/restart-prod.sh` per `contracts/restart-prod-phases.md`, **excluding** `sync-config` and `rollback-config` (those are US3): `main "$@"` dispatch on `${1:-all}`; a `run_cmd` wrapper honouring `DRY_RUN` on every mutating `docker` invocation (same name and shape as `monitor-prod.sh`'s, so the two scripts read alike); env knobs `SERVICES`, `IMAGE_TAG`, `STATE_DIR`, existing `VERIFY_TIMEOUT`/`VERIFY_POLL`; phases `all`, `maintenance-on`, `maintenance-off`, `pull`, `recreate`, `verify`, `verify-public`, `rollback`; exit codes 0/1/2/64 as specified. Keep `#!/usr/bin/env bash`, `set -euo pipefail` and `SCRIPT_DIR`/`PROJECT_DIR` resolution (Principle IX).

  **`all` must be behaviourally identical to today's script** — whole-file `pull`, whole-stack `up -d` with the failure recorded and deliberately **not** fatal, `restart nginx`, the settle loop, all six public hostnames, the same messages and exit code. Every existing explanatory comment must move with the code it explains: the "deliberately not fatal" note on `up -d`, the `created`-state explanation, the "a green `docker compose ps` is not proof the site serves" note, and the `VERIFY_TIMEOUT` rationale.

- [X] T005 In the same file, replace the inline `python3` settle-loop parser in `unsettled_containers` with `jq`, preserving all four cases: a container with a healthcheck that is not `healthy`; an `exited` container with a non-zero `ExitCode` (a clean one-shot is fine); a container that is neither `running` nor `exited` (**this is what catches `created`**); and a malformed line skipped rather than fatal. Output stays `name<TAB>state<TAB>detail` so the existing reporting loop is unchanged.

- [X] T006 In the same file, implement `pull` so it **truncates** `$STATE_DIR/rollback-images` at the start of the phase and then records `service<TAB>image-id` from `docker image inspect --format '{{.Id}}' "$image:latest"` before pulling. Comment why truncate and not append: Temporal retries activities, and an append would make a retried `pull` record the freshly-pulled image as the rollback target, so rollback would restore the broken version. A service with no local image records nothing and is not an error.

- [X] T007 [P] Write `scripts/test/test-restart-prod-phases.sh`: under `DRY_RUN=1` with a throwaway `STATE_DIR`, assert — `maintenance-on` creates the flag and is idempotent; `maintenance-off` removes it and is idempotent when already absent; `pull` truncates rather than appends `rollback-images` across two runs; `pull` with `IMAGE_TAG=<sha>` emits both a `docker pull …:<sha>` and a `docker tag …:<sha> …:latest`; `pull` with the default tag emits no `docker tag`; `recreate` emits `--no-deps --pull never` for each service in `SERVICES` and never for `deployer`; `rollback` re-tags from the recorded image IDs; an unknown phase exits 64; `verify` checks the four ops hostnames and not `www`/`api`; `verify-public` checks `www`/`api`. Also assert the `jq` parser classifies a `created` container as unsettled, using a fixture of `docker compose ps --format json` lines rather than a live Docker.

### Java: configuration and domain

- [X] T008 [P] Create `software-factory/src/main/java/com/simonrowe/factory/deploy/config/DeployTaskQueues.java` — final class, private constructor, `public static final String DEPLOY = "deploy"`, matching `CveFixTaskQueues`.

- [X] T009 [P] Create `.../deploy/config/DeployProperties.java` — `@ConfigurationProperties("factory.deploy")` record with every field and default from data-model.md, defaulted in the compact constructor in the `CveFixProperties` style. Include the Javadoc note that `enabled` and `triggerEnabled` are two fields on one record deliberately: they must move independently but are read together. Document that `recreatable` defaults to the eight-service allowlist and that `deployer` is absent because it excludes itself.

- [X] T010 [P] Create the domain records in `.../deploy/domain/`: `DeployRequest`, `DeployPhase` (with `argument()` returning the kebab-case script argument and `null` for `TRIAGE`/`REPORT`), `PhaseOutcome` (with `detail` bounded to 4000 chars), `SyncOutcome` + `SyncDecision`, `DeployStatus`, `DeployProgress` (with `accepted()`), `DeployResult` — all per data-model.md. Every one must be JSON-round-trippable across a Temporal boundary: no `Path`, no bare `Duration`.

- [X] T011 [P] Write `.../deploy/config/DeployPropertiesTest.java` — asserts every default, including that `recreatable` contains exactly the eight allowlisted services and does **not** contain `deployer`, and that `enabled`/`triggerEnabled` default to `false`.

### Java: persistence

- [X] T012 [P] Create `.../deploy/persistence/DeployRunRecord.java` — `@Document(collection = "deploy_runs")` record per data-model.md, with a static `idFor(String runId)`. Javadoc **must** explain the divergence from `CveFixRunRecord.idFor(workflowId)`: the workflow id here is the fixed constant `deploy-prod`, so keying on it would collapse every deploy in history into one document. Keys on the Temporal run id instead — unique per execution, stable across replays.

- [X] T013 [P] Create `.../deploy/persistence/DeployRunRepository.java` — `MongoRepository<DeployRunRecord, String>`, plus a `findTop20ByOrderByStartedAtDesc()` derived query for reading deploy history (SC-010).

- [X] T014 [P] Create `.../deploy/persistence/DeployIndexInitializer.java` — `ApplicationRunner` creating a `startAt`-descending index named `startedAt` on `DeployRunRecord`, `@ConditionalOnProperty(name = "factory.deploy.enabled", havingValue = "true")`. Copy the reason from `CveFixIndexInitializer`'s Javadoc: an unreachable Mongo must not fail the whole application context and take the webhook receiver and the `code-review` worker with it.

- [X] T015 [P] Write `.../deploy/persistence/DeployRunRepositoryTest.java` — Testcontainers Mongo, following `CveFixRepositoriesTest`. Assert a round-trip, that two records with different run ids coexist (the regression test for the id-strategy divergence), and that `findTop20ByOrderByStartedAtDesc` orders newest first.

### Java: wiring

- [X] T016 Add `com.simonrowe.factory.deploy.workflow` to `spring.temporal.workers-auto-discovery.workflow-packages` in `software-factory/src/main/resources/application.yml`, with a comment matching the existing `cvefix` one: without this entry no worker polls the `deploy` task queue and nothing ever deploys. Add the note that classpath scanning is unconditional, so this registers a workflow-task poller in **both** JVMs, and that the executor gate is `DeployActivitiesImpl`'s `@ConditionalOnProperty` (see research.md §11).

- [X] T017 Add the `factory.deploy` block to `software-factory/src/main/resources/application.yml` with **`enabled: ${FACTORY_DEPLOY_ENABLED:false}` and `trigger-enabled: ${FACTORY_DEPLOY_TRIGGER_ENABLED:false}`**, plus every other property from data-model.md bound to its `FACTORY_DEPLOY_*` environment variable. Landing the `false` defaults here — before any executing code exists — is what makes every later commit on this branch safe.

**Checkpoint**: `./gradlew :software-factory:test :software-factory:checkstyleMain` and `./scripts/test/run-tests.sh` both green. Nothing deploys; there is no workflow yet.

---

## Phase 3: User Story 1 — A merged change reaches production by itself (P1)

**Goal**: A successful `Publish` on `main` results, with no human action, in the
new images and the same-commit host configuration running in production, behind a
branded maintenance page while it happens, and recorded durably.

**Independent test**: With both flags on, merge a trivial change and confirm the
public site and API serve the new build, the deployed commit matches, and a
`deploy_runs` record names that commit with every phase passed.

### The maintenance page (US1 scenario 2 depends on it)

- [X] T018 [P] [US1] Create `config/nginx/maintenance/maintenance.html` — "Update in progress". A single self-contained file: **all CSS inlined, no external asset**, because the frontend that would serve those assets is precisely what is down. Styled from the site's own tokens (`--surface: #0f131c`, `--surface-container: #1c2029`, `--primary: #77d1ff`, `--on-surface: #dfe2ef`), Space Grotesk headings with a system fallback, Inter body — both as font stacks, not web-font loads. Includes a `<meta http-equiv="refresh">` so it retries itself.

- [X] T019 [P] [US1] Create `config/nginx/maintenance/unavailable.html` — same constraints, "Temporarily unavailable" wording for an unplanned outage rather than a deploy. (Wired up in US4; the file lands here so both pages are designed as one pair.)

- [X] T020 [US1] Modify `config/nginx/nginx-proxy.conf`: in the `simonrowe.dev www.simonrowe.dev` block and the `api.simonrowe.dev` block, add `error_page 503 = @maintenance;`, `error_page 502 504 = @unavailable;`, the `@maintenance` location (`root /etc/nginx/maintenance; try_files /maintenance.html =503; add_header Retry-After 120 always;`) and the `@unavailable` location. Declare both named locations **in each block** — nginx does not inherit them.

  **Put `if (-f /var/run/deploy-state/maintenance.on) { return 503; }` inside `location /`, not at server level.** At server level it would also 503 the `location = /webhooks/github` exact-match block, which is how this deploy was triggered and how the next one will be — GitHub would retry into a wall. Comment this. Leave the `default_server` block (which serves `/healthz`) and the four ops-hostname blocks entirely untouched.

- [X] T021 [P] [US1] Write `scripts/test/test-nginx-maintenance.sh` — run `nginx -t` against the conf in an `nginx:alpine` container, then start nginx with the maintenance mount and a `deploy-state` mount and assert: with the flag set, `Host: www.simonrowe.dev /` returns 503 with the themed body and a `Retry-After` header, and `Host: api.simonrowe.dev /` likewise; `/healthz` returns 200; `Host: api.simonrowe.dev POST /webhooks/github` is **not** 503; `Host: console.simonrowe.dev /` is not 503. With the flag absent and no upstream running, `www` returns the themed unavailable page rather than a raw nginx 502.

### The shell → Java bridge

- [X] T022 [P] [US1] Create `.../deploy/shell/PhaseRunner.java` — wraps `ProcessRunner` to run `bash <script> <phase> [sha]` with `SERVICES`, `IMAGE_TAG`, `STATE_DIR`, `REPO_URL` and `RECREATABLE` in the child environment, a heartbeat consumer for the Temporal activity, and `factory.deploy.phase-timeout` as the ceiling. Returns exit code plus captured stdout/stderr, and parses `key=value` lines from stdout into a map (the `sync-config` contract). Maps exit code 2 to a distinct "declined" result rather than a failure.

### The activities

- [X] T023 [US1] Create `.../deploy/workflow/DeployActivities.java` — `@ActivityInterface` with `runPhase(DeployPhase, String targetSha)` returning `PhaseOutcome`, `syncConfig(String targetSha)` returning `SyncOutcome`, `rollbackConfig(String previousSha)`, `recordRun(DeployRunRecord)`, `captureEvidence(...)`, `triage(...)` and `report(...)`. Javadoc each with what it may and may not do.

- [X] T024 [US1] Create `.../deploy/workflow/DeployActivitiesImpl.java` — `@Component`, `@ActivityImpl(taskQueues = DeployTaskQueues.DEPLOY)`, **and `@ConditionalOnProperty(name = "factory.deploy.enabled", havingValue = "true")`**. Implement `runPhase`, `syncConfig` (delegating to the script, US3 fills in the script side), `rollbackConfig` and `recordRun`. The `@ConditionalOnProperty` carries a comment stating it is the *only* thing confining the Docker socket to the `deployer` JVM: `@WorkflowImpl` classpath scanning is unconditional, so both containers register a workflow poller, and it is the absence of this bean that stops `software-factory` — which has no socket — from executing a deploy step.

- [X] T025 [US1] Create `.../deploy/workflow/DeployWorkflow.java` — `@WorkflowInterface` with `@WorkflowMethod DeployResult run(DeployRequest)`, `@SignalMethod void deployRequested(String sha)` and `@QueryMethod DeployProgress progress()`.

- [X] T026 [US1] Create `.../deploy/workflow/DeployWorkflowImpl.java` — `@WorkflowImpl(taskQueues = DeployTaskQueues.DEPLOY)`. Phase sequence `SYNC_CONFIG → MAINTENANCE_ON → PULL → RECREATE → VERIFY → MAINTENANCE_OFF → VERIFY_PUBLIC`. Activity stubs as instance fields (the module's established idiom), with separate options for the fast activities and the long `PULL`/`RECREATE`/`VERIFY` ones — `VERIFY` alone must allow the 420s settle budget plus headroom, and `PULL` must allow an ARM image pull on a Pi. Use `Workflow.currentTimeMillis()`, never `Instant.now()`. Record the run via `recordRun` on every exit path.

  Implement the **drain loop**: after a deploy attempt completes, if `deployRequested` has recorded a newer SHA, deploy again. Comment that without this, a merge landing mid-deploy signals a workflow that never reads the field again and its commit never deploys — the design's "or the one that starts next" is only true because of this loop.

- [X] T027 [US1] Create `.../deploy/api/DeployWorkflowService.java` and `.../deploy/api/DeployAccepted.java` — `signalWithStart` via `BatchRequest` against the fixed workflow id `deploy-prod` with `ALLOW_DUPLICATE` reuse policy, per `contracts/webhook-workflow-run.md`. Follows `ReviewWorkflowService`'s shape but needs no already-started catch: signal-with-start is idempotent by construction. Gate the bean on `factory.deploy.trigger-enabled` so a JVM that cannot trigger holds no client stub.

### The trigger

- [X] T028 [US1] Modify `software-factory/src/main/java/com/simonrowe/factory/codereview/webhook/GitHubWebhookController.java` — add a `workflow_run` branch **before** the existing `if (!"pull_request".equals(event))` early return, delegating to a private `handleWorkflowRun(JsonNode)` in the shape of `handleClosed`. Accept only when the trigger flag is on, `workflow_run.name == "Publish"`, `conclusion == "success"`, `head_branch == "main"`, the repository matches `factory.deploy.owner`/`.repository`, and `head_sha` is non-blank; otherwise return the existing `202 {"status":"ignored"}`. Read `installation.id`, passing `null` when absent or `0`. Comment why `pull_request closed` is deliberately not the trigger: merge precedes image availability, so deploying on merge would pull the previous `:latest` and report success.

- [X] T029 [US1] Extend `software-factory/src/test/java/com/simonrowe/factory/codereview/webhook/GitHubWebhookControllerTest.java` — `workflow_run` accepted for Publish/success/main/allowlisted-repo; ignored for a failed conclusion, `action: requested` (null conclusion), a non-`main` branch, another workflow name, a non-allowlisted repository, and the trigger flag off; unsigned still `401`; a body that is not JSON still `400`. Assert the existing `pull_request` behaviour is unchanged.

- [X] T030 [US1] Write `.../deploy/workflow/DeployWorkflowTest.java` (happy-path portion) — `TestWorkflowEnvironment`, activities mocked: the happy path runs the seven phases in order and returns `DEPLOYED`; a `recordRun` is persisted with every phase's outcome; two `deployRequested` signals arriving before the workflow starts coalesce into **one** deploy of the newer SHA; a signal arriving mid-deploy produces a second deploy via the drain loop; an activity that fails once and succeeds on retry does not double-apply (assert `runPhase` idempotency by call count).

- [X] T031 [P] [US1] Write `.../deploy/workflow/DeployActivitiesImplTest.java` — `runPhase` maps exit 0/1/2 to succeeded/failed/declined; `PhaseRunner` receives the right environment for each phase; `detail` is truncated to 4000 characters; `recordRun` upserts on the run id.

**Checkpoint**: US1 is independently testable. The workflow deploys images end to
end (`sync-config` is a no-op stub until US3), and the maintenance page renders.

---

## Phase 4: User Story 2 — A bad deploy undoes itself and explains why (P1)

**Goal**: A deploy that fails verification restores the previous images and
commit, verifies the restore, produces a written diagnosis from captured
evidence, and reports it — leaving the maintenance page up if the restore also
failed.

**Independent test**: Deploy a deliberately broken build; confirm production
returns to the previous version, the restore verifies, and a diagnosis naming the
failing component appears on the commit and as an issue, with no human action.

- [X] T032 [P] [US2] Copy `specs/036-auto-deploy-on-merge/contracts/deploy-triage-schema.json` to `software-factory/src/main/resources/deploy-triage-schema.json`.

- [X] T033 [P] [US2] Create `.../deploy/agent/TriageEngine.java` (interface) and `.../deploy/agent/DeployTriageEngine.java` — uses the shared `ClaudeCliRunner.runStructured` against `deploy-triage-schema.json`. Tool set is **`Read` only, scoped to the scratch evidence directory, and explicitly no `Bash`**: the agent is handed captured output and asked to explain it, exactly as `cvefix` hands over Dependency-Track findings. It never touches Docker, git or a credential. `ClaudeCliRunner` already strips the environment to `SAFE_SECRET_ENVIRONMENT` + `PROCESS_ENVIRONMENT`, so the GitHub App key and the Dependency-Track key are removed from the child process automatically — note that in the Javadoc so it is not "improved" later.

- [X] T034 [US2] Implement `captureEvidence` in `DeployActivitiesImpl` — writes `phase-output.txt`, `compose-ps.txt`, `container-logs.txt` (`docker compose logs --tail` for the services the settle loop reported unsettled) and `commit-range.txt` (`git log --oneline <previous>..<target>`) into a scratch directory under the workspace volume, and deletes it after the run. Bounded output per file so a crash-looping container's log cannot fill the volume.

- [X] T035 [P] [US2] Create `.../deploy/github/DeployReportGateway.java` — `POST /repos/{owner}/{repo}/issues` and `POST /repos/{owner}/{repo}/commits/{sha}/comments`, modelled on `CveFixPrGateway` (same client, same `GitHubCredentials.accessToken(installationId)` resolution at run time, same error handling). No configured installation id, for the reason `cvefix` documents: a configured-but-empty value makes `accessToken(null)` fall back to a `GITHUB_TOKEN` this service does not set — anonymous call, 403.

- [X] T036 [P] [US2] Create `.../deploy/github/DeployReportRenderer.java` — renders the issue title from `headline`, and a body carrying the diagnosis, the confidence, the failing services, the suspect commits, the suggested next step, the phase outcomes, whether a rollback was taken and whether it verified, and — when `sync-config` declined — the held-back services and the manual command. Renders the commit comment as a shorter form of the same.

- [X] T037 [US2] Implement `triage` and `report` activities in `DeployActivitiesImpl`, and add the rollback path to `DeployWorkflowImpl`: on a `VERIFY` or `VERIFY_PUBLIC` failure → `MAINTENANCE_ON` (re-asserted, because `verify-public` runs with the page down) → `rollbackConfig(previousSha)` **only when `SyncOutcome.decision() == APPLIED`** → `ROLLBACK` → `VERIFY` → `TRIAGE` → `REPORT`. Clear the maintenance page **only if the rollback verified clean**; leave it up otherwise and set `maintenancePageLeftUp`. Honour `factory.deploy.rollback-enabled`: when false, stop at `TRIAGE`/`REPORT` with `ROLLBACK_DISABLED` and the page still up.

  Comment that restoring the commit first is what makes the rollback run the *previous* version of `restart-prod.sh` — which is what matters when the thing that broke the deploy was a change to the script itself.

- [X] T038 [US2] Extend `DeployWorkflowTest.java` — verify-fails-then-rollback-succeeds returns `ROLLED_BACK` and clears the flag; verify-fails-and-rollback-fails returns `ROLLBACK_FAILED` and **asserts `maintenance-off` was never called**; `rollback-enabled = false` returns `ROLLBACK_DISABLED` with the page up; `rollbackConfig` is not called when `SyncOutcome` was anything other than `APPLIED`; `report` is called on every failure path.

- [X] T039 [P] [US2] Write `.../deploy/agent/DeployTriageEngineTest.java` (asserts the argv carries no `Bash` tool and that the evidence directory is the working directory) and `.../deploy/github/DeployReportGatewayTest.java` (issue and commit-comment requests, following `CveFixPrGatewayTest`).

- [X] T040 [US2] Modify `docker-compose.prod.yml`: change `pull_policy: always` → `pull_policy: missing` on `backend`, `frontend` and `software-factory`. Comment the reason at each: `scripts/monitor-prod.sh` runs a bare `docker compose up -d` every minute whenever anything is unsettled (lines ~284 and ~363), so with `always` the watchdog re-pulls the broken `:latest` within 60 seconds of a rollback completing and the site breaks again with nothing in the deploy log to explain it. It also currently means the watchdog can silently upgrade the backend as a side effect of healing an unrelated container. `restart-prod.sh` is unaffected — it runs `docker compose pull` explicitly.

**Checkpoint**: US2 independently testable. A failed deploy self-heals and
explains itself.

---

## Phase 5: User Story 6 — Nothing changes until an operator opts in (P1)

**Goal**: Merging deploys nothing; the two flags are independently switchable; a
broken deployer cannot silence code review.

**Independent test**: With defaults, a successful `Publish` deploys nothing and
errors nowhere. With the executor on and the trigger off, a hand-started deploy
runs end to end.

- [X] T041 [US6] Write `.../deploy/config/DeployWorkerRegistrationTest.java` — a Spring context test asserting `DeployActivitiesImpl` is **absent** from the context when `factory.deploy.enabled` is unset or `false`, and **present** when it is `true`; and that `DeployWorkflowService` is absent when `factory.deploy.trigger-enabled` is false. This is the test that keeps the Docker socket confined to the deployer: without it the gate can be removed by accident and the only symptom is an intermittent deploy failure on whichever JVM won the activity task. Say that in the class Javadoc.

- [X] T042 [US6] Extend `software-factory/src/test/java/com/simonrowe/factory/FactoryApplicationTest.java` (or add a sibling) asserting the application context still starts with every `factory.deploy.*` flag off and no Mongo `deploy_runs` index attempted — the same property `CveFixIndexInitializer`'s gate protects, for the same reason: the webhook receiver and the `code-review` worker have no Mongo dependency and must not be taken down by one.

- [X] T043 [US6] Add the `deployer` service to `docker-compose.prod.yml`: `image: ${FACTORY_IMAGE:-…software-factory:latest}`, `restart: unless-stopped`, `pull_policy: missing`, **deliberately no `env_file: .env`** (carry the existing prohibition comment forward), `mem_limit`/`mem_reservation` in the style of the neighbouring services with a note that they are inert until the memory cgroup is enabled, and `depends_on: temporal: service_healthy` + `temporal-create-namespace: service_completed_successfully` + `mongodb: service_healthy`. Healthcheck copied from `software-factory`.

  Environment: `FACTORY_DEPLOY_ENABLED: ${FACTORY_DEPLOY_ENABLED:-false}`, `FACTORY_DEPLOY_TRIGGER_ENABLED: "false"` (hard-coded — the deployer must never start workflows), `FACTORY_DEPLOY_SYNC_CONFIG`, `FACTORY_DEPLOY_ROLLBACK_ENABLED`, `FACTORY_DEPLOY_SERVICES`, `FACTORY_DEPLOY_RECREATABLE`, `FACTORY_DEPLOY_COMPOSE_FILE`, `FACTORY_DEPLOY_SCRIPT`, `FACTORY_DEPLOY_REPO_DIR`, `FACTORY_DEPLOY_REPO_URL`, plus `TEMPORAL_ADDRESS`, `TEMPORAL_NAMESPACE`, `FACTORY_MONGODB_URI`, `GITHUB_APP_CLIENT_ID`, `GITHUB_APP_PRIVATE_KEY_PATH`, `CLAUDE_CODE_OAUTH_TOKEN` and the Claude model knobs. Note in a comment that `GITHUB_WEBHOOK_SECRET` and `FACTORY_TRIGGER_TOKEN` are deliberately absent — the deployer receives nothing.

  Volumes: the Docker socket; `DOCKER_BINARY_PATH`/`DOCKER_PLUGINS_PATH` read-only (the same mechanism, and the same macOS/OrbStack reason, as the mount `backend` is losing); `.:/workspace/repo` **read-write**; the GitHub App PEM read-only; `deploy-state:/var/run/deploy-state`; and the workspace volume for the triage scratch directory. Comment that the read-write deploy-directory mount is the one genuinely new capability, that it also picks up `.env` (which `docker compose` must interpolate — the same file `backend` is losing, not new access), and that it is fenced by clean-tree + ancestor + `--ff-only`.

- [X] T044 [US6] Add `deploy-state:` to the `volumes:` block of `docker-compose.prod.yml`, and add the two new mounts to the `nginx` service: `./config/nginx/maintenance:/etc/nginx/maintenance:ro` and `deploy-state:/var/run/deploy-state:ro`. Read-only on nginx is the point — the flag is written by the deployer and only read by the proxy.

**Checkpoint**: the stack is fully wired and defaults to doing nothing.

---

## Phase 6: User Story 3 — Host-side configuration deploys with its own commit (P2)

**Goal**: The deploy advances the host checkout to the same commit the images came
from, or declines and says why — never half way.

**Independent test**: Against a scratch checkout, each fence independently: dirty
tree refused; non-ancestor SHA refused; the target SHA used rather than a newer
branch tip; a non-allowlisted affected service leaves `HEAD` untouched.

- [X] T045 [US3] Add the `sync-config` phase to `scripts/restart-prod.sh` per `contracts/restart-prod-phases.md` steps 1–8, in that exact order, abandoning without side effects on any failure. `git status --porcelain --untracked-files=no` (so a hand-edited, gitignored `.env` and any untracked file do not block). `git fetch --no-tags "$REPO_URL" main` — anonymous, read-only, from the pinned URL rather than the checkout's configured remote, so a tampered remote cannot redirect it. `git merge-base --is-ancestor "$sha" FETCH_HEAD`. Then `git merge --ff-only "$sha"`. Emit `key=value` lines on stdout (`previous-sha`, `decision`, `affected`, `held-back`, `missing-variable`, `manual-command`) and human narration on stderr. Exit 0 for `applied`/`already-current`, 2 for every decline, 1 for a git or fetch error.

- [X] T046 [US3] In the same phase, implement the affected-service computation **before** the fast-forward: `git show "$sha:docker-compose.prod.yml" > "$tmp"` then `docker compose -f "$tmp" config --hash='*'` versus the same on the current file; affected = differing hashes plus services present in one list and not the other. Parse `--hash` output tolerantly (split on whitespace, first and last field) rather than assuming a tab. If the command fails on the candidate file while succeeding on the current one, that is a variable `.env` does not define → `decision=missing-variable` with a best-effort name, exit 2.

  Comment why deciding first and moving second is the whole point: a fast-forward followed by a refusal to recreate would leave the deploy directory ahead of what is running, and `monitor-prod.sh`'s next bare `up -d` would apply the held-back change within the minute — precisely the surprise this prevents. Also comment that this ordering is what keeps `recreate`'s full `up -d` reconcile safe.

- [X] T047 [US3] Add the `rollback-config <sha>` phase — `git -C "$repo" reset --hard <sha>`. Comment that it is `reset --hard` and not `merge --ff-only` because the recorded SHA is an ancestor of the current `HEAD` and a fast-forward cannot go backwards, and that this is safe only because the clean-tree check ran before anything moved.

- [X] T048 [US3] Add a comment block to `scripts/restart-prod.sh` recording that **bare invocation never runs `sync-config`** — a human typing `restart-prod.sh` after their own `git pull` must not have the script decide to move `HEAD` for them; today the script touches git not at all and that stays true. It is opt-in, and the deployer opts in.

- [X] T049 [US3] Write `scripts/test/test-restart-prod-sync-config.sh` — builds a scratch bare "origin" plus a working clone in a temp directory (never the real deploy directory), then asserts: fast-forwards to the target SHA **rather than the newer branch tip** when the tip is ahead; refuses a dirty tree (tracked file modified) with exit 2 and `HEAD` unmoved; an untracked file does **not** block; refuses a SHA that is not an ancestor of the fetched `main` with exit 2 and `HEAD` unmoved; leaves `HEAD` untouched with exit 2 and a `held-back=` line when the compose diff hits a non-allowlisted service; reports `already-current` and exit 0 when `HEAD` is already the target; `rollback-config` restores the recorded SHA; `previous-sha=` is emitted before anything moves. `git` is already in the image so this needs no new dependency.

- [X] T050 [US3] Extend `DeployWorkflowImpl` and `DeployWorkflowTest` for the declined-sync path: a `SyncOutcome` of `HELD_BACK`, `DIRTY_TREE`, `NOT_AN_ANCESTOR` or `MISSING_VARIABLE` still deploys images, returns `DEPLOYED_IMAGES_ONLY`, records the outcome, and reports the held-back services and the manual command via `DeployReportGateway` — a comment on the deployed commit even on success, because "deployed, but not all of it" must not be silent. Assert `rollbackConfig` is never called in these cases.

**Checkpoint**: the feature is end-to-end. Config and images come from one commit
or neither.

---

## Phase 7: User Story 5 — The public API no longer holds host-level power (P2)

**Goal**: The backend's self-redeploy path and the host access it required are
gone.

**Independent test**: The redeploy request is not served, the admin UI no longer
offers it, and the backend has no Docker socket, compose file or `.env` copy.

- [X] T051 [US5] Delete `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` and `backend/src/main/java/com/simonrowe/dataops/RedeployProperties.java`, and delete `backend/src/test/java/com/simonrowe/dataops/RedeployServicesTest.java`.

- [X] T052 [US5] Modify `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — remove the `RedeployService` field, its constructor parameter, and the `@PostMapping("/redeploy")` method. Leave every other operation untouched.

- [X] T053 [US5] Remove the `redeploy:` block from `backend/src/main/resources/application.yml` (lines ~387–403).

- [X] T054 [US5] Modify `docker-compose.prod.yml` — remove these five mounts from the `backend` service: `/var/run/docker.sock`, the `DOCKER_BINARY_PATH` and `DOCKER_PLUGINS_PATH` read-only mounts, `./docker-compose.prod.yml:/workspace/docker-compose.prod.yml:ro` and `./.env:/workspace/.env:ro`. Add a comment recording what this buys: the container serving the public API loses host-root capability and its copy of the compose file and `.env`, which is the largest single security improvement in this change. Note that `DOCKER_BINARY_PATH`/`DOCKER_PLUGINS_PATH` survive because the deployer uses them now.

- [X] T055 [P] [US5] Modify `frontend/src/services/dataOperationsApi.ts` — remove `startRedeploy` and the `// Redeploy` section.

- [X] T056 [US5] Modify `frontend/src/pages/admin/DataOperationsAdmin.tsx` — remove the `startRedeploy` import, the `showRedeployConfirm` state, `handleRedeployConfirm`, the "Redeploy Site" card, the confirm dialog, and the `isRedeploy` parameter and branch in `connectSse`. Verify the remaining SSE path still works for backup and restore.

- [X] T057 [US5] Add a guard test asserting **no `ProcessBuilder` remains anywhere in `backend/src/main/java`** — `RedeployService` was the only one, and nothing else in the backend shells out to a host process. A simple recursive source scan in a JUnit test, so the constitutional rule from T001 is enforced by the build rather than by memory.

- [X] T058 [US5] Run `./gradlew :backend:test :backend:jacocoTestCoverageVerification` and `cd frontend && npm test && npm run lint`. The backend JaCoCo floor is 0.78 and the deletion removes both covered and uncovered lines, so the floor could move either way — **verify, do not assume**. If it drops below the floor, add coverage to an existing under-tested `dataops` class rather than lowering the floor.

**Checkpoint**: two deploy mechanisms have become one, and the public API is
smaller.

---

## Phase 8: User Story 4 — An unplanned outage looks intentional (P3)

**Goal**: With no deploy in progress and a public upstream down, visitors get a
branded page; every operational hostname stays reachable.

**Independent test**: Stop a public upstream with no deploy running; confirm the
branded unavailable page and that all ops hostnames and `/healthz` still respond.

- [X] T059 [US4] Verify and, if needed, complete the `error_page 502 504 = @unavailable;` wiring added in T020 for both the `www` and `api` blocks, and confirm `unavailable.html` (T019) is reached rather than nginx's built-in 502 page. Confirm the ops-hostname blocks are deliberately **not** given `error_page` handling — a 502 there should look like a 502, because those hostnames are the debugging tools.

- [X] T060 [US4] Extend `scripts/test/test-nginx-maintenance.sh` for the flag-absent case: with no upstream running and no flag file, `www` and `api` serve the themed unavailable page with a 502-class status, while `/healthz` is 200 and the ops hostnames are untouched.

**Checkpoint**: the last visible rough edge in a production outage is gone.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T061 [P] Create `docs/runbooks/deploy.md` — the deploy architecture, the phase table, the three off-switches, how to read `deploy_runs`, how to start a deploy by hand from the Temporal UI, how to check for a live poller on the `deploy` task queue (a container can be `healthy` with no poller, in which case nothing ever deploys), the rollout order from quickstart.md, and — prominently — **how to update the deployer**, because it never recreates itself.

- [X] T062 [P] Modify `docs/runbooks/software-factory-manual-actions.md` — add the one action only a human can perform: **subscribe the GitHub App to the `workflow_run` event.** Without it no delivery ever arrives and the feature is inert with no error anywhere.

- [X] T063 [P] Modify `.claude/skills/prod-deploy/SKILL.md` — the deploy is now automatic on merge, so the skill's job shifts to *watching* one and verifying it. Add the deployer-staleness step (`docker compose -f docker-compose.prod.yml up -d --no-deps deployer`) so it surfaces during a deploy rather than only in a runbook nobody re-reads, and add checking `deploy_runs` and the Temporal UI to the verification steps.

- [X] T064 Modify `CLAUDE.md` — add a `036-auto-deploy-on-merge` entry at the top of `## Recent Changes` in the established dense style, and update the `## Production Deployment` section: the new `deployer` service and what it holds, the `pull_policy: missing` change and why the watchdog made it necessary, the maintenance/unavailable pages and the flag file, that `backend` no longer holds the Docker socket (so the existing `POST /api/admin/data-operations/redeploy` bullet in "Manual additions" is now false and must be replaced), and that the deployer does not self-update. **Edit by hand** — `.specify/scripts/bash/update-agent-context.sh` truncates older `Recent Changes` entries by stripping their first line, leaving orphaned text, so do not use it here.

- [X] T065 Modify `.github/workflows/ci.yml` — add a step running `./scripts/test/run-tests.sh` (shell tests, `DRY_RUN=1` enforced by the entrypoint). Put it in an existing job rather than adding a new one, so it does not cost a fresh runner and a cold Gradle cache.

- [X] T066 Run the full gate: `./gradlew :software-factory:test :software-factory:checkstyleMain :software-factory:jacocoTestReport :backend:test :backend:checkstyleMain :backend:jacocoTestCoverageVerification`, `cd frontend && npm test && npm run lint`, `./scripts/test/run-tests.sh`, and `docker run --rm -v "$PWD/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine nginx -t`. Also `docker compose -f docker-compose.prod.yml config -q` to prove the compose file still parses.

- [X] T067 Update `.specify/feature.json` to `specs/036-auto-deploy-on-merge` (already done during planning — confirm), and tick the boxes in `specs/036-auto-deploy-on-merge/checklists/requirements.md` that implementation has now satisfied.

---

## Dependencies

```
Phase 1 (Setup: T001–T003)
        │
Phase 2 (Foundational: T004–T017)   ← both flags land false here
        │
        ├─► Phase 3  US1 (T018–T031)  P1  the deploy itself
        │           │
        │           ├─► Phase 4  US2 (T032–T040)  P1  rollback + triage
        │           │
        │           ├─► Phase 5  US6 (T041–T044)  P1  gating + compose wiring
        │           │
        │           └─► Phase 6  US3 (T045–T050)  P2  config sync
        │
        ├─► Phase 7  US5 (T051–T058)  P2  deletions — independent of US1–US4
        │
        └─► Phase 8  US4 (T059–T060)  P3  needs T019/T020 from US1

Phase 9 (Polish: T061–T067) last.
```

Cross-story couplings worth stating explicitly:

- **US6 depends on US1 and US2** even though all three are P1: its tasks gate and
  wire the classes those phases create. Safety in the meantime comes from T017,
  not from US6.
- **US4 depends on T019/T020**, which live in US1 because the maintenance page is
  required by US1 scenario 2. The two pages are designed as a pair.
- **US5 is genuinely independent** and could be done first, but is deliberately
  last-but-two so there is never a window with neither deploy mechanism.
- **T040 (`pull_policy`) sits in US2**, not US6, because rollback is what makes it
  necessary — with `always` a rollback cannot hold.

## Parallel opportunities

Within Phase 2: T007 with T008–T015 (shell tests vs. Java leaves; different
files). T008, T009, T010, T011, T012, T013, T014, T015 are all `[P]` — eight
independent files.

Within Phase 3: T018, T019 and T021 (pages and their test) run alongside T022 and
the Java work; T031 alongside T029.

Within Phase 4: T032, T033, T035, T036 and T039 are independent files.

Within Phase 7: T055 (frontend service) alongside T051–T053 (backend).

Within Phase 9: T061, T062 and T063 are three separate documents.

## Implementation strategy

**MVP is Phase 2 + Phase 3 (US1)**: a merge deploys images automatically, behind a
maintenance page, recorded durably. That alone closes the gap the feature exists
for — the `software-factory` image being published for months and never deployed.

**But do not ship the MVP alone.** US2 (rollback) is the reason automating the
last mile is acceptable at all: it removes the human who would have caught a bad
deploy. The design puts rollback in the first version deliberately, and this plan
keeps it there.

**Incremental delivery on one branch**: every phase leaves the tree in a state
that is no worse than today, because both flags are `false` from T017 onward. The
branch can therefore be merged at any checkpoint without changing production, and
the first real deploy is the deliberate act of an operator following the rollout
order in quickstart.md.
