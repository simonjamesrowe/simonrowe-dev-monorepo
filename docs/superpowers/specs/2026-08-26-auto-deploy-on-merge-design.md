# Auto-deploy on merge

**Created**: 2026-08-26

**Status**: Approved (design)

A merge to `main` should end with the change live on the Raspberry Pi, without a
human running anything. Today the last mile is manual: CI publishes three images
and then someone has to notice and act.

## Motivation

`Publish` has built `backend`, `frontend` and `software-factory` images on every
merge to `main` for months. Nothing deploys them. The gap has already caused one
recorded incident of its own kind: the `software-factory` image was published on
every merge but never deployed, so the automated code reviewer ran whatever image
was last started by hand until 2026-08-11. That is not a one-off — it is the
predictable outcome of a publish step with no deploy step.

The two existing paths both have problems:

- **`scripts/restart-prod.sh`** works well, and is the only thing that reliably
  gets the stack into a verified-good state. But it requires a human on the Pi.
- **`POST /api/admin/data-operations/redeploy`** exists to remove that
  requirement and has never been trusted. It is a partial reimplementation of
  `restart-prod.sh` inside the backend, which forced the backend to hold
  `/var/run/docker.sock` — host-root capability in the container that serves the
  public API — and to restart itself mid-orchestration via an ephemeral helper
  container. It is being deleted, not extended.

## Shape

One new container, one script, no new HTTP surface anywhere.

| Component | Role | Docker socket | Public ingress |
| --- | --- | --- | --- |
| `software-factory` | Receives the signed `workflow_run` webhook, starts the workflow | no | `POST /webhooks/github` only |
| `deployer` | **Same image.** Polls the `deploy` Temporal task queue and executes | **yes** | none |
| `nginx` | Serves the themed maintenance page off a flag file | no | all public hostnames |
| `scripts/restart-prod.sh` | The deploy itself — one script, shared by the deployer and by hand | | |

### Why the socket does not go on `software-factory`

`software-factory` terminates untrusted internet traffic. The compose file
already goes out of its way to deny it `env_file: .env` for exactly that reason —
"declare what it needs, and nothing else". A Docker socket mount is
root-equivalent on the host, and it is the single worst thing to hand the process
on the other side of a webhook.

So the socket goes on a second container that has no ingress at all. Its trigger
arrives over Temporal, on an internal network, from a service that has already
verified an HMAC signature.

### Why the same image, and not a minimal one

An alpine-plus-docker-CLI image would be smaller. It would also have no Claude
binary and no GitHub App key, and the failure path needs both: on a bad deploy an
agent reads the container logs and writes up what broke, and posting that write-up
needs an installation token.

Building a second image to carry those two things would duplicate
`Dockerfile.software-factory` almost exactly. `FACTORY_IMAGE` is run twice
instead, with different module flags — the pattern `FACTORY_CVEFIX_ENABLED`
already establishes for turning parts of this JVM on and off.

### The deployer's mounts

```yaml
- /var/run/docker.sock:/var/run/docker.sock
- ${DOCKER_BINARY_PATH:-/usr/bin/docker}:/usr/local/bin/docker:ro
- ${DOCKER_PLUGINS_PATH:-/usr/libexec/docker/cli-plugins}:/usr/local/lib/docker/cli-plugins:ro
- .:/workspace/repo
- ${GITHUB_APP_PRIVATE_KEY_PATH:?…}:/run/secrets/github-app.pem:ro
- deploy-state:/var/run/deploy-state
```

The whole deploy directory, read-write, rather than the individual read-only file
mounts the backend uses today — the deployer runs `git` in it, and the compose
file, the nginx conf, the maintenance pages and the script all live there. That
write access is the one genuinely new capability in this design; see
[Configuration sync](#configuration-sync) for what fences it.

Note it also picks up `.env` as part of the directory, which the deployer needs
because `docker compose` interpolates it. That is not new access — it is the same
file the backend mounts today and is losing.

### Why Temporal is the queue

The deployer restarts `software-factory`, and `software-factory` is where the
trigger came from. Anything holding in-process state across that boundary would
lose it. A durable workflow does not: it survives its own worker being recreated
and resumes afterwards. That is the property this feature needs most, and it is
free here because Temporal is already running with a UI on
`temporal.simonrowe.dev`.

It also means the deployer needs no HTTP server, no port, and no shared token —
one less thing to protect.

## Trigger

`GitHubWebhookController` gains a `workflow_run` branch. It accepts only:

- `workflow_run.name == "Publish"`
- `workflow_run.conclusion == "success"`
- `workflow_run.head_branch == "main"`
- repository on the existing allowlist

Everything else returns the existing `202 {"status":"ignored"}`. Unsigned and
malformed deliveries are rejected by `WebhookSignatureVerifier` before any of
this, unchanged.

**`pull_request` merge is deliberately not the trigger.** Merge fires
`pull_request closed` immediately, while `Publish` then spends minutes building
three ARM images. Deploying on merge would pull the *previous* `:latest` and
report success — the worst available failure mode, because it looks like it
worked. `workflow_run` completion is the only event that means the images exist.

CI already tags every image with `${{ github.sha }}`, so the workflow deploys an
exact SHA rather than trusting whatever `:latest` points at when the pull runs
(see [Pulling by SHA](#pulling-by-sha-without-touching-the-compose-file)).

### Coalescing

The workflow is started with **signal-with-start on a fixed workflow id,
`deploy-prod`**, carrying the head SHA.

Consequences, both intended:

- A duplicate webhook delivery is inherently idempotent.
- Two merges a few minutes apart produce **one** deploy, of the newer SHA,
  rather than two racing runs recreating the same containers. The cost is that a
  fast follow-up merge does not get its own deploy run in Temporal — it is
  absorbed into the one in flight or the one that starts next. On a single-node
  Pi that is the right trade.

A per-SHA workflow id was considered and rejected: it makes duplicate deliveries
free but does nothing about concurrency, which is the failure that actually
matters when two `docker compose up -d` runs overlap.

## One script, not two

`scripts/restart-prod.sh` stays the single deploy script. It is not split into a
library, and no sibling `deploy-prod.sh` is added.

Bare `./scripts/restart-prod.sh` keeps behaving exactly as it does today — same
pull, same recreate, same settle loop, same hostname checks. That remains the
documented human path and the `prod-deploy` skill needs no change.

It gains an optional phase argument, because Temporal needs individually
retryable units and a rollback entry point:

| Phase | Does |
| --- | --- |
| *(none)* | The full sequence, as today — **excluding `sync-config`** |
| `sync-config` | Fast-forward the deploy directory to the target SHA, if the change is safe to apply |
| `maintenance-on` / `maintenance-off` | Create/remove the flag file |
| `pull` | Record the current image ID of each target service, then pull `IMAGE_TAG` and re-tag it to `:latest` |
| `recreate` | `up -d --no-deps --pull never` each target, `restart nginx`, then a full `up -d` reconcile |
| `verify` | The existing container settle loop, plus the four ops hostnames |
| `verify-public` | The existing `www` / `api` hostname checks |
| `rollback` | Re-tag `:latest` back to the recorded image IDs, recreate with `--pull never` |

Plus two env knobs: `SERVICES` (default: the three published images) and
`IMAGE_TAG` (default `latest`; the deployer passes the head SHA).

**Every phase must be safe to re-run**, since Temporal retries activities. That
is already true of the existing logic and is the main constraint on the new
phases.

### Why `verify` is split in two

The existing hostname check treats `503` as a failure — correctly. But the
maintenance page *returns* `503` by design, so running the check with the flag
still set would fail every single deploy.

So the flag-sensitive part is separated. The workflow runs:

`sync-config` → `maintenance-on` → `pull` → `recreate` → `verify` →
`maintenance-off` → `verify-public`

`verify` covers what can be checked while the page is up: the container settle
loop (which is where nearly all the waiting happens anyway, and which relies on
each container's own healthcheck rather than on nginx) and the four ops hostnames,
which are never behind the flag. `verify-public` runs after the page comes down
and is the existing check, unchanged. A failure in either enters the rollback
path, which re-asserts `maintenance-on` first.

Bare `./scripts/restart-prod.sh` runs both, with no flag file present, which is
exactly today's behaviour.

**Bare invocation never runs `sync-config`.** A human typing `restart-prod.sh`
after their own `git pull` must not have the script decide to move `HEAD` for
them; today the script touches git not at all, and that stays true. It is
opt-in — `restart-prod.sh sync-config <sha>` — and the deployer opts in.

### Pulling by SHA without touching the compose file

`docker compose pull backend` pulls whatever tag the compose file names, which is
`:latest`. Deploying an exact SHA therefore needs either compose-level image
indirection (`${BACKEND_IMAGE:-...}`) or a local re-tag.

**Re-tagging is the right one**, and the reason is durability. With env
indirection, the pinned image only holds for commands that pass the variable —
`monitor-prod.sh`'s bare `docker compose up -d` would fall back to `:latest` and
undo a rollback within the minute. Re-tagging `:latest` locally is what every
other command on the box already resolves:

```bash
docker pull  ghcr.io/…/backend:<sha>
docker tag   ghcr.io/…/backend:<sha> ghcr.io/…/backend:latest
docker compose up -d --no-deps --pull never backend
```

Rollback is the same two commands against the recorded previous image ID. No
compose change, one mechanism for both directions, and it survives the watchdog —
provided `pull_policy` changes as below.

Two incidental changes fall out of the deployer running this script:

- The settle-loop JSON parser moves from `python3` to `jq`, so the deployer image
  needs only `bash`, `curl` and `jq` rather than a Python runtime.
- The docker CLI and compose plugin are bind-mounted from the host into the
  deployer via `DOCKER_BINARY_PATH` / `DOCKER_PLUGINS_PATH` — the same mechanism
  the backend uses today, and the same reason it exists (macOS/OrbStack paths
  differ from Linux).

### `deployer` is never in the service list

Recreating the container that is mid-orchestration is how the backend's redeploy
path went wrong, and it needed an ephemeral helper container to work around it.
The deployer simply excludes itself.

It therefore **does not self-update**. Updating it is a documented manual step:

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps deployer
```

This is a known, accepted staleness risk — it is the same shape as the bug that
left `software-factory` on an old image for months. It is mitigated by putting the
step in `docs/runbooks/` *and* in the `prod-deploy` skill, so it surfaces during a
deploy rather than only in a runbook nobody re-reads. Deferring an automatic
self-update is a deliberate choice to keep the first version's failure modes
small; revisit it once the deployer has proven itself.

## Configuration sync

`docker-compose.prod.yml`, `config/nginx/*`, `frontend/nginx.conf` and `scripts/*`
are read from the deploy directory on the Pi, not from any image. A deploy that
only ships images would therefore deploy a merge touching those files *half* way —
silently, while reporting success. So the deployer fast-forwards the deploy
directory as part of the deploy, and the feature is end-to-end.

This is the one part of the design that mutates the host, so it is fenced.

### The `sync-config` phase

Runs **first**, before `pull`, so every later phase uses the newly-synced compose
file and script:

1. Record `git rev-parse HEAD` — the rollback target.
2. Refuse if any *tracked* file is modified (`git status --porcelain`, ignoring
   untracked and ignored paths, so a hand-edited `.env` is fine). A dirty tree
   means someone is working on the box; the deploy continues with images only and
   says so.
3. `git fetch https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git main`.
   The repository is public, so this needs **no credential and no push access** —
   an anonymous read-only fetch. The URL is pinned in configuration rather than
   taken from the checkout's remote, so a tampered remote cannot redirect it.
4. Assert the target SHA is an ancestor of `FETCH_HEAD`. This is what makes the
   operation safe: the working tree can only ever move to a commit that is
   genuinely on `origin/main`.
5. Decide whether the change is safe to apply (below).
6. `git merge --ff-only <sha>`.

**It fast-forwards to the deployed SHA, not to the tip of `main`.** `git pull`
would take whatever `main` points at now, which may be a newer commit whose images
do not exist yet — config and images have to come from the same commit or the
deploy is a mix of two.

### The recreate allowlist

A compose change can recreate any service, and some of them must never be
recreated by a robot. Recreating `dependencytrack-apiserver` crash-loops
Dependency-Track unless its KEK comes from env; `langfuse-clickhouse` carries a
pinned image and a `CLICKHOUSE_DISABLE_LAZY_MATERIALIZATION` workaround;
recreating `pinggy` can collide with its own still-live tunnel, since one
`PINGGY_TOKEN` allows exactly one; and `mongodb`, `elasticsearch` and `kafka` are
the data.

So the deployer works from an **allowlist of services it may recreate**, not a
denylist of ones it may not:

```
backend, frontend, software-factory, nginx, alloy, searxng,
temporal-ui, dependencytrack-frontend
```

An allowlist is the fail-safe direction: a service added to compose next year is
held for a human by default rather than silently recreated by the first deploy
that touches it. Widening it is a one-line env change once a service has earned
it. `deployer` is deliberately absent — it excludes itself.

This is also what keeps the `recreate` phase's full `up -d` reconcile safe. The
reconcile evaluates the compose file as it is on disk and can therefore recreate
anything — but `sync-config` only moves `HEAD` when every affected service is
allowlisted, so by the time the reconcile runs there is nothing outside the
allowlist for it to change.

### Deciding before committing

The affected services are computed **without moving `HEAD`**:

```bash
git show <sha>:docker-compose.prod.yml > /tmp/next.yml
docker compose -f /tmp/next.yml config --hash='*'   # vs. the same on the current file
```

`docker compose config --hash='*'` prints a per-service configuration hash, so
diffing the two lists gives exactly the set of services the change affects, plus
any service that does not exist yet.

If that set contains anything outside the allowlist, **the fast-forward does not
happen at all.** The deploy proceeds with images only, and the run record and
commit comment name the held-back services and the command to apply them by hand.

Deciding first and moving `HEAD` second is the whole point. If it fast-forwarded
and then declined to recreate, the deploy directory would be left ahead of what is
running — and `monitor-prod.sh`'s next bare `up -d` would apply the held-back
change within the minute, which is precisely the surprise this is meant to
prevent.

### Consequences worth knowing

- **The deploy directory is mounted `rw`.** Not a privilege escalation — the
  deployer already holds the Docker socket, which is root on the host — but it is
  a wider blast radius for a bug. `--ff-only` plus the ancestor assertion plus the
  clean-tree check bound it to "some commit on `origin/main`".
- **`.env` is never synced.** It is not in the repository; it comes from
  `~/workspace/simonjamesrowe/env`. A change needing a new variable still needs a
  human, and `sync-config` reports when the new compose file references a variable
  the current `.env` does not define — which would otherwise fail every subsequent
  `docker compose` command on the box.
- **A phase can rewrite the script running the next phase.** Each phase is a
  separate `bash scripts/restart-prod.sh <phase>` process, so phases after
  `sync-config` pick up the new script automatically, and the process already
  running is unaffected because git replaces files by rename rather than in place.
  This is a property to preserve deliberately, not an accident: it is why
  `sync-config` is its own phase rather than a step inside another one.

### The nginx restart gap

### The nginx restart gap

`recreate` restarts `nginx`, and `nginx` is what serves the maintenance page. For
a second or two mid-deploy there is nothing serving at all, not even the page.
This is unavoidable while the page lives in the same nginx that proxies the site,
and a couple of seconds inside an already-degraded window is not worth a second
proxy container to fix.

## Rollback, and why `pull_policy` has to change

On a failure in `verify` or `verify-public`:

0. `maintenance-on` — re-asserted, since `verify-public` runs with the page down
1. `sync-config --to <previous-sha>` — `git reset --hard` back to the SHA recorded
   at the start, so the compose file and the script return to the known-good
   commit. Skipped if `sync-config` declined to move `HEAD` in the first place.
2. `rollback` — re-tag `:latest` back to the image IDs recorded before the pull,
   recreate with `--pull never`
3. `verify` — the rollback is verified the same way the deploy was
4. `triage` — a Claude call with the failing container logs, `docker compose ps`
   output and the commit range, producing a written diagnosis
5. `report` — a GitHub issue plus a commit comment on the deployed SHA
6. Maintenance page comes down if the rollback verified clean; **it stays up if
   the rollback also failed**, which is the correct outcome

Because step 1 restores the previous commit, the rollback runs the *previous*
version of `restart-prod.sh` — which matters when the thing that broke the deploy
was a change to the script itself.

The agent gets no `Bash` tool. It is handed captured output and asked to explain
it, exactly as `cvefix` hands over Dependency-Track findings. It never touches
Docker, git or a credential.

### `pull_policy: always` → `pull_policy: missing`

`backend`, `frontend` and `software-factory` all declare `pull_policy: always`.
`scripts/monitor-prod.sh` runs a bare `docker compose up -d` every minute
whenever it sees anything unsettled (lines 284 and 363).

Together those mean **a rollback cannot hold**: the watchdog re-pulls the broken
`:latest` within 60 seconds of the rollback completing, and the site breaks again
with nothing in the deploy log to explain it.

It also means the watchdog *currently* changes which image version is running as
a side effect of healing an unrelated container — a restart intended to fix a
health-check failure can silently upgrade the backend.

Switching those three to `pull_policy: missing` makes pulling something only the
deployer and an explicit `restart-prod.sh` run do. `restart-prod.sh` is
unaffected: it already runs `docker compose pull` explicitly before `up -d`.

## Maintenance page

A named volume `deploy-state`, mounted `rw` on `deployer` and `ro` on `nginx`.
`maintenance-on` touches `maintenance.on` inside it.

In the `simonrowe.dev` / `www.simonrowe.dev` and `api.simonrowe.dev` server
blocks:

```nginx
error_page 503 = @maintenance;
error_page 502 504 = @unavailable;
if (-f /var/run/deploy-state/maintenance.on) { return 503; }

location @maintenance {
    root /etc/nginx/maintenance;
    try_files /maintenance.html =503;
    add_header Retry-After 120 always;
}
location @unavailable {
    root /etc/nginx/maintenance;
    try_files /unavailable.html =502;
}
```

Both named locations are declared per server block that uses them — nginx does not
inherit them from elsewhere — so `www` and `api` each carry a copy.

`nginx` gains two mounts alongside its existing proxy conf:

```yaml
- ./config/nginx/maintenance:/etc/nginx/maintenance:ro
- deploy-state:/var/run/deploy-state:ro
```

Two pages under `config/nginx/maintenance/`, bind-mounted like the proxy conf:
"Update in progress" for a deploy, "Temporarily unavailable" for anything else.
The second one is the reason to do this at all beyond deploys — an unplanned
outage currently shows a raw nginx 502.

Both are single self-contained files with **all CSS inlined and no external
assets**, because the frontend that would serve those assets is precisely what is
down. Styled from the site's own tokens (`--surface: #0f131c`,
`--surface-container: #1c2029`, `--primary: #77d1ff`, `--on-surface: #dfe2ef`),
Space Grotesk headings with a system fallback, Inter body. Each sends
`Retry-After` and refreshes itself.

### What is deliberately not behind the flag

- `/healthz` — the nginx healthcheck. Failing it would mark nginx unhealthy, and
  `pinggy` waits on nginx being `service_healthy`, which takes *every* hostname
  offline. That exact failure has happened before and is documented in
  `config/nginx/nginx-proxy.conf`.
- `POST /webhooks/github` — it is how this deploy was triggered and how the next
  one will be. Returning 503 would make GitHub retry into a wall.
- `console.simonrowe.dev`, `temporal.simonrowe.dev`, `langfuse.simonrowe.dev`,
  `dependency-track.simonrowe.dev` — Portainer and the Temporal UI must stay
  reachable *especially* while a deploy is failing. They are how it gets fixed.

## What gets deleted

The backend's redeploy path goes entirely:

- `RedeployService`, `RedeployProperties`, `POST /api/admin/data-operations/redeploy`
  and their tests
- the `redeploy:` block in `backend/src/main/resources/application.yml`
- `startRedeploy` in `frontend/src/services/dataOperationsApi.ts`
- the "Redeploy Site" card, its confirm dialog and the `isRedeploy` SSE branch in
  `frontend/src/pages/admin/DataOperationsAdmin.tsx`

`RedeployService` is the **only** `ProcessBuilder` in `backend/src/main/java` —
nothing else in the backend shells out to a host process. So these mounts come
off the `backend` compose service too:

```yaml
- /var/run/docker.sock:/var/run/docker.sock
- ${DOCKER_BINARY_PATH:-/usr/bin/docker}:/usr/local/bin/docker:ro
- ${DOCKER_PLUGINS_PATH:-/usr/libexec/docker/cli-plugins}:/usr/local/lib/docker/cli-plugins:ro
- ./docker-compose.prod.yml:/workspace/docker-compose.prod.yml:ro
- ./.env:/workspace/.env:ro
```

The container serving the public API loses host-root capability and its copy of
the compose file and `.env`. That is the largest single security improvement in
this change, and it is a consequence of the feature rather than the point of it.

The `DOCKER_BINARY_PATH` / `DOCKER_PLUGINS_PATH` variables survive — the deployer
uses them now. The macOS/OrbStack note in `CLAUDE.md` stays true, pointed at a
different service.

## Persistence

Deploy runs are recorded in `software_factory.deploy_runs` (the `CveFixRunRecord`
pattern): SHA, trigger, phase outcomes, verification results, rollback taken,
issue URL. Indexes via an initializer, matching `CveFixIndexInitializer`.

Without this, deploy history lives only in Temporal's retention window and in
container logs that a redeploy rotates.

## Configuration

New on the `deployer` compose service, and nothing else:

| Variable | Default | Why |
| --- | --- | --- |
| `FACTORY_DEPLOY_ENABLED` | `false` | Registers the `deploy` worker. Off by default so merging this change deploys nothing until an operator opts in. |
| `FACTORY_DEPLOY_COMPOSE_FILE` | `/workspace/docker-compose.prod.yml` | |
| `FACTORY_DEPLOY_SCRIPT` | `/workspace/scripts/restart-prod.sh` | |
| `FACTORY_DEPLOY_SERVICES` | `backend,frontend,software-factory` | The images to pull. Tunable without a rebuild, like `FACTORY_MAX_CHANGED_FILES`. |
| `FACTORY_DEPLOY_ROLLBACK_ENABLED` | `true` | An escape hatch for the case where rollback itself is the problem. |
| `FACTORY_DEPLOY_SYNC_CONFIG` | `true` | Turns the git fast-forward off without disabling the whole deployer, leaving images-only deploys. |
| `FACTORY_DEPLOY_REPO_DIR` | `/workspace/repo` | |
| `FACTORY_DEPLOY_REPO_URL` | `https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git` | Pinned rather than read from the checkout's remote. |
| `FACTORY_DEPLOY_RECREATABLE` | the eight allowlisted services | Widening it is a config change, not a code change. |

`software-factory` gains only `FACTORY_DEPLOY_TRIGGER_ENABLED` (default `false`),
gating whether it starts workflows from `workflow_run`. Deliberately separate: the
receiver and the executor must be switchable independently, so a broken deployer
can be silenced without also silencing code review.

The deployer keeps the `env_file` prohibition. It needs the Claude token, the
GitHub App key path and Temporal — not every production secret.

## Manual prerequisites and rollout order

One thing only a human can do, tracked in
`docs/runbooks/software-factory-manual-actions.md`: **subscribe the GitHub App to
the `workflow_run` event.** Without it no delivery ever arrives and the feature is
inert with no error anywhere.

Rollout, in order, because the order matters:

1. Merge. Both flags default off, so production is unchanged.
2. `git pull` on the Pi — the `deployer` service, the maintenance pages and the
   nginx conf are all host-side, so nothing works until this happens.
3. `docker compose -f docker-compose.prod.yml up -d nginx deployer`. Confirm the
   maintenance pages render by touching and removing the flag file by hand, and
   confirm `console.simonrowe.dev` stays up while the flag is set.
4. Subscribe the App to `workflow_run`.
5. `FACTORY_DEPLOY_ENABLED=true`, recreate `deployer`, and assert a live poller on
   the `deploy` task queue — a container can be `healthy` with no poller
   registered, in which case nothing ever deploys. That failure mode is already
   documented for the `code-review` queue and applies identically here.
6. Trigger a deploy manually from the Temporal UI, on the SHA already in
   production, so `sync-config` is a no-op fast-forward and the run exercises
   every phase without changing anything.
7. Only then `FACTORY_DEPLOY_TRIGGER_ENABLED=true` on `software-factory`.

Step 2 is a one-off: from here on the deployer does its own fast-forwarding. It is
needed this once because the `deployer` service definition cannot deploy itself.

## Testing

- `GitHubWebhookControllerTest` — `workflow_run` accepted for
  Publish/success/main; ignored for a failed conclusion, a non-`main` branch,
  another workflow name, and a non-allowlisted repo. Unsigned still 401.
- `DeployWorkflowTest` — Temporal test environment, activities mocked: happy
  path; verify-fails-then-rollback-succeeds; verify-fails-and-rollback-fails
  (asserting the maintenance flag is **not** cleared); activity retry
  idempotency; coalescing two signals into one deploy; a config sync declined for
  a non-allowlisted service still deploying images and reporting.
- `sync-config` against a scratch clone, which is where the real risk is: refuses
  a dirty tree; refuses a SHA not on `origin/main`; fast-forwards to the target
  SHA rather than the branch tip when the tip is newer; leaves `HEAD` untouched
  when the compose diff hits a non-allowlisted service; restores the recorded SHA
  on rollback. `git` is already in the image, so this needs no new dependency.
- Phase-level shell coverage of `restart-prod.sh` under `DRY_RUN=1` with a
  throwaway state dir. This is not optional: every remediation path shells out to
  `docker compose`, so merely running it performs real restarts. The same warning
  already applies to `monitor-prod.sh` and is recorded in `CLAUDE.md`.
- Removal coverage: existing `DataOperationsController` tests for `/redeploy`
  deleted, and a check that no `ProcessBuilder` remains in the backend.
- nginx: the maintenance flag serves the themed 503 for `www` and `api` and
  **does not** affect `/healthz`, `/webhooks/github` or the four ops hostnames;
  with the flag absent and an upstream stopped, `www` serves the themed
  unavailable page rather than a raw nginx 502.

## Accepted risks

- **The deployer holds the Docker socket.** Unavoidable for anything that
  deploys. Mitigated by no ingress, no public route, and a trigger that arrives
  only over Temporal from an HMAC-verified webhook.
- **The deployer goes stale** until manually updated. Accepted, per above.
- **A bad `main` reaches production automatically.** That is the feature. The
  guards are CI, the code-review bot, and SonarQube on the pull request — plus
  automatic rollback and a maintenance page when verification fails anyway.
- **A fast follow-up merge gets no deploy run of its own**, absorbed by
  coalescing.
- **The deployer writes to the deploy directory.** Bounded to commits on
  `origin/main` by `--ff-only` plus an ancestor assertion plus a clean-tree check,
  and it is not a privilege escalation over the socket it already holds.
- **A compose change touching a non-allowlisted service is not deployed at all**
  — reported, with the manual command, rather than half-applied.
- **`.env` is never synced**, so a change needing a new variable still needs a
  human.
- **A couple of seconds with nothing serving** while nginx restarts.
- **`FACTORY_DEPLOY_ENABLED` defaults off**, so merging this changes nothing in
  production until someone turns it on. That is intentional and mirrors
  `cvefix`'s paused-by-default schedule.

## Out of scope

**Keeping `CLAUDE_VERSION` current.** `Dockerfile.software-factory` pins the
Claude Code binary (`ARG CLAUDE_VERSION=2.1.220`) and checks its published
SHA-256, deliberately, so a review that passed yesterday is reproducible today.
Tracking `:latest` would throw that away.

The right answer is a scheduled flow that reads
`downloads.claude.ai/claude-code-releases/.../manifest.json`, compares it to the
pinned ARG, and opens a pull request bumping it — structurally a sibling of
`cve-fix`, reviewed like any other change. That is its own small spec and does
not belong tangled into deploy orchestration.

Also out of scope: blue/green or any zero-downtime scheme (one Pi, one node),
deploying anything other than the three images CI publishes, and automatic
rollback of a Mongock change unit.
