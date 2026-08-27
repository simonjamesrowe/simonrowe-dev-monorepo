# Phase 0 Research: Auto-deploy on merge

The approved design at `docs/superpowers/specs/2026-08-26-auto-deploy-on-merge-design.md`
already resolves the architectural questions. This document records the decisions
that matter for implementation, plus the answers found by reading the code the
design touches — where the design said "the existing pattern" and the code had to
be consulted to know which pattern that is.

## 1. Where the trigger lands

**Decision**: A `workflow_run` branch inside the existing
`GitHubWebhookController.receive`, before the current `pull_request`-only early
return, gated by `factory.deploy.trigger-enabled`.

**Rationale**: `WebhookSignatureVerifier` runs first and unchanged, so the new
branch inherits HMAC verification for free. The controller already returns
`202 {"status":"ignored"}` for unhandled events, which is exactly the required
behaviour for a failed conclusion, a non-`main` branch or another workflow name.

**Found in code**: `receive` currently does
`if (!"pull_request".equals(event)) return accepted("ignored")`. The new branch
must be inserted before that line, matching the shape of `handleClosed` (a private
method returning `ResponseEntity<?>`).

**Repository allowlist**: there is no existing shared repo allowlist —
`factory.feedback.repos` is feedback-only and `factory.cvefix` pins
`owner`/`repository` as scalars. Deploy follows `cvefix`: `factory.deploy.owner`
and `factory.deploy.repository`, defaulted in the record's compact constructor.
That is the allowlist, and it is a set of exactly one.

**Alternatives rejected**: a second controller (duplicates signature
verification); `pull_request closed` as the trigger (deploys the previous images
and reports success — the design's worst failure mode).

## 2. Coalescing: signal-with-start on a fixed workflow id

**Decision**: `WorkflowClient.signalWithStart` against workflow id `deploy-prod`,
carrying the head SHA. The workflow drains a `latestSha` field set by the signal
handler and loops while a newer SHA has arrived.

**Rationale**: One deploy at a time on a single node, duplicate deliveries free,
and the durability the design needs — the deploy restarts `software-factory`,
which is the process that started the workflow, so nothing in-process can hold the
request across that boundary.

**Found in code**: `ReviewWorkflowService` is the established shape for
"controller → Temporal client → typed stub". It uses
`WorkflowClient.newWorkflowStub` with `WorkflowOptions` naming the task queue, and
catches `WorkflowExecutionAlreadyStarted` to make retries idempotent.
`DeployWorkflowService` follows it but uses `BatchRequest` +
`WorkflowClient.signalWithStart`, which needs no already-started catch because
signal-with-start is idempotent by construction.

**Consequence to implement deliberately**: the workflow must re-read the signalled
SHA *after* each deploy attempt completes and start again if it changed, otherwise
"absorbed into the one in flight" silently drops the newer commit. The design says
"absorbed into the one in flight **or the one that starts next**" — draining a
pending SHA at the end of `run` is what makes the second half of that true.

## 3. Phase-argument shape for `scripts/restart-prod.sh`

**Decision**: `main "$@"` dispatch on `${1:-all}`, where `all` runs the existing
sequence (`pull` → `recreate` → `verify` → `verify-public`) and deliberately
**not** `sync-config`. Env knobs `SERVICES`, `IMAGE_TAG`, `STATE_DIR`,
`DRY_RUN`, plus `TARGET_SHA` as `$2` for `sync-config` and `rollback`.

**Rationale**: The design is explicit that this stays one script and that bare
invocation behaves exactly as today. A dispatch function preserves that: `all`
calls the same helpers in the same order with no flag file present.

**Found in code**: the current script is a flat top-to-bottom sequence with
`unsettled_containers` as its only function. Restructuring is required, and the
existing inline comments — especially the "deliberately not fatal" note on
`up -d` and the whole settle-loop rationale — must move with the code they
explain, not be dropped.

**`python3` → `jq`**: `unsettled_containers` currently pipes
`docker compose ps -a --format json` through an inline Python program. The design
requires `jq` so the deployer image needs no Python runtime. The rewrite must
preserve all four cases the Python covers: a container with a healthcheck that is
not `healthy`; an `exited` container with a non-zero exit code (a clean one-shot
is fine); a container that is neither `running` nor `exited` (this is what catches
`created`); and malformed lines skipped rather than fatal. `jq -r` with
`--slurp`-free line-by-line input and `// empty` guards does all four.

**`jq` availability**: `jq` is *not* in the runtime stage of
`Dockerfile.software-factory` — it is installed only in the throwaway `claude`
download stage. The runtime stage installs `ca-certificates git`. It must gain
`curl jq` (curl for the hostname checks, jq for the settle loop). This is a real
change the design implies but does not spell out.

## 4. Pinning an image version: local re-tag, not compose indirection

**Decision**: `docker pull <image>:<sha>` then
`docker tag <image>:<sha> <image>:latest`, then
`docker compose up -d --no-deps --pull never <service>`. Rollback re-tags
`:latest` back to image **IDs** recorded before the pull.

**Rationale**: verbatim from the design. The decisive argument is that
`monitor-prod.sh` runs a bare `docker compose up -d` every minute, which resolves
`:latest`; env indirection would let the watchdog undo a rollback within 60
seconds.

**Implementation detail the design implies**: the recorded rollback point must be
an image **ID** (`docker image inspect --format '{{.Id}}'`), not a tag — the whole
point is that the tag is about to be moved. `docker tag <id> <image>:latest` is
valid, so the restore is symmetric.

**Additional required change, found in code**: `backend`, `frontend` and
`software-factory` declare `pull_policy: always` in `docker-compose.prod.yml`
(lines 325, 241 and the frontend's). With `pull_policy: always`, `--pull never` on
the command line is what actually holds for that invocation, but the watchdog's
bare `up -d` has no such flag and would re-pull. Hence `pull_policy: missing` on
those three, as the design requires. `restart-prod.sh` is unaffected because it
runs `docker compose pull` explicitly.

## 5. Deciding the affected services before moving `HEAD`

**Decision**: `git show <sha>:docker-compose.prod.yml > "$tmp"`, then
`docker compose -f "$tmp" config --hash='*'` versus the same command on the
current file; the affected set is every service whose hash differs plus every
service present in one list and not the other.

**Rationale**: verbatim from the design. It is the only way to get "exactly the
services this change affects" without moving `HEAD` first.

**Risk identified while reading the code**: `docker compose config` interpolates
`.env`, so a compose file referencing a variable the host does not define makes
this command *fail*, not merely warn. That failure is itself the signal FR-031
requires: if `config --hash` on the candidate file fails while it succeeds on the
current one, the sync is declined and the deploy reports the missing variable,
which is far better than discovering it after `HEAD` moved and every subsequent
`docker compose` command on the box is broken. So the missing-variable check is
not a separate step — it falls out of ordering the hash comparison before the
fast-forward. The error text is parsed for the variable name on a best-effort
basis only; the decline itself does not depend on parsing it.

**Second risk**: `--hash='*'` output format is `service<TAB>hash` per line in
current Compose versions. Parsing must be tolerant (split on whitespace, take
first and last field) rather than assuming a tab.

## 6. Which git operations are safe on the host checkout

**Decision**, in order, and abandoning on the first failure without side effects:

1. `git -C "$repo" rev-parse HEAD` → rollback target.
2. `git -C "$repo" status --porcelain --untracked-files=no` → must be empty.
3. `git -C "$repo" fetch --no-tags "$FACTORY_DEPLOY_REPO_URL" main` → anonymous.
4. `git -C "$repo" merge-base --is-ancestor "$sha" FETCH_HEAD` → must succeed.
5. affected-service check (§5).
6. `git -C "$repo" merge --ff-only "$sha"`.

**Rationale**: `--untracked-files=no` is what makes a hand-edited `.env` (which is
gitignored anyway) and any untracked file non-blocking, per FR-025/FR-026. The
pinned URL rather than `origin` is what stops a tampered remote redirecting the
fetch. `merge-base --is-ancestor` against `FETCH_HEAD` is the assertion that the
target is genuinely on `origin/main`. `--ff-only` cannot rewrite history or create
a commit.

**Rollback uses `git reset --hard <recorded-sha>`**, not `merge --ff-only`, because
the recorded SHA is an ancestor of the current `HEAD` and a fast-forward cannot go
backwards. This is safe precisely because step 2 proved the tree was clean before
anything moved.

## 7. Where the maintenance flag lives

**Decision**: named volume `deploy-state`, `rw` on `deployer`, `ro` on `nginx`,
mounted at `/var/run/deploy-state`. `maintenance-on` writes
`$STATE_DIR/maintenance.on`.

**Rationale**: verbatim from the design. A named volume rather than a host bind
keeps it off the host filesystem and gives nginx a read-only view, which is what
FR-043 requires.

**nginx detail confirmed by reading the conf**: `error_page` and the named
locations are per-`server`-block and are not inherited, so `simonrowe.dev/www` and
`api.simonrowe.dev` each carry a copy. The `default_server` block that serves
`/healthz` gets neither, which is what keeps the health endpoint outside the flag
(FR-042). The `location = /webhooks/github` exact-match block sits *inside* the
`api` server block, so the `if (-f ...) { return 503; }` must be placed in the
`location /` block rather than at server level — otherwise it would 503 the
webhook that triggered the deploy. This is the single most important placement
detail in the nginx change.

**`if` in nginx**: `if` is only reliably safe at `location` level with `return`,
which is exactly this usage ("`if` is evil" applies to `if` + other directives).
`return 503` plus `error_page 503 = @maintenance` is the documented-safe idiom.

## 8. The failure-path agent

**Decision**: a `DeployTriageEngine` using the shared `ClaudeCliRunner` with a
tool set of `Read` only against a scratch directory holding the captured
evidence — no `Bash`.

**Rationale**: FR-038. `ClaudeCliRunner` already strips the environment down to
`SAFE_SECRET_ENVIRONMENT` + `PROCESS_ENVIRONMENT`, so the GitHub App key and the
Dependency-Track key are removed from the child process automatically. The
`cvefix` engine is the template.

**Found in code**: `ClaudeCliRunner.runStructured` takes an `Invocation` carrying
`tools`, `allowedTools`, `workingDirectory`, `prompt`, `timeout` and returns the
parsed `structured_output` node, with a JSON schema file under
`src/main/resources` (`cve-fix-schema.json`). Deploy triage adds
`deploy-triage-schema.json` in the same shape.

## 9. Reporting: issue + commit comment

**Decision**: a new `DeployReportGateway`, modelled on `CveFixPrGateway`.

**Rationale**: no existing gateway creates an issue or a commit comment.
`GitHubGateway` does PR reviews and comments; `CveFixPrGateway` does
`POST /repos/{o}/{r}/pulls` and `POST /repos/{o}/{r}/issues/{n}/comments`. Deploy
needs `POST /repos/{o}/{r}/issues` and
`POST /repos/{o}/{r}/commits/{sha}/comments`, which is the same client, the same
`GitHubCredentials.accessToken(installationId)` and the same error handling.

**Found in code**: `GitHubCredentials.installationId(owner, repository)` resolves
the installation at run time; `cvefix` deliberately configures no installation id
so an empty value cannot silently degrade to anonymous. Deploy does the same.

## 10. Persistence and index management

**Decision**: `deploy_runs` collection, `DeployRunRecord` +
`DeployRunRepository` + `DeployIndexInitializer`, the last gated on
`@ConditionalOnProperty("factory.deploy.enabled")`.

**Rationale**: verbatim from the design ("the `CveFixRunRecord` pattern",
"indexes via an initializer, matching `CveFixIndexInitializer`"). The
`@ConditionalOnProperty` gate matters for the same documented reason it does for
`cvefix`: an unreachable Mongo must not fail the whole application context and
take the webhook receiver and the `code-review` worker with it.

**Id strategy**: `CveFixRunRecord.idFor(workflowId)` returns the workflow id, so a
re-drive overwrites its own row. Deploy cannot do that — the workflow id is the
fixed constant `deploy-prod`, so every deploy in history would share one row.
Deploy keys on the Temporal **run** id instead, which is unique per execution and
stable across replays. This is a deliberate divergence from the pattern the design
cites, and it is the kind of thing that would otherwise be discovered only when
the second deploy overwrote the first.

## 11. Worker registration: which JVM polls the `deploy` queue

Both `software-factory` and `deployer` run the same image, so anything that
registers a `deploy` worker registers it in **both** JVMs. The design's central
security property — the Docker socket only ever reached by the container with no
ingress — depends on getting this right, so it was checked against the starter's
source rather than assumed.

**Verified in `temporal-spring-boot-autoconfigure` 1.36.0**
(`WorkersTemplate.java`):

- Workflow implementations are discovered from two unioned sources:
  classpath scanning of `workers-auto-discovery.workflow-packages`
  (`autoDiscoverWorkflowImplementations`) and, when
  `workers-auto-discovery.enabled: true`, Spring beans annotated
  `@WorkflowImpl` (`autoDiscoverWorkflowBeans`, line 391:
  `beanFactory.getBeansWithAnnotation(WorkflowImpl.class)`).
- `configureWorkflowImplementationsByTaskQueue` (line 222) creates a worker for
  every task queue named by a discovered class, unconditionally:
  `if (worker == null) worker = createNewWorker(taskQueue, null, workers)`.
- Activity implementations are discovered **only** as Spring beans
  (`autoDiscoverActivityBeans`, line 397), gated by
  `register-activity-beans`.

So: a `@WorkflowImpl` in a scanned package is **not** a Spring bean and
`@ConditionalOnProperty` cannot gate it. An `@ActivityImpl` bean can be gated,
because it is a bean.

**Decision**: add `com.simonrowe.factory.deploy.workflow` to `workflow-packages`
as normal, and gate **`DeployActivitiesImpl`** with
`@ConditionalOnProperty(name = "factory.deploy.enabled", havingValue = "true")`.

Consequences, all of them intended:

- `deployer` (flag `true`) holds the only implementation of every
  side-effecting step, so the Docker socket, the deploy directory and
  `restart-prod.sh` are reachable from that JVM alone. FR-016 holds on the
  property that actually matters: `software-factory` cannot execute a deploy
  step because it has no code for one.
- `software-factory` (flag `false`) does register a `deploy` **workflow**-task
  poller. That is harmless: a workflow implementation is deterministic
  orchestration that only schedules activities — it touches no socket, no
  filesystem and no credential. Whichever JVM runs the workflow task, every
  activity runs on the `deployer`.
- With the executor disabled entirely, a triggered deploy starts, schedules its
  first activity, and waits. Temporal applies no schedule-to-start timeout by
  default, so the task sits in the queue until a poller appears. That is exactly
  the durable queueing US6 scenario 3 asks for, and it is a property of the
  queue rather than something to build.

**Alternatives rejected**:

- Making `DeployWorkflowImpl` a Spring bean so `@ConditionalOnProperty` gates it
  too. This works — `workers-auto-discovery.enabled: true` is already set in
  `application.yml`, so bean-based workflow discovery is live — but it requires
  moving activity-stub creation out of instance-field initialisers, because
  `Workflow.newActivityStub` throws outside a workflow thread and Spring would
  instantiate the bean at startup. Every existing workflow in this module
  (`CodeReviewWorkflowImpl`, `CveFixWorkflowImpl`, `ReviewFeedbackWorkflowImpl`)
  uses the field idiom. Diverging from it to gate a worker that is harmless when
  registered is not worth the inconsistency.
- A `deployer`-only Spring profile: a second configuration axis for something one
  flag already expresses, and the flag has to exist anyway.
- Setting the task-queue name from a placeholder
  (`@WorkflowImpl(taskQueues = "${...}")` — line 229 does call
  `environment.resolvePlaceholders`). This renames the queue rather than removing
  the worker, so the disabled JVM would poll a differently-named queue: more
  moving parts, same outcome.

**Test that pins this**: assert `DeployActivitiesImpl` is absent from the
context when `factory.deploy.enabled` is unset, and present when it is `true`.
Without that test the gate can be removed by accident and the only symptom is an
intermittent deploy failure on whichever JVM won the activity task.

## 12. Testing the shell script without deploying anything

**Decision**: `DRY_RUN=1` echoes every `docker` invocation instead of running it,
`STATE_DIR` is overridable, and the phase tests run against a scratch git clone in
`$BATS_TMPDIR`-style temp directories created by the test itself.

**Rationale**: `CLAUDE.md` records this as non-negotiable for `monitor-prod.sh`
for exactly the reason it applies here — every remediation path shells out to
`docker compose`, so merely running the script performs real restarts.
`monitor-prod.sh` already has a `run_cmd` wrapper honouring `DRY_RUN`;
`restart-prod.sh` gains the same wrapper, named the same, so the two scripts read
alike.

**Harness**: plain bash test scripts under `scripts/test/`, run by a
`scripts/test/run-tests.sh` entrypoint, because the repo has no shell test
framework and adding `bats` as a dependency for this is unjustified under
Principle V. CI runs the entrypoint as a step.

## 13. Constitution conflict

Principle II contains:

> Docker redeploy MUST be available from the admin UI. The backend MUST use
> `ProcessBuilder` to execute Docker Compose CLI commands (binary mounted from
> host).

This feature deletes exactly that. The constitution's amendment procedure requires
the principle change to be documented, versioned and reflected in the file
*before* implementation begins, so the amendment is Task 1 of the plan and not a
follow-up. Version bump is **MAJOR** (2.0.0): it is a principle redefinition, not
an expansion.

The replacement text states the new rule positively — deployment is performed by
a dedicated container holding the Docker socket, triggered over a durable
workflow, and the container serving the public API must hold no host-level
container access — so that the next feature to consider a `ProcessBuilder` in the
backend is refused by the constitution rather than by memory.
