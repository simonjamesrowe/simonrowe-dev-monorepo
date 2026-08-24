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
