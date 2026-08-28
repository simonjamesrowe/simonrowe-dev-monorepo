# Software Factory

`software-factory/` is a Temporal-backed modular monolith. Two containers run the same image:
`software-factory` receives the signed GitHub webhook and owns tracker credentials, while
`deployer` has no ingress and exclusively owns the Docker socket. Workflow pollers may exist in
both containers; conditional activity beans determine where side effects can execute.

The administration console at `/admin/software-factory` reports the effective flag, workflow and
activity pollers, trigger, and Temporal schedule for every module. Its browser calls go through the
admin-role-protected backend. The backend alone holds `FACTORY_TRIGGER_TOKEN` and calls unrouted,
token-protected factory endpoints over the compose network.

## Modules and production defaults

| Module | Queue | Normal trigger | Production default | Manual admin action |
| --- | --- | --- | --- | --- |
| Code review | `code-review` | Pull-request webhook | On | Dry-run or published review of a PR |
| Feedback | `review-feedback` | Pull-request close webhook | On (`FACTORY_FEEDBACK_ENABLED`) | Process a closed PR |
| Vulnerability scan | `cve-fix` | Daily Temporal schedule | On (`FACTORY_CVEFIX_ENABLED`) | Scan now |
| Deploy | `deploy` | Successful `Publish` workflow | Executor and automatic trigger off | Confirmed redeploy of the running SHA |
| Linear filing | `linear` | Activity requested by another workflow | On (`FACTORY_LINEAR_ENABLED`) | None; it is an activity sink |
| Platform backup | `platform-backup` | 02:00 Europe/London schedule | On (`FACTORY_PLATFORM_BACKUP_ENABLED`) | Dry run or real capture |

Application-level flag defaults remain false because the same image also runs as the differently
privileged `deployer`. The defaults above are applied only to each owning production service in
`docker-compose.prod.yml`. An explicit environment value of `false` still wins.

Temporal schedules are durable server state. A feature flag and its schedule can therefore
disagree: the console reports both. A newly-created CVE or platform-backup schedule is active, but
an operator's existing pause is preserved across restarts.

## Module behavior

### Code review

An exact pull-request head SHA is cloned into a fresh workspace and reviewed read-only. The agent
can publish review comments but cannot push, approve, merge, or trigger a manual admin review.

### Feedback

An eligible closed PR is harvested into durable lessons. When useful lessons exist, the workflow
first creates one Linear issue keyed by repository and pull-request number. It includes the source
PR, evidence, proposed guidance, scope, and acceptance criteria. Guidance PRs reference that issue
and are attached back to it. `FACTORY_FEEDBACK_ENABLED=false` disables both webhook and manual
starts without affecting other modules.

### Vulnerability scan

The daily or manual workflow reads all current Dependency-Track findings and files one consolidated
Linear report for the repository. A repeated scan updates the same open issue with a complete
snapshot. The module has no git workspace, fix agent, push, pull-request, or CI repair path. A
future Linear-triggered remediation agent is deliberately out of scope.

### Deploy

Automatic deployment remains opt-in through separate trigger and executor flags. The admin action
does not enable arbitrary deployment: frontend and backend must report the same currently running
40-character commit and the administrator must type `REDEPLOY <short-sha>`. The backend validates
both immediately before signalling the existing fixed-id `deploy-prod` workflow.

### Linear filing

This is an activity-only queue, not a standalone workflow or admin action. Producers use a stable
fingerprint and the sink creates, comments on, suppresses, or files a regression according to the
existing Linear issue state. Feedback can additionally attach guidance PR URLs idempotently.

### Platform backup

The `deployer` executes `scripts/backup-platform.sh`; the public-facing factory and backend never
receive Docker access. The schedule is active nightly at 02:00 Europe/London. Admin dry-run and
real-run requests use Temporal and reject a conflicting scheduled or manual capture. Restore stays
a host recovery operation documented in
[platform-backup-restore.md](runbooks/platform-backup-restore.md).

## Local operation

```bash
docker compose up -d temporal mongodb
./gradlew :software-factory:bootRun
```

- Factory API: `http://localhost:8090`
- Factory management port: `http://localhost:8091/actuator/health`
- Temporal UI: `http://localhost:8233`

Only the exact GitHub webhook route is exposed by production nginx. Every manual action route is
both unrouted and protected by `X-Factory-Token`, and `FactoryPublicSurfaceTest` reads
`config/nginx/nginx-proxy.conf` to keep that true.

`GET /api/factory/status` is unrouted but **unauthenticated**, and it has to be: the backend asks
both containers for it, and `deployer` deliberately holds no `FACTORY_TRIGGER_TOKEN`. Requiring one
would make the deployer report itself permanently unreachable, disabling the deploy and
platform-backup actions. `GET /api/factory/runs/{workflowId}` beside it does require the token,
because a run's `detail` is free-text diagnostics rather than a boolean.

## Diagnostic rule

A healthy container is not proof that a module can execute. **Four** independent things have to
agree, and the admin page presents them separately for exactly that reason:

1. the configured flag in the owning container;
2. the required Temporal pollers — workflow and activity counted separately, and `null` rather
   than `0` when Temporal could not be asked, because "no poller" and "we do not know" call for
   different actions. The `linear` queue is activity-only by design and is exempt from the
   workflow-poller check;
3. for `cvefix` and `platformbackup`, the schedule's existence and pause state;
4. the module's own prerequisites — `ModulePrerequisites` reports, per module, an enabled flag
   whose credential or host path is still unset. Four flags now default true while
   `LINEAR_API_KEY`, `FACTORY_LINEAR_TEAM_KEY` and `DEPENDENCYTRACK_API_KEY` still default empty,
   so "enabled but not usable" is the most likely state after a first deploy. It is also logged
   once at startup, since an operator who has just flipped a flag reads container logs first.

A module is `ready` only when all four agree, and the backend refuses a manual action whose module
is not ready — a workflow started on a queue nothing polls does not fail, it sits in Temporal
looking accepted until an activity timeout.
