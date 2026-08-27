# Runbook: production monitoring and self-healing

How the Pi keeps itself up, what it can and cannot fix on its own, and the two
host-level defects that make cold starts risky.

## The watchdog

`scripts/monitor-prod.sh`, installed by `scripts/install-prod-monitoring.sh` as a
**once-a-minute cron job** logging to `/var/log/prod-health/monitor.log`
(logrotate: daily, 7 days). Check it is live with:

```bash
crontab -l | grep monitor-prod
tail -50 /var/log/prod-health/monitor.log
```

It runs three layers, cheapest first, and stops after the first one that acts.

| Layer | Question | Remedy |
| --- | --- | --- |
| 1. Site | Does `https://www.simonrowe.dev` answer? | After 3 consecutive failures, `docker compose up -d` to reconcile the whole stack |
| 2. Container health | Does every container report `running` + `healthy`? | After 3 consecutive ticks `unhealthy`, restart **that** service. Containers in `created`/`exited` trigger a stack reconcile instead |
| 3. Endpoint | Does each public hostname actually serve? | After 3 consecutive bad responses, restart the single service behind it |

Backoff: the whole-stack path allows 3 reconciles per 10 minutes; each service
allows 2 restarts per 30 minutes. On exhaustion it logs `CRIT ... Needs a human.`
and stops trying — grep for `CRIT` when something has been down a while.

### Why layer 2 has to exist

**Docker never restarts an unhealthy container.** `restart: unless-stopped` only
fires when the process *exits*. A container whose healthcheck fails forever is
left running, untouched, indefinitely. Nothing in Docker or Compose closes that
gap, so the cron job is the only thing that does.

### Testing a change to the watchdog

**Use `DRY_RUN=1`.** Every remediation path shells out to `docker compose`, so
just running the script "to see what it prints" performs real restarts — and if
the compose file has been edited since the last deploy, its `up -d` will start
*recreating containers*. That is not hypothetical: it happened while this runbook
was being written, and stranded `frontend` in `created` (502 on www) because of
the backend-healthcheck bug described below.

```bash
DRY_RUN=1 STATE_DIR=/tmp/mon-test ./scripts/monitor-prod.sh
```

`DRY_RUN` logs each intended command as `[DRYRUN] would run: ...` and skips the
restart bookkeeping, so a test run cannot arm a real backoff window. Always pass a
throwaway `STATE_DIR` too, so failure counters do not leak into the live state at
`/tmp/prod-health`.

### Choosing probe URLs

Two traps, both already hit in this repo:

- **Do not probe a URL that answers without reaching origin.** `https://simonrowe.dev`
  301-redirects to `www` at the Cloudflare edge and `curl -f` treats 3xx as
  success, so it reports healthy with the entire origin down. Probe `www`.
- **Do not probe an actuator path on `api.simonrowe.dev`.** Management runs on its
  own port (8081) and nginx deliberately does not route it, so `/actuator/health`
  is a public 404 forever. The watchdog uses `/api/profile` — the smallest public
  200, and it reaches MongoDB, so it exercises the real path.

Dependency-Track needs **two** probes: its frontend renders fine while its API is
dead, which is exactly how the 2026-08-14 outage stayed invisible.

The full probe list lives in `ENDPOINTS` in the script, as
`<service>|<url>|<expected-codes>`:

| Service | Probe |
| --- | --- |
| `frontend` | `https://www.simonrowe.dev/` |
| `backend` | `https://api.simonrowe.dev/api/profile` |
| `langfuse` | `https://langfuse.simonrowe.dev/` |
| `dependencytrack-apiserver` | `https://dependency-track.simonrowe.dev/api/version` |
| `dependencytrack-frontend` | `https://dependency-track.simonrowe.dev/` |
| `temporal-ui` | `https://temporal.simonrowe.dev/` |
| `portainer` | `https://console.simonrowe.dev/` |

One-shot init containers (`uploads-init`, `temporal-db-init`,
`temporal-schema-init`, `temporal-create-namespace`, `dependencytrack-db-init`)
are excluded from the layer-2 health sweep — they are *supposed* to exit, and
other services gate on them with `condition: service_completed_successfully`.

### Installing it

```bash
./scripts/install-prod-monitoring.sh
```

Run from the repo root on the Pi. It enables and starts `cron`, creates
`/var/log/prod-health/monitor.log`, installs `/etc/logrotate.d/prod-health`
(daily, 7 days, `copytruncate`), registers the once-a-minute crontab entry with
this machine's absolute repo path, and then runs one verification check. It is
idempotent: an existing entry for the same script is replaced, not duplicated.

Doing it by hand instead:

```bash
sudo mkdir -p /var/log/prod-health
sudo chown "$USER:$USER" /var/log/prod-health
crontab -e
# * * * * * /absolute/path/to/repo/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
sudo systemctl enable cron && sudo systemctl start cron
```

### Configuration

Every knob is an environment variable with a default. To change one under cron,
set it inline in the crontab entry.

| Variable | Default | Description |
| --- | --- | --- |
| `CHECK_URL` | `https://www.simonrowe.dev` | Layer-1 site probe. Must be a URL nginx actually serves |
| `FAILURE_THRESHOLD` | `3` | Consecutive site failures before a whole-stack reconcile |
| `MAX_RESTARTS` | `3` | Whole-stack reconciles allowed per `BACKOFF_WINDOW` |
| `BACKOFF_WINDOW` | `600` | Whole-stack backoff window, seconds |
| `SERVICE_FAILURE_THRESHOLD` | `3` | Consecutive bad ticks before restarting one service |
| `SERVICE_MAX_RESTARTS` | `2` | Restarts allowed per service per `SERVICE_BACKOFF_WINDOW` |
| `SERVICE_BACKOFF_WINDOW` | `1800` | Per-service backoff window, seconds |
| `STATE_DIR` | `/tmp/prod-health` | Where the counters live |
| `COMPOSE_PROJECT` | `simonrowe-dev-monorepo` | Compose project name |
| `DRY_RUN` | `0` | `1` logs intended commands and skips all remediation |

Per-service remediation is deliberately less trigger-happy than the whole-stack
path: a container restart is cheap, but a restart *loop* is worse than one bad
service.

### State files

In `STATE_DIR`:

- `failure_count` — consecutive layer-1 failures, reset on success or reconcile
- `restart_timestamps` — epoch times of recent whole-stack reconciles, pruned each run
- `svc_<service>` — per-service failure counters and restart history

They live in `/tmp`, so a reboot clears them. That is the wanted behaviour: after
a reboot the stack needs starting fresh anyway, and a stale backoff window would
stop the watchdog helping exactly when it is most needed.

To reset by hand: `rm -rf /tmp/prod-health`.

### When the watchdog itself is not running

```bash
crontab -l | grep monitor-prod          # is it registered?
systemctl status cron                   # is cron running?
grep CRON /var/log/syslog | tail -20    # is it firing?
ls -la scripts/monitor-prod.sh          # is it executable?
```

If the log is full of `CRIT` instead, the stack reconcile is not helping. Check
host connectivity (`curl -I https://google.com`), container states
(`docker compose -f docker-compose.prod.yml ps -a` — anything in `Created` or
`Restarting`), the pinggy token (`docker compose -f docker-compose.prod.yml logs pinggy`;
one token allows one active tunnel, reclaim with `PINGGY_TOKEN=<token>+force`), and
[status.pinggy.io](https://status.pinggy.io).

## Host defect: the memory cgroup is disabled

```bash
docker info 2>&1 | grep -i 'WARNING.*memory'
#   WARNING: No memory limit support
#   WARNING: No swap limit support
cat /sys/fs/cgroup/cgroup.controllers
#   cpuset cpu io pids        <- no `memory`
```

The Raspberry Pi **firmware** prepends `cgroup_disable=memory` to the kernel
command line. It is in `/proc/cmdline` but in **no file under `/boot`**, so
grepping `cmdline.txt`/`config.txt` for it finds nothing and looks like a dead
end.

Two consequences:

1. **Every `mem_limit:` in `docker-compose.prod.yml` is silently ignored.** Docker
   accepts the value and enforces nothing; `docker stats` reports `0B / 0B`. There
   is no blast-radius containment — one runaway container can drive the whole host
   into swap and take every service with it.
2. **JVMs size their heap from the host total (15.84GiB), not their limit.**
   Dependency-Track's image sets `-XX:MaxRAMPercentage=80.0`, so it will grow a
   ~12.7GiB heap despite declaring `mem_limit: 2g`. The backend has no explicit
   `-Xmx` either. Enabling the controller is what makes the declared limits real
   *and* fixes JVM sizing, with no heap flags to tune.

### Fixing it

```bash
./scripts/enable-memory-cgroup.sh --verify   # report current state
./scripts/enable-memory-cgroup.sh --apply    # edit cmdline.txt (backs it up first)
# ... reboot at a planned time ...
./scripts/enable-memory-cgroup.sh --verify   # confirm; docker stats must not show 0B / 0B
```

`--apply` appends `cgroup_enable=memory cgroup_memory=1`. The kernel parses its
command line left to right and the firmware's `cgroup_disable=memory` comes first,
so the later explicit enable wins. `cmdline.txt` **must stay a single line** — the
bootloader ignores everything after the first newline — which the script enforces.
`--revert` undoes it before a reboot.

**The script does not reboot, deliberately.** A reboot here means a full-stack cold
start, which is the single riskiest event for this host (see below). Schedule it,
and verify the stack afterwards.

Measured steady-state PSS was ~6.8GiB of 15.84GiB across 21 containers, and the
declared `mem_reservation` values total ~5.2GiB, so enabling enforcement should not
by itself push anything into its cap. The `mem_limit` values carry roughly 2x
headroom over measured usage and are recorded per service in the compose file.

## Host defect: cold starts are the dangerous moment

Both services that broke on 2026-08-14 broke *at the same instant* — the host
rebooted (`uptime` confirmed a boot at 17:17 BST) and all 21 containers cold-started
together on 4 cores. Neither came back correctly:

- **Dependency-Track** hit `NoClassDefFoundError: dev/cel/runtime/CelFunctionBinding`
  while loading the CEL policy engine and its Jetty API listener never started. The
  class is present in `lib/runtime-0.13.1.jar` and on the classpath, and a plain
  `docker restart` fixed it with no image change — so this was a transient
  start-up failure, not a bad image.
- **Langfuse** came up with a corrupt `next-auth` module
  (`Failed to load external module .../react: SyntaxError: Invalid or unexpected token`),
  which 500'd every *page* while its API routes kept returning 200. Also fixed by a
  plain restart.

Both then stayed broken for **10 days**, because nothing was watching (see
"What changed after 2026-08-14"). After any reboot, do not assume a green
`docker compose ps` means the stack is serving:

```bash
for h in www api langfuse dependency-track temporal console; do
  echo -n "$h: "; curl -s -o /dev/null -w '%{http_code}\n' -m 15 "https://$h.simonrowe.dev/"
done
curl -s -o /dev/null -w 'dt-api=%{http_code}\n' https://dependency-track.simonrowe.dev/api/version
./scripts/monitor-prod.sh   # or wait one cron tick
```

`api.simonrowe.dev/` returning 404 is correct — it has no route at `/`.

## The backend healthcheck budget

`/actuator/health` aggregates Elasticsearch, Kafka, Mongo, mail and SSL, and the
Kafka indicator builds a **fresh AdminClient on every call**. Measured cost on the
Pi is **~9 seconds** while returning `{"status":"UP"}`.

The healthcheck used to allow `timeout 4` inside a 5s Docker timeout, so it marked
a perfectly healthy backend unhealthy. Because `frontend` declares
`depends_on: backend: condition: service_healthy`, `up -d` then aborted with
`dependency failed to start: ... backend is unhealthy` and left `frontend` in
`created` — a 502 on www until somebody re-ran the deploy. That is the
"just re-run `restart-prod.sh`" folklore; the real cause was the timeout.

Now `interval: 30s`, `timeout: 25s`, inner `timeout 20`. The interval was raised
from 10s as well: at 10s a 9s probe left almost no idle time, and since every probe
opens a Kafka AdminClient, frequent probing made the thing it was measuring slower.

If the backend is genuinely wedged this still catches it — the probe requires an
actual `"status":"UP"` body, not just a TCP connect.

To recover a stranded frontend without waiting on the dependency gate:

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps frontend
```

## What changed after 2026-08-14

The outage was invisible for 10 days because of three separate gaps, all now closed:

- `langfuse`, `langfuse-worker`, `temporal-ui` and `dependencytrack-frontend` had
  **no healthcheck at all** — they read as "Up" no matter what they served.
- `dependencytrack-apiserver`'s healthcheck probed only the **management port**
  (9000 `/health/ready`, which reports datasource reachability). The API port 8080
  was dead while the JVM stayed alive, because the health listener and the Hikari
  pool are non-daemon threads. Docker reported `healthy` throughout. It now probes
  `/api/version` on 8080 as well.
- `monitor-prod.sh` checked **only** `www.simonrowe.dev`, so nothing looked at
  Langfuse, Dependency-Track, Temporal or Portainer.

Auth0 note: Langfuse and Dependency-Track were the two Auth0-backed services that
broke, which made this look like an Auth0 problem. It was not — Auth0 config was
correct throughout, and Temporal's Auth0 SSO (the third Auth0 service) kept working.
Verify the login paths directly:

```bash
curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available   # -> true
curl -s https://langfuse.simonrowe.dev/api/auth/providers | head -c 200 # -> includes "auth0"
curl -s -o /dev/null -w '%{redirect_url}\n' https://temporal.simonrowe.dev/auth/sso
```

## Production scripts reference

| Script | Purpose |
| --- | --- |
| `scripts/start-prod.sh` | Start all production services and wait for health |
| `scripts/stop-prod.sh` | Stop all production services (data volumes preserved) |
| `scripts/restart-prod.sh` | Phased restart; also the script the `deployer` runs — see [deploy.md](deploy.md) |
| `scripts/status-prod.sh` | Health of every service plus external reachability |
| `scripts/monitor-prod.sh` | Single-run watchdog check (designed for cron) |
| `scripts/install-prod-monitoring.sh` | Install the cron job, log file and logrotate config |
| `scripts/enable-memory-cgroup.sh` | Report/apply/revert the kernel memory-cgroup fix |
