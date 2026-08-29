# Auto-deploy on Merge Runbook

A merge to `main` ends with the change live on the Pi, with no human action. This
is how that works, how to turn it off, and how to fix it when it goes wrong.

Read [software-factory.md](software-factory.md) first. Everything there about the
image, the GitHub App, the Claude credential and the `env_file` prohibition
applies to the `deployer` too — it is the same image, run a second time with
different module flags, the pattern `FACTORY_CVEFIX_ENABLED` already established.

## Shape

| Component | Role | Docker socket | Public ingress |
| --- | --- | --- | --- |
| `software-factory` | Receives the signed `workflow_run` webhook, starts the workflow | no | `POST /webhooks/github` only |
| `deployer` | **Same image.** Polls the `deploy` Temporal task queue and executes | **yes** | none |
| `nginx` | Serves the themed maintenance page off a flag file | no | all public hostnames |
| `scripts/restart-prod.sh` | The deploy itself — one script, shared by the deployer and by hand | | |

The socket is not on `software-factory` because that container terminates
untrusted internet traffic, and a Docker socket is root-equivalent on the host.
It goes on a container with no ingress at all, whose trigger arrives over Temporal
from a service that has already verified an HMAC signature.

## The phases

`sync-config` → `maintenance-on` → `pull` → `recreate` → `verify` →
`maintenance-off` → `verify-public`

| Phase | Does |
| --- | --- |
| `sync-config` | Fast-forwards the deploy directory to the deployed commit, if that is provably safe |
| `maintenance-on` / `maintenance-off` | Creates/removes `/var/run/deploy-state/maintenance.on` |
| `pull` | Records the current image ID of each target service, then pulls the head SHA and re-tags it to `:latest` |
| `recreate` | `up -d --no-deps --pull never` each target, `restart nginx`, then a full `up -d` reconcile |
| `verify` | The container settle loop, plus the four ops hostnames |
| `verify-public` | `www` and `api` — only after the page comes down |
| `rollback` | Re-tags `:latest` back to the recorded image IDs and recreates |

Two things about this list are load-bearing and easy to "tidy" into bugs:

- **`verify` deliberately excludes `www` and `api`.** The maintenance page
  *returns* 503 by design, and the hostname check treats 503 as a failure —
  correctly. Checking them while the flag is set would fail every single deploy.
- **Bare `./scripts/restart-prod.sh` never runs `sync-config`.** A human typing
  the script after their own `git pull` must not have it decide to move `HEAD`
  for them. It is opt-in, and only the deployer opts in.

Full contract:
`specs/036-auto-deploy-on-merge/contracts/restart-prod-phases.md`.

## Turning it off

Four levers, coarsest first. All are `.env` changes plus a recreate of the named
service.

| Lever | Effect |
| --- | --- |
| `FACTORY_DEPLOY_TRIGGER_ENABLED=false` on `software-factory` | No new deploys start. Code review is unaffected. |
| `FACTORY_DEPLOY_ENABLED=false` on `deployer` | Nothing executes. A triggered deploy waits in the Temporal queue rather than being lost. |
| `FACTORY_DEPLOY_SYNC_CONFIG=false` on `deployer` | Images-only deploys; the host checkout is never touched. |
| `FACTORY_DEPLOY_ROLLBACK_ENABLED=false` on `deployer` | A failed deploy stops and reports instead of rolling back — for when rollback itself is the problem. |

The first two are separate on purpose: a broken deployer must be silenceable
without also silencing code review.

## Keeping the deployer current

**The deployer never recreates itself, and therefore does not self-update.**

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
docker compose -f docker-compose.prod.yml up -d --no-deps deployer
```

Recreating the container that is mid-orchestration is how the backend's old
redeploy path went wrong — it needed an ephemeral helper container to work
around it — so the deployer excludes itself from both
`FACTORY_DEPLOY_SERVICES` and `FACTORY_DEPLOY_RECREATABLE`.

This is an accepted staleness risk, and it is the same shape as the bug that left
`software-factory` running a months-old image until 2026-08-11. It is recorded
here **and** in the `prod-deploy` skill, so it surfaces during a deploy rather
than only in a runbook nobody re-reads. Run it after any merge that changes
`software-factory/` or `Dockerfile.software-factory`.

## Reading a deploy

Durably, outside Temporal's retention window and outside container logs that a
deploy itself rotates:

```bash
docker exec -it simonrowe-dev-monorepo-mongodb-1 mongosh software_factory \
  --eval 'db.deploy_runs.find().sort({startedAt:-1}).limit(5).pretty()'
```

Each record holds the commit, the trigger, every phase's outcome, the
configuration-sync decision, whether a rollback was taken and whether it
verified, whether the maintenance page was left up, and the reported issue URL —
a Linear ticket, not a GitHub issue; see [linear.md](linear.md).

For a run still inside the retention window, `temporal.simonrowe.dev` shows the
live phase and the `progress()` query.

## When something is wrong

### The site is showing the maintenance page and nothing is moving

Check the run record's `maintenancePageLeftUp` and `status`:

- `ROLLBACK_FAILED` — **this is the case that needs a human now.** The deploy
  failed, the rollback also failed verification, and the page was deliberately
  left up rather than exposing a broken site. Read the Linear ticket the run
  filed (`issueUrl` on the run record; see [linear.md](linear.md)), then fix
  forward or restore by hand.
- `ROLLBACK_DISABLED` — the operator turned rollback off, so the broken version
  is still running behind the page.

To take the page down by hand once the site is actually serving:

```bash
docker compose -f docker-compose.prod.yml exec deployer \
  rm -f /var/run/deploy-state/maintenance.on
```

### A merge deployed images but not the configuration

Expected behaviour, and it is reported rather than silent — look for a commit
comment titled "Deployed — images only". `sync-config` declines, without moving
`HEAD` at all, when:

| Decision | Meaning |
| --- | --- |
| `dirty-tree` | A tracked file in the deploy directory is modified — someone is working on the box. Untracked and ignored files (including `.env`) never block. |
| `not-an-ancestor` | The target commit is not on `origin/main`. |
| `held-back` | The change affects a service outside `FACTORY_DEPLOY_RECREATABLE`. |
| `missing-variable` | The new compose file references a variable the host's `.env` does not define. `.env` is host-managed and never synced. |

For `held-back`, the report names the services and the exact command to apply
them by hand. Deciding first and moving `HEAD` second is deliberate: a
fast-forward followed by a refusal to recreate would leave the deploy directory
ahead of what is running, and `monitor-prod.sh`'s next bare `up -d` would apply
the held-back change within the minute.

#### A decline does not clear itself — it wedges the checkout

This is the part that cost ten days in production, from 2026-08-28. **Fix a
decline when you see it; do not wait for the next merge to sort it out.**

`sync-config` compares the **host checkout** against the target commit, not the
previous target against the target. So once a decline leaves `HEAD` behind, the
same difference is still there next time, and every subsequent merge declines for
the identical reason. Meanwhile `SyncDecision.deployImagesAnyway()` is true for
every decline, so each of those merges still pulls and recreates images. Images
track `main`; `docker-compose.prod.yml`, `config/nginx/` and `scripts/` stay
frozen at whatever commit the checkout stopped on.

What actually happened: #130 added `FACTORY_RUNTIME_ROLE: deployer` to the
`deployer` service. `deployer` is deliberately absent from
`FACTORY_DEPLOY_RECREATABLE` — it must never recreate itself mid-deploy — so the
change was held back, correctly. #131 and #132 were then held back too, for the
same `deployer` difference, and #132's new `location /s/` never reached the host.
Under the frontend's old nginx bind mount that made every share link on the site
return the SPA's 404 while `api.simonrowe.dev/s/<slug>` served the right document.
That mount is gone, so a frozen checkout no longer breaks SPA routing — but it
still freezes the compose file, the proxy conf and the scripts.

Recovering it is one command on the host, the one the phase already printed:

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
docker compose -f docker-compose.prod.yml up -d <held-back services>
```

Then let the next merge fast-forward, or force the point with
`git fetch --no-tags <repo-url> main && git merge --ff-only <sha>`. Note that
fast-forwarding by hand also lands every *other* pending host-side change at once,
and `monitor-prod.sh`'s next bare `up -d` applies them within the minute — so read
`git log --oneline HEAD..FETCH_HEAD -- docker-compose.prod.yml config/ scripts/`
first and know what you are turning on.

**How to spot it without host access.** A commit comment from
`simonrowe-software-factory[bot]` on a merge commit *is* the images-only signal: a
fully-applied deploy posts nothing at all, because `DeployWorkflowImpl.finish`
comments on success for exactly one status, `DEPLOYED_IMAGES_ONLY`.

```bash
gh api repos/simonjamesrowe/simonrowe-dev-monorepo/commits/<sha>/comments \
  --jq '.[] | .created_at + "  " + .user.login'
```

Until 042 that comment rendered as the bare line "The site is up." — the notice
naming the held-back services lived in `partialDeployComment`, which nothing
called. `commitComment` now carries it.

### Nothing deploys at all, and there is no error anywhere

In order of likelihood:

1. **The GitHub App is not subscribed to `workflow_run`.** No delivery ever
   arrives, so there is nothing to log. See
   [software-factory-manual-actions.md](software-factory-manual-actions.md).
2. **A flag is off.** Both default to `false`.
3. **No poller on the `deploy` task queue.** A container can be `healthy` with
   no poller registered, in which case nothing ever deploys. Check the queue in
   the Temporal UI, not the healthcheck. This failure mode is already documented
   for `code-review` and applies identically.

Note that `software-factory` *does* register a workflow-task poller on the
`deploy` queue even with every flag off — that is expected and harmless.
`@WorkflowImpl` classpath scanning is unconditional, and a workflow
implementation only schedules activities. What confines the Docker socket to the
`deployer` is that `DeployActivitiesImpl` is gated on `factory.deploy.enabled`,
so `software-factory` holds no implementation of any deploy step.

### A deploy is stuck on `verify`

`verify` waits up to `VERIFY_TIMEOUT` (420s) for every container to settle,
because Elasticsearch needs ~130s to bind 9200 on this Pi and the backend another
~90s after that. That is normal. If it fails, the settle loop names the
containers and explains the `created` state, and the run's Linear ticket, when
filing is enabled, quotes the logs (see [linear.md](linear.md)).

### sync-config declines every merge, naming a variable that IS in .env

Fixed, but worth knowing the shape. `sync-config` validates the incoming compose
file by writing it to `mktemp` and running `docker compose -f <tmp> config -q`.
Compose derives the project directory — and therefore where it looks for `.env` —
from the **compose file's own location**, so it read `/tmp/.env`, found nothing,
and every `${VAR:?}` failed as "required variable ... is missing a value".

That is indistinguishable from the genuine `missing-variable` case the check
exists to catch, and it fires on *whichever* required variable compose reaches
first — so three consecutive merges reported three different variables, all of
them present in `.env` all along. Nothing deployed: `sync-config` fails before
`maintenance-on`, so the site is never touched and the only symptom is that
merges silently stop reaching production.

Every `docker compose` call against a file outside the project directory needs
`--project-directory "$PROJECT_DIR"`. `service_hashes` had the same bug with a
worse failure mode: its stderr is discarded, so it returned an empty hash list,
which reads as "no service changed" and would let a non-allowlisted service
through the held-back check.

### The deploy kills its own deployer

Fixed. `reconcile()` used to run a bare `docker compose up -d`, which ignored
`FACTORY_DEPLOY_RECREATABLE` and would recreate the `deployer` — SIGTERMing the
container running the deploy. The replacement is left in `created`, because the
process that would have started it was the one being killed, and the workflow
then sits with no worker until the activity heartbeat times out.

The trigger is not "the merge changed the deployer". `deployer` and
`software-factory` share `${FACTORY_IMAGE}` (`software-factory:latest`), and the
`pull` phase re-tags `:latest` to the new image — so compose sees an image change
on the deployer on **every deploy where the factory image changed**. It happened
twice before the cause was understood.

`reconcile()` now enumerates services (`config --no-interpolate --services`) and
excludes `deployer`. If it is ever reintroduced, the symptom is a deploy that
stalls for ~10 minutes mid-`recreate` with the maintenance page up, and recovers
on its own once someone runs
`docker compose -f docker-compose.prod.yml up -d --no-deps deployer`.

## Running compose from inside the deployer

The deployer drives the **host's** Docker daemon from inside a container, and that
one fact caused every prerequisite failure found during the first rollout. All of
them are now fixed in `docker-compose.prod.yml`; this section exists so a future
change does not reintroduce one, because each failed in a way that looked like
something else.

| What | Why it breaks | Fix in place |
| --- | --- | --- |
| Bind-mount paths | Compose resolves the nine `./...` binds against its project directory and hands the result to the **host** daemon. A container-private project dir means host paths that do not exist — and the daemon *creates* a missing bind source as an empty directory rather than erroring, so the container fails with "not a directory" and the host root gets littered. | The deploy directory is mounted **at its own host path on both sides** (`${DEPLOY_DIR}:${DEPLOY_DIR}`). |
| Project name | Compose derives it from the directory name, so a container-private path meant a *different, empty* project: `up -d` would build a second parallel stack and report success while the live site ran the old images. | `COMPOSE_PROJECT_NAME` pinned on `deployer`. |
| Docker socket | `root:docker 0660`; the container runs as `factory` (uid 10003) with no supplementary groups. Healthy container, registered poller, then "permission denied" on the first compose call. | `group_add: ["${DOCKER_GID:-984}"]`. |
| `.env` | 0600 and host-owned, but compose interpolates it on every call. | `.env` is mode **0640** and the deployer joins its **owning group** via `group_add`. Not a `chgrp` — see below. |
| `deploy-state` volume | Docker creates a named volume `root:root`; `maintenance-on` and `pull` both write there. | `deploy-state-init`, mirroring `uploads-init`. |
| git ownership | The checkout is owned by the host login, not `factory`, so git refuses it — and `sync-config` reports the misleading "`<dir>` is not a git checkout". | `GIT_CONFIG_COUNT`/`KEY_0`/`VALUE_0` set `safe.directory`. |
| Variables the container also sets | **Compose gives the process environment precedence over `.env`.** Any variable that is both interpolated in this file and present in the deployer's own environment resolves to the *container's* value when the deployer runs compose. | The two that collided are split or removed — see below. |

### The environment-precedence trap

This is the subtle one, and it will happen again if someone reuses a name.

- `GITHUB_APP_PRIVATE_KEY_PATH` meant two different things: the host path (mount
  source) and the in-container path (what the app reads). The deployer sets the
  container value, so its reconcile resolved the *mount source* to
  `/run/secrets/github-app.pem`, which does not exist on the host. Now split into
  `GITHUB_APP_PRIVATE_KEY_HOST_PATH` (source) and `GITHUB_APP_PRIVATE_KEY_PATH`
  (destination). **Do not merge them back.**
- `FACTORY_DEPLOY_TRIGGER_ENABLED` was pinned `"false"` on `deployer`. With the
  flag true in `.env`, the deployer's own reconcile would have re-rendered
  `software-factory` with the trigger **false** — auto-deploy would have worked
  exactly once and then switched itself off, on the very deploy meant to enable
  it. The line is now **absent**; the service has no `env_file`, so absence
  already yields false.

To check for a new collision:

```bash
grep -oE '\$\{[A-Z_][A-Z0-9_]*' docker-compose.prod.yml | sed 's/${//' | sort -u > /tmp/interp
docker exec simonrowe-dev-monorepo-deployer-1 env | cut -d= -f1 | sort -u > /tmp/depenv
comm -12 /tmp/interp /tmp/depenv     # any name here must mean the same thing on both sides
```

The definitive test is that both sides render the same stack:

```bash
D=/home/simonrowe/workspace/simonjamesrowe/simonrowe-dev-monorepo
docker compose -f docker-compose.prod.yml config --hash='*' | sort > /tmp/a
docker exec simonrowe-dev-monorepo-deployer-1 \
  sh -c "cd $D && docker compose -f docker-compose.prod.yml config --hash='*'" | sort > /tmp/b
diff /tmp/a /tmp/b        # must be empty
```

Run it from a **clean shell**: an interactive Claude Code session exports
`CLAUDE_*` variables that this file interpolates, so `docker compose` run from one
renders `software-factory` differently from cron. Prefix with
`env -u CLAUDE_EFFORT -u CLAUDE_MODEL -u CLAUDE_TIMEOUT -u CLAUDE_MAX_TURNS -u CLAUDE_CODE_OAUTH_TOKEN`.

### Why the deployer joins .env's group instead of .env changing group

`docker compose` interpolates `.env` on every call, so the deployer must be able to
read a host-owned 0640 file. The obvious fix — `chgrp factory .env` — **does not
hold**, and fails in the worst possible way.

`sed -i`, and every editor that writes a temp file and renames it over the
original, recreates `.env` with the host user's default group. The chgrp is
silently undone by the next edit of `.env`; nothing warns; and the next deploy
fails at `recreate` with `open .../.env: permission denied` — *after* the
maintenance page is up, so it takes the site down and ends `ROLLBACK_FAILED`
(the rollback runs compose too, so it fails identically). This was observed
exactly that way, caused by nothing more than flipping a flag with `sed -i`.

So the group membership goes on the container, where an edit to `.env` cannot
disturb it:

```yaml
group_add:
  - "${DOCKER_GID:-984}"       # getent group docker
  - "${DEPLOY_ENV_GID:-1000}"  # the group owning .env — `stat -c %G .env`
```

`.env` only has to stay **0640**. It is not world-readable, so no other host user
gains access.

## Host state this feature depends on

Not in git, and lost on a host rebuild:

```bash
stat -c '%U:%G %a' .env      # must be group-readable (0640); its gid = DEPLOY_ENV_GID
getent group docker          # gid must match DOCKER_GID (default 984)
```

`.env` must also define `DEPLOY_DIR` (the absolute deploy directory) and
`GITHUB_APP_PRIVATE_KEY_HOST_PATH`.

## Rollout order

Only needed once, and the order matters because each step can fail invisibly. See
`specs/036-auto-deploy-on-merge/quickstart.md` for the full version with
commands.

1. Merge. Both flags default off, so production is unchanged.
2. `git pull` on the Pi — the `deployer` service, the maintenance pages and the
   proxy nginx conf are all host-side. One-off: from here on the deployer
   fast-forwards the directory itself. (`frontend/nginx.conf` is *not* host-side;
   since 042 it ships inside the frontend image.)
3. `docker compose -f docker-compose.prod.yml up -d nginx deployer`, then prove
   the pages render by touching and removing the flag by hand — and confirm
   `console.simonrowe.dev` stays up while the flag is set.
4. Subscribe the GitHub App to `workflow_run`.
5. `FACTORY_DEPLOY_ENABLED=true`, recreate `deployer`, and assert a live poller
   on the `deploy` queue.
6. Trigger a deploy from the Temporal UI on the SHA **already in production**, so
   `sync-config` reports `already-current` and every phase runs without changing
   anything.
7. Only then `FACTORY_DEPLOY_TRIGGER_ENABLED=true` on `software-factory`.

## Accepted risks

- **The deployer holds the Docker socket.** Unavoidable for anything that
  deploys. Mitigated by no ingress, no published port, no public route, and a
  trigger that arrives only over Temporal from an HMAC-verified webhook.
- **The deployer goes stale** until manually updated, per above.
- **A bad `main` reaches production automatically.** That is the feature. The
  guards are CI, the code-review bot and SonarQube on the pull request — plus
  automatic rollback and a maintenance page when verification fails anyway.
- **A fast follow-up merge gets no deploy run of its own**, absorbed by
  coalescing on the fixed workflow id `deploy-prod`. Its commit is still
  deployed, by the drain loop or by the next run.
- **The deployer writes to the deploy directory.** Bounded to commits on
  `origin/main` by `--ff-only` plus an ancestor assertion against a pinned URL
  plus a clean-tree check. Not a privilege escalation over the socket it already
  holds.
- **A couple of seconds with nothing serving** while nginx restarts, since nginx
  is what serves the maintenance page.
- **`.env` is never synced**, so a change needing a new variable still needs a
  human — reported, not silently broken.

## Testing changes to any of this

```bash
./scripts/test/run-tests.sh          # phases, sync-config, nginx pages
./gradlew :software-factory:test
```

**Never run `restart-prod.sh` without `DRY_RUN=1` on a machine with the
production stack.** Every remediation path shells out to `docker compose`, so
merely running it performs real restarts and can recreate containers if the
compose file has been edited since the last deploy. The same warning applies to
`monitor-prod.sh` and is recorded in `CLAUDE.md`.

The `sync-config` tests deliberately opt out of `DRY_RUN` — real git behaviour is
the thing under test — and are safe because every one of them builds its own
throwaway origin and clone under `mktemp`.
