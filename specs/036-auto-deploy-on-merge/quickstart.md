# Quickstart: Auto-deploy on merge

How to verify this feature locally, and how to roll it out on the Pi. The rollout
order is not advisory — each step exists because the one before it can fail
invisibly.

## Local verification (no Pi, no deploy)

### 1. The shell script, which is where the real risk is

```bash
cd /path/to/repo
DRY_RUN=1 ./scripts/test/run-tests.sh
```

**Never run `restart-prod.sh` without `DRY_RUN=1` on a machine with the
production compose stack.** Every remediation path shells out to
`docker compose`, so merely running it performs real restarts and can recreate
containers if the compose file has been edited since the last deploy. The same
warning already applies to `monitor-prod.sh` and is recorded in `CLAUDE.md`.

Individual phases, by hand:

```bash
DRY_RUN=1 STATE_DIR=/tmp/ds ./scripts/restart-prod.sh maintenance-on
ls /tmp/ds                       # maintenance.on
DRY_RUN=1 STATE_DIR=/tmp/ds ./scripts/restart-prod.sh maintenance-off
DRY_RUN=1 IMAGE_TAG=abc123 SERVICES=backend ./scripts/restart-prod.sh pull
```

`sync-config` against a scratch clone — never against a real deploy directory:

```bash
tmp=$(mktemp -d)
git clone --no-local . "$tmp/repo"
DRY_RUN=1 REPO_URL="$PWD" ./scripts/restart-prod.sh sync-config "$(git rev-parse HEAD)"
```

### 2. The Java side

```bash
./gradlew :software-factory:test :software-factory:checkstyleMain
```

The tests that matter most:

- `DeployWorkflowTest` — happy path; verify-fails-then-rollback-succeeds;
  verify-fails-and-rollback-fails (asserting the maintenance flag is **not**
  cleared); activity retry idempotency; two signals coalescing into one deploy;
  a config sync declined for a non-allowlisted service still deploying images
  and reporting.
- `DeployWorkerRegistrationTest` — `DeployActivitiesImpl` is absent from the
  context with the flag off and present with it on. This is the test that keeps
  the Docker socket confined to the deployer; if it is deleted the only symptom
  is an intermittent deploy failure on whichever JVM won the activity task.
- `GitHubWebhookControllerTest` — `workflow_run` accepted for
  Publish/success/main; ignored for a failed conclusion, a non-`main` branch,
  another workflow name, a non-allowlisted repo, and the trigger flag off;
  unsigned still `401`.

### 3. The maintenance pages

Open `config/nginx/maintenance/maintenance.html` and `unavailable.html` directly
in a browser. They must render correctly **with no network access** — both are
self-contained with all CSS inlined and no external asset, because the frontend
that would serve those assets is precisely what is down.

Validate the proxy conf:

```bash
docker run --rm -v "$PWD/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t
```

### 4. The whole thing against a local stack (optional, macOS/OrbStack)

`docker-compose.prod.yml` runs on macOS with two overrides in `.env`:

```
DOCKER_BINARY_PATH=/opt/homebrew/bin/docker
DOCKER_PLUGINS_PATH=~/.docker/cli-plugins
```

The compose defaults (`/usr/bin/docker`,
`/usr/libexec/docker/cli-plugins`) do not exist on macOS, and the `deployer`
needs both — the same mechanism, and the same reason, as the mount the `backend`
is losing.

## Rollout on the Pi, in order

**1. Merge.** Both flags default off, so production is unchanged. Nothing
deploys, nothing errors.

**2. `git pull` on the Pi.**

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo && git pull
```

The `deployer` service definition, the maintenance pages and the nginx conf are
all host-side, so nothing works until this happens. This step is a one-off: from
here on the deployer does its own fast-forwarding. It is needed this once because
the `deployer` service definition cannot deploy itself.

**3. Start nginx and the deployer, and prove the pages work.**

```bash
docker compose -f docker-compose.prod.yml up -d nginx deployer
docker compose -f docker-compose.prod.yml exec deployer touch /var/run/deploy-state/maintenance.on
curl -s -o /dev/null -w '%{http_code}\n' https://www.simonrowe.dev/     # 503
curl -s -o /dev/null -w '%{http_code}\n' https://api.simonrowe.dev/api/blogs   # 503
curl -s -o /dev/null -w '%{http_code}\n' https://console.simonrowe.dev/ # NOT 503
curl -s http://localhost/healthz                                        # ok (from inside nginx)
docker compose -f docker-compose.prod.yml exec deployer rm /var/run/deploy-state/maintenance.on
curl -s -o /dev/null -w '%{http_code}\n' https://www.simonrowe.dev/     # back to 200
```

Confirm `console.simonrowe.dev` stayed up while the flag was set. If it did not,
stop — the flag has leaked out of the `www`/`api` blocks, and Portainer is how a
failing deploy gets fixed.

**4. Subscribe the GitHub App to the `workflow_run` event.** Only a human can do
this, and without it no delivery ever arrives and the feature is inert with **no
error anywhere**. Recorded in
`docs/runbooks/software-factory-manual-actions.md`.

**5. Enable the executor and confirm it is actually listening.**

```bash
# FACTORY_DEPLOY_ENABLED=true in .env
docker compose -f docker-compose.prod.yml up -d --no-deps deployer
```

Then assert a **live poller on the `deploy` task queue** in the Temporal UI, not
just a healthy container. A container can be `healthy` with no poller registered,
in which case nothing ever deploys — that failure mode is already documented for
the `code-review` queue and applies identically here.

**6. Trigger a deploy by hand, on the SHA already in production.** From
`temporal.simonrowe.dev`, start `DeployWorkflow` on the `deploy` queue with the
current production SHA. `sync-config` reports `already-current`, so the run
exercises every phase without changing anything. This is the rehearsal, and it is
the last point at which nothing has happened automatically.

**7. Only then `FACTORY_DEPLOY_TRIGGER_ENABLED=true` on `software-factory`.**

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps software-factory
```

## Keeping the deployer current

The deployer **never recreates itself** — recreating the container that is
mid-orchestration is how the backend's redeploy path went wrong. So it does not
self-update, and after a merge that changes the factory image it is running the
previous one until someone runs:

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps deployer
```

This is a known, accepted staleness risk. It is the same shape as the bug that
left `software-factory` on an old image for months, so the step is recorded in
`docs/runbooks/deploy.md` **and** in the `prod-deploy` skill, where it surfaces
during a deploy rather than only in a runbook nobody re-reads.

## Turning it off

Three levers, coarsest first:

| Lever | Effect |
| --- | --- |
| `FACTORY_DEPLOY_TRIGGER_ENABLED=false` on `software-factory` | No new deploys start. Code review is unaffected. |
| `FACTORY_DEPLOY_ENABLED=false` on `deployer` | Nothing executes. A triggered deploy waits in the queue rather than being lost. |
| `FACTORY_DEPLOY_SYNC_CONFIG=false` on `deployer` | Images-only deploys; the host checkout is never touched. |
| `FACTORY_DEPLOY_ROLLBACK_ENABLED=false` on `deployer` | A failed deploy stops and reports instead of rolling back — for the case where rollback itself is the problem. |

## Reading a deploy afterwards

```bash
docker exec -it simonrowe-dev-monorepo-mongodb-1 mongosh software_factory \
  --eval 'db.deploy_runs.find().sort({startedAt:-1}).limit(5).pretty()'
```

Or the Temporal UI for a run still inside the retention window. The Mongo record
is the one that survives retention and a log rotation, which is why it exists.
