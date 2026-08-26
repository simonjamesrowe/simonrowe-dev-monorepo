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
verified, whether the maintenance page was left up, and the reported issue URL.

For a run still inside the retention window, `temporal.simonrowe.dev` shows the
live phase and the `progress()` query.

## When something is wrong

### The site is showing the maintenance page and nothing is moving

Check the run record's `maintenancePageLeftUp` and `status`:

- `ROLLBACK_FAILED` — **this is the case that needs a human now.** The deploy
  failed, the rollback also failed verification, and the page was deliberately
  left up rather than exposing a broken site. Read the issue the run opened, then
  fix forward or restore by hand.
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
containers and explains the `created` state, and the run's triage issue quotes
the logs.

## Rollout order

Only needed once, and the order matters because each step can fail invisibly. See
`specs/036-auto-deploy-on-merge/quickstart.md` for the full version with
commands.

1. Merge. Both flags default off, so production is unchanged.
2. `git pull` on the Pi — the `deployer` service, the maintenance pages and the
   nginx conf are all host-side. One-off: from here on the deployer fast-forwards
   the directory itself.
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
