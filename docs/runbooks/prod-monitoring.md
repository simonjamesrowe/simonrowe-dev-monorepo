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

Before the layers it checks the disk (see [Disk full](#disk-full-2026-09-28-outage)):
above 85% used on the filesystem holding `/var/lib/docker` it prunes unused images
itself, then carries on. It then runs four layers, cheapest first, and stops after
the first one that acts.

| Layer | Question | Remedy |
| --- | --- | --- |
| 1. Site | Does `https://www.simonrowe.dev` answer? | After 3 consecutive failures, restart `pinggy` (see below), then `docker compose up -d` to reconcile the whole stack |
| 2. Container health | Does every container report `running` + `healthy`? | After 3 consecutive ticks `unhealthy`, restart **that** service. Containers in `created`/`exited` trigger a stack reconcile instead |
| 3. Endpoint | Does each public hostname actually serve? | After 3 consecutive bad responses, restart the single service behind it |
| 4. DNS | Can each internet-facing container resolve an external name while the host can? | After 3 consecutive failures, restart **that** service (see below) |

Backoff: the whole-stack path allows 3 reconciles per 10 minutes; each service
(including `pinggy`) allows 2 restarts per 30 minutes. On exhaustion it logs
`CRIT ... Needs a human.` and stops trying — grep for `CRIT` when something has
been down a while.

### Why layer 2 has to exist

**Docker never restarts an unhealthy container.** `restart: unless-stopped` only
fires when the process *exits*. A container whose healthcheck fails forever is
left running, untouched, indefinitely. Nothing in Docker or Compose closes that
gap, so the cron job is the only thing that does.

### Why layer 4 exists (2026-09-24 DNS outage)

On 2026-09-24 the Wi-Fi dropped, the wifi-watchdog rebooted the Pi at 20:18Z,
and Docker started the stack at 20:20Z **with the link still down** — no DHCP
lease, so the host had no nameservers. Docker's embedded resolver (`127.0.0.11`,
what every container here uses) copies its upstream servers from the host **when
a container starts** and never re-reads them. Twenty containers came up with an
empty upstream list. They resolved compose service names, so every healthcheck
and every public hostname stayed green, and could not resolve anything outside
the stack. The link came back at 06:45Z; layer 1 restarted `pinggy`, `backend`
and `temporal-ui` to bring the site back, those three picked up DNS, and the
other twenty stayed broken for ~23 more hours, until Dependency-Track's "Login
with Auth0" button was noticed missing. Over the same window alloy shipped
nothing to Loki and software-factory could reach neither Loki nor Linear, so
nothing reported any of it.

The tell is the `# ExtServers:` comment Docker writes into a container's
generated resolv.conf — present and listing servers on a healthy container,
absent on a broken one. `scripts/enable-docker-dns.sh --verify` lists every
container without it.

Two guards came out of it:

- **The cause is removed** by pinning `dns` in `/etc/docker/daemon.json`
  (`scripts/enable-docker-dns.sh --apply`). The upstream list becomes a property
  of the daemon rather than a snapshot of the host during boot.
- **Layer 4 is the backstop.** For each service in `DNS_EGRESS_SERVICES` —
  the ones that need the internet: alloy, backend, software-factory, deployer,
  the Dependency-Track apiserver, temporal-ui, langfuse and its worker, searxng,
  trivy-server, pinggy — it runs `getent hosts github.com` inside the container.
  The host is probed first as the control: if the host cannot resolve either,
  that is an outage (wifi-watchdog's problem) and nothing is restarted. Databases,
  Kafka and Elasticsearch are deliberately not probed — they only resolve service
  names, and restarting mongodb over external DNS would take the backend down for
  nothing. Layer 4 keeps its own counter (`svc_<service>.dns-failures`), because
  layer 3 resets a service's counter every time its hostname serves, and a service
  that serves while its outbound DNS is dead is exactly the case this is for. It
  shares the per-service restart budget with the other layers.

Neither of those can raise an alarm off the Pi. That is what the
[production heartbeat](prod-heartbeat.md) is for.

### Why layer 1 restarts `pinggy` specifically (2026-09-06 outage)

All six public hostnames — `www`, `api`, `console`, `langfuse`, `temporal`,
`dependency-track` — go through one path: Cloudflare → the `pinggy` tunnel →
`nginx`. On 2026-09-06 every one of them was dark for about four hours while
`docker compose ps` reported `pinggy: Up (healthy)` and `nginx: Up (healthy)` —
**zero unhealthy containers** — because the fault was inside the tunnel client,
not visible to Docker at all:

- `a.pinggy.io` resolves an IPv6 address (`2a01:7e01::f03c:95ff:fefb:3443`)
  *first*, and this host has no IPv6 default route (`ip -6 route` shows only
  `fe80::/64` link-local; `curl -6` to any IPv6 target fails outright).
- pinggy dialled it anyway, got
  `Error: Tunnel disconnected before establishment: Could not connect: Resource
  temporarily unavailable` (`EAGAIN`) on every attempt, and retried forever —
  645 failed connects and 47,420 `Reconnecting to Pinggy` log lines over the
  four hours.
- A raw TCP connect to the same edge on IPv4 port 443 succeeded immediately. A
  plain `docker restart pinggy` (which forces a fresh DNS resolution, and
  happened to pick IPv4) brought the whole site back with **no other change**.

Two structural reasons this lasted four hours instead of one minute:

1. **The healthcheck cannot see it.** `pinggy`'s healthcheck is
   `test: ["CMD-SHELL", "kill -0 1"]` — it only proves PID 1 exists, which stays
   true throughout an infinite reconnect loop. There is no known replacement
   healthcheck for the `pinggy/pinggy` image verified to work, so this is
   deliberately left as-is; layer 1 is the detector instead.
2. **The existing remediation could not fix it.** Layer 1's only action was
   `docker compose up -d`, which — per its own comment — is "a no-op for
   services that are already healthy". `pinggy` was running and reporting
   healthy, so the reconcile did nothing to it, every single minute, for four
   hours, while layer 3 restarted `frontend`/`backend`/`langfuse` instead (the
   services that own the failing hostnames) until `SERVICE_MAX_RESTARTS`
   backoff gave up — none of which can fix an ingress fault.

The fix has two parts:

- `scripts/monitor-prod.sh` layer 1 now calls `svc_restart "pinggy" ...` (a
  plain `docker compose restart`, honouring pinggy's own per-service backoff)
  **before** the whole-stack reconcile, whenever the site check fails. This is
  additive: the whole-stack `up -d` still runs afterwards, because a
  `created`/`exited` container is a separate, equally real failure mode that
  only `up -d` can fix.
- `docker-compose.prod.yml`'s `pinggy` service sets
  `sysctls: [net.ipv6.conf.all.disable_ipv6=1]`, so the container's resolver
  should only ever see the IPv4 address in the first place. **This is verified
  reasoning, not a fix observed working in production** — the 2026-09-06 outage
  was cleared by a restart before this sysctl existed. If IPv6 ever becomes
  reachable from this host, revisit rather than assume this still holds.

`sysctls:` only applies at container *creation*, not `docker compose restart`,
so the sysctl above lands only when something recreates `pinggy`. The obvious
move is to add `pinggy` to `FACTORY_DEPLOY_RECREATABLE` so a deploy does that —
and it was **deliberately not done**.

`pinggy` is the single point of ingress for every public hostname, and one
`PINGGY_TOKEN` admits one active tunnel. Nobody has established whether a
*recreate* can race its own outgoing session the way a second host holding the
token does; the pre-existing comment on that variable warned that it can, and
this change did not disprove it. If it can, listing `pinggy` there converts a
manual step into an unattended, automated outage of the whole site.

The asymmetry decides it. Leaving `pinggy` off costs one manual
`docker compose -f docker-compose.prod.yml up -d pinggy` after the deploy that
ships this — which is needed **regardless**, because the allowlist reaches
`sync-config` as an environment variable on the *running* deployer and so would
not have covered its own deploy either way. Adding it risks the site for no
saving on this change.

To settle it properly: recreate `pinggy` by hand while watching
`docker logs -f` for `A tunnel with the same token is already active`. If a
clean recreate reconnects, add `pinggy` to the allowlist and cite that
observation in the comment.

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
| `DNS_PROBE_NAME` | `github.com` | External name layer 4 resolves, on the host and in each container |
| `DNS_PROBE_TIMEOUT` | `5` | Seconds allowed per layer-4 probe |
| `DISK_CHECK_PATH` | `/var/lib/docker` | The disk check watches the filesystem holding this path (`/` if it does not exist) |
| `DISK_WARN_PERCENT` | `85` | At or above: log `WARN`, prune unused images built more than `IMAGE_PRUNE_KEEP` ago |
| `DISK_CRIT_PERCENT` | `95` | At or above: log `CRIT`, prune **every** unused image |
| `DISK_PRUNE_INTERVAL` | `3600` | At most one prune per level per this many seconds |
| `DISK_PRUNE_DEPLOY_GRACE` | `3600` | No prune within this many seconds of a deploy's `pull` |
| `IMAGE_PRUNE_KEEP` | `72h` | The `until` window of the ordinary prune (shared with `restart-prod.sh`) |
| `IMAGE_PRUNE_TIMEOUT` | `600` | Seconds a single prune may run |

Per-service remediation is deliberately less trigger-happy than the whole-stack
path: a container restart is cheap, but a restart *loop* is worse than one bad
service.

### State files

In `STATE_DIR`:

- `failure_count` — consecutive layer-1 failures, reset on success or reconcile
- `restart_timestamps` — epoch times of recent whole-stack reconciles, pruned each run
- `svc_<service>.failures` / `.dns-failures` / `.restarts` — per-service failure counters (layers 2-3 and layer 4) and restart history
- `disk_prune_warn.last` / `disk_prune_crit.last` — epoch time of the last disk-check prune at each level (the rate limit)

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

## Disk full (2026-09-28 outage)

### What it looks like

On 2026-09-28, between about 19:50 and 20:08 UTC, `/dev/sda2` (the Pi's 117G
root filesystem, which holds `/var/lib/docker`) reached 100%. The API was down
for about six hours. In the order it happened:

- **Kafka** died with `SIGBUS`. The JVM mmaps its files, and on a full disk a page
  fault on an mmapped write kills the process.
- **Postgres** (`langfuse-db`) failed crash recovery with
  `No space left on device`. Langfuse and Dependency-Track went with it.
- **Mongo** hit a fatal error.
- **Docker's own restart attempts then failed** with
  `failed to set up container networking: write /var/lib/docker/...`, so those
  three containers stayed `exited`. `restart: unless-stopped` does not try again
  once the daemon itself has failed to restart a container.
- **The backend** failed on every restart with exit code 82 and
  `pkcs12: error reading P12 data: asn1: syntax error: data truncated`. The
  buildpack launcher had been writing
  `/layers/paketo-buildpacks_bellsoft-liberica/jre/lib/security/cacerts` in the
  container's writable layer when the disk filled, so that file was truncated. A
  restart reuses the same writable layer. Only recreating the container fixes it.

`docker system df` showed **217 images, 25 in use, 65.79GB, 45.25GB
reclaimable**. Every deploy pulls new backend, frontend and software-factory
images (six deploys on 2026-09-26 alone), and nothing ever deleted the old ones.
No deploy ran on 09-27 or 09-28. The dead images had used up the headroom, and
ordinary growth filled the rest. `docker image prune -af` reclaimed 49.64GB
(100% to 67%).

### What now stops it

- **Every successful deploy prunes.** `restart-prod.sh` runs
  `docker image prune -af --filter until=72h` at the end of `verify-public` (and
  at the end of a verified bare `all`). It never prunes on a failure or after a
  rollback, and a prune failure never fails the deploy. See
  [deploy.md](deploy.md#image-cleanup).
- **The watchdog prunes when the disk is high.** Above `DISK_WARN_PERCENT` (85%)
  it runs the same prune, at most once an hour. Above `DISK_CRIT_PERCENT` (95%) it
  runs `docker image prune -af` with no window, also at most once an hour. At that
  point disk space matters more than keeping a local image for a manual rollback,
  and a rollback can still pull the image's sha tag from ghcr. It stands down during a deploy (the
  maintenance flag) and for an hour after a deploy's `pull`, because the
  pre-deploy image a rollback re-tags is unused from `recreate` onwards. It runs
  before layer 1 and before anything else writes a file, so it still works on a
  disk that is completely full.
- **`until` means built, not pulled.** The window keeps images *built* in the
  last 72h. The backend image used to carry Spring Boot's fixed 1980-01-01
  creation date, which made every unused backend image look ancient. It is now
  stamped with the build time (`createdDate` in `backend/build.gradle.kts`).
  Backend images pulled before that change still say 1980, so they are removed
  as soon as nothing uses them.

It only ever removes **images no container uses**. It never removes containers,
volumes or networks. A stopped container keeps its image, so the `exited`
datastores below are never pruned.

If the watchdog logs `CRIT disk: still NN% used after the prune`, images are not
what is filling the disk. Run `docker system df -v`. The next-largest thing that
grows without bound is the **`langfuse-clickhouse-data` volume (17.68GB on
2026-09-28)**. Langfuse traces have no TTL. It is not fixed here.

### Recovery, in this order

Verified on 2026-09-28. Run these from the deploy directory.

1. **Free space.** `docker image prune -af`, then `df -h /` to confirm there is
   room. (The watchdog does this itself above 95% when it can run. Do it by hand
   if cron was not running, or if you got there first.)
2. **Start the datastores Docker gave up on.** They are `exited`, and nothing
   retries them:
   ```bash
   docker start simonrowe-dev-monorepo-mongodb-1 \
     simonrowe-dev-monorepo-langfuse-db-1 simonrowe-dev-monorepo-kafka-1
   ```
   (The watchdog's layer 2 also runs `up -d` on the next tick once there is space,
   and that starts `exited` containers too.)
3. **Wait until they are healthy.** Check
   `docker compose -f docker-compose.prod.yml ps mongodb langfuse-db kafka`. Kafka's
   `start_period` is 180s.
4. **Restart the backend:** `docker restart simonrowe-dev-monorepo-backend-1`. Its
   Kafka consumers do not recover from a long broker outage on their own (see the
   Kafka bullet in `CLAUDE.md`).
5. **If the backend exits 82** and its log shows
   `pkcs12: error reading P12 data: asn1: syntax error: data truncated`, its
   writable layer is corrupt, and restarting will not help. Recreate it:
   ```bash
   docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps backend
   ```
   Any other container whose logs show a truncated file after a disk-full needs
   the same fix.
6. **Curl the public hostnames.** Do not rely on a green `docker compose ps`.

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
| `scripts/lib/image-prune.sh` | The unused-image prune shared by `restart-prod.sh` (after a deploy) and `monitor-prod.sh` (disk check); sourced, not run |
| `scripts/install-prod-monitoring.sh` | Install the cron job, log file and logrotate config |
| `scripts/enable-memory-cgroup.sh` | Report/apply/revert the kernel memory-cgroup fix |
| `scripts/enable-docker-dns.sh` | Report/apply/revert pinned upstream DNS for containers; `--verify` lists containers with none |
| `scripts/prod-heartbeat.sh` | Off-Pi check run by the `Production heartbeat` workflow — see [prod-heartbeat.md](prod-heartbeat.md) |
