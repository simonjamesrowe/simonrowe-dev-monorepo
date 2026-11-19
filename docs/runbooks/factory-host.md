# Runbook: the factory host (the NUC)

The software factory runs on its own machine, an Intel NUC (i5 8th gen, x86_64,
8 GB RAM, 120 GB SSD, Ubuntu, on Wi-Fi). The Raspberry Pi keeps the site and
everything the site depends on. This runbook covers what runs on the NUC, how the
two hosts talk, how to install the NUC from scratch, and what breaks when it is down.

## What runs on the NUC, and why

`docker-compose.factory.yml`, compose project **`simonrowe-factory`**, checked out
at `DEPLOY_DIR` (default `/srv/simonrowe-dev-monorepo`). Containers are named
`simonrowe-factory-<service>-1`.

| Service | What it does |
|---|---|
| `mongodb` | The factory's own Mongo 8, database `software_factory`, volume `factory-mongo-data`. Not published. |
| `software-factory` | The GitHub webhook receiver and the Temporal workers (code review, ticket agent, CVE fix, log watch, feedback, writing, page reads). Published on `FACTORY_BIND_ADDRESS:8090`. |
| `page-reader` | Headless Chromium for bot-challenged article pages. On network `page-reader`, fixed subnet `172.30.250.0/24`. |
| `deployer` | Deploys this host: workflow `deploy-factory`, queue `deploy-factory`. Holds the NUC's Docker socket. Never raises a maintenance page. |
| `deploy-state-init` | One-shot: chowns the `deploy-state` volume to 10003. `exited 0` is its normal state. |
| `alloy` | Ships the NUC's container logs to Grafana Cloud Loki (`config/alloy/factory.alloy`). |

Why it moved: the factory is the part of the stack that builds nothing, runs Claude
Code, and spikes CPU and memory during reviews. On the Pi it competed with the site's
JVMs, Elasticsearch, Kafka and ClickHouse for 16 GB of RAM and one SSD. Moving it
off takes that load away from the site, and the site keeps no hard dependency on it
(see "The NUC is down" below).

What stays on the Pi: the site, Temporal (the factory's workflows still run
against it), Langfuse, Dependency-Track, and the Pi's own `deployer` (workflow
`deploy-prod`, queue `deploy`, plus the platform backup).

## The network between the two

Two links, both on the LAN, both TCP, both restricted to the other host by
`DOCKER-USER` rules:

```
                    LAN (Wi-Fi on both)
   Pi (SITE_HOST)                                  NUC (FACTORY_HOST)
   ------------------------------------            -----------------------------------
   temporal  :7233  <-------------------------------  software-factory, deployer,
     published on TEMPORAL_BIND_ADDRESS               page-reader (TEMPORAL_ADDRESS)
     accepted only from FACTORY_HOST

   nginx /webhooks/github                           software-factory :8090
   backend (page reads, writing jobs,   ---------->   published on FACTORY_BIND_ADDRESS
     console, /status)                                accepted only from SITE_HOST
       via factory-gateway (socat) -> FACTORY_HOST:8090
```

- **Pi to NUC, tcp 8090.** GitHub's webhook arrives at the Pi (Cloudflare, pinggy,
  nginx) and nginx proxies `POST /webhooks/github` to `factory-gateway:8090`, a
  `socat` container on the Pi that forwards to `FACTORY_HOST:8090`. The backend's
  `PLATFORM_FACTORY_URL` points at the same gateway.
- **NUC to Pi, tcp 7233.** Every Temporal client on the NUC uses
  `TEMPORAL_ADDRESS=<pi-ip>:7233`. Temporal has no authentication, so the firewall
  is the only thing keeping other LAN devices off it.
- Everything else the NUC talks to is on the internet (GitHub, ghcr, Linear,
  Anthropic, Grafana Cloud, Dependency-Track's public hostname).

## First-time install

On the NUC, as the login user that will own the checkout (it uses `sudo` itself):

1. Install Ubuntu (x86_64), join the Wi-Fi, and give the NUC a **DHCP reservation**
   on the router. The Pi's `FACTORY_HOST` and the firewall's peer are fixed
   addresses; a NUC that changes address silently loses every webhook.
2. Get the installer onto the machine. The simplest way is to clone the repo where
   it will live:
   ```bash
   sudo mkdir -p /srv/simonrowe-dev-monorepo && sudo chown "$USER": /srv/simonrowe-dev-monorepo
   git clone https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git /srv/simonrowe-dev-monorepo
   ```
   (The installer does this itself if the directory is missing, but it has to be
   fetched from somewhere first.)
3. Create `/srv/simonrowe-dev-monorepo/.env` (see the next section), mode `0640`.
   Copy the GitHub App private key to the path `GITHUB_APP_PRIVATE_KEY_HOST_PATH`
   names.
4. Dry run, read the plan, then apply:
   ```bash
   cd /srv/simonrowe-dev-monorepo
   scripts/install-factory-host.sh            # prints the plan, changes nothing
   scripts/install-factory-host.sh --apply
   ```
   It is idempotent: re-run it after any change, or to repair drift. It installs
   Docker Engine and the compose plugin from Docker's apt repo (if missing), adds
   you to the `docker` group, configures `/etc/docker/daemon.json` (log rotation
   and pinned DNS, using the same `enable-docker-log-rotation.sh` and
   `enable-docker-dns.sh` the Pi uses), turns Wi-Fi power saving off, installs the
   cron jobs and the firewall, and verifies **last**. The verify step exits
   non-zero if anything critical is missing and leaves the steps that succeeded
   installed; fix what it names and re-run.
5. On the **Pi**, install the matching firewall (see below) and set
   `FACTORY_HOST` and `TEMPORAL_BIND_ADDRESS` in its `.env`.
6. Start the stack on the NUC. The installer deliberately does not: Mongo has to be
   restored first (see the cutover section).
   ```bash
   docker compose -f docker-compose.factory.yml up -d
   ```
7. Check pollers, not just health. A `healthy` software-factory that never connected
   to Temporal accepts webhooks with `202` and reviews nothing. From the Pi:
   ```bash
   for q in code-review deploy-factory; do
     docker run --rm --network simonrowe-dev-monorepo_default temporalio/admin-tools:1.31.2 \
       temporal task-queue describe --address temporal:7233 --task-queue "$q"
   done
   ```
   Each needs a poller whose identity names the NUC's container.

## The .env

`$DEPLOY_DIR/.env`, mode `0640`, group `DEPLOY_ENV_GID` (the deployer reads it
through that group). Names only here; values come from the shared env file. The
installer refuses to continue without the required names and never prints a value
other than `SITE_HOST`.

Required (the installer refuses without them; the compose file's `:?` names
are among them):

- `SITE_HOST`: the Pi's LAN IP (the firewall's peer)
- `FACTORY_BIND_ADDRESS`: the NUC's LAN IP (software-factory's published port binds here)
- `TEMPORAL_ADDRESS`: `<pi-ip>:7233`
- `DEPLOY_DIR`: this checkout
- `DOCKER_GID`: the gid of the NUC's `docker` group (the installer checks it matches)
- `GITHUB_WEBHOOK_SECRET`
- `FACTORY_TRIGGER_TOKEN`
- `GITHUB_APP_CLIENT_ID`
- `GITHUB_APP_PRIVATE_KEY_HOST_PATH`
- `CLAUDE_CODE_OAUTH_TOKEN`

Recommended (the stack starts without them, but a module stays inert):

- `DEPLOY_ENV_GID`
- `FACTORY_READ_TOKEN`
- `LINEAR_API_KEY`, `FACTORY_LINEAR_TEAM_KEY`
- `DEPENDENCYTRACK_API_KEY`
- `GRAFANA_CLOUD_LOKI_ENDPOINT`, `GRAFANA_CLOUD_LOKI_USER`, `GRAFANA_CLOUD_API_KEY`

The Pi's `.env` gains `FACTORY_HOST` (the NUC's LAN IP) and
`TEMPORAL_BIND_ADDRESS` (the Pi's LAN IP).

## The firewall

A port Docker publishes is DNAT'd and forwarded to the container; it never passes
INPUT, so ufw cannot filter it. `scripts/enable-factory-host-firewall.sh` manages
the `DOCKER-USER` chain instead, through its own chain `FACTORY-HOST`, jumped to
from `DOCKER-USER` exactly once.

```bash
# on the NUC (the installer runs this for you)
scripts/enable-factory-host-firewall.sh --role factory --peer <pi-ip>            # dry run
scripts/enable-factory-host-firewall.sh --role factory --peer <pi-ip> --apply
# on the Pi
scripts/enable-factory-host-firewall.sh --role site --peer <nuc-ip> --apply
# either host, any time
scripts/enable-factory-host-firewall.sh --role <role> --peer <ip> --verify
```

| Role | Rules |
|---|---|
| `site` (Pi) | tcp 7233 from the peer only |
| `factory` (NUC) | tcp 8090 from the peer only; `172.30.250.0/24` (page-reader) may reach `<peer>:7233` and the internet, and is dropped to 10/8, 172.16/12, 192.168/16, 169.254/16 and 100.64/10 |

Things worth knowing:

- **The port rules match DNAT'd connections by their original destination port**
  (`--ctstate DNAT --ctorigdstport`), not the bare port. On the Pi, every Temporal
  client in the stack talks to `temporal:7233` over the compose bridge, and bridged
  packets pass `DOCKER-USER` too. A bare `--dport 7233` rule would cut them all off.
- The first rule returns RELATED/ESTABLISHED traffic, so replies are never dropped.
- The rules are installed as `/usr/local/sbin/factory-host-firewall-rules.sh`, run by
  `factory-host-firewall.service` (oneshot, `After=docker.service`,
  `PartOf=docker.service` so a Docker restart re-applies them, enabled for boot).
  Re-running `--apply` replaces the rules; it never duplicates them. There is no
  `ExecStop`: stopping the unit leaves the rules in place.
- `--apply` ends with a verify that exits non-zero if any rule, the single jump, or
  the enabled unit is missing.
- page-reader's DNS queries leave from its own network, so the daemon's upstream
  servers must be public: a router resolver (`192.168.x.1`) is dropped. That is one
  reason the installer pins `1.1.1.1`/`8.8.8.8` in `daemon.json`.
- Not covered: IPv6 (both published ports bind an IPv4 address), and page-reader
  reaching the NUC's own host services on its network gateway (INPUT, not FORWARD;
  its in-container egress proxy refuses non-public addresses).

## Monitoring

`scripts/monitor-prod.sh` runs every minute from cron, as on the Pi, in the
**factory role**:

```
* * * * * MONITOR_ROLE=factory COMPOSE_FILE=$DEPLOY_DIR/docker-compose.factory.yml COMPOSE_PROJECT=simonrowe-factory $DEPLOY_DIR/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
```

Installed through the Pi's own `scripts/install-prod-monitoring.sh` (with
`MONITOR_ENV`), so log location and logrotate match the Pi's.

What the factory role does and does not do:

| Layer | Pi (`site`) | NUC (`factory`) |
|---|---|---|
| Deploy stand-down (maintenance flag) | read through nginx | read through the deployer; inert today, since the NUC's deploy raises no flag |
| Disk: prune unused images at 85%/95% | yes | yes |
| Prune grace after a deploy's pull | `rollback-images` mtime through nginx | the same file through the deployer; if it cannot be read, says why and prunes |
| 1. www check, pinggy restart | yes | **skipped**: no site, no pinggy |
| 2. restart `unhealthy`, reconcile `created`/`exited` | yes | yes; the only one-shot is `deploy-state-init` |
| 3. public hostnames | yes | **skipped**: none |
| 4. DNS egress | 11 services | `alloy`, `software-factory`, `deployer` |

Test any change to the watchdog with `DRY_RUN=1` and a throwaway `STATE_DIR`:
every remediation path runs `docker compose`.

Logs: the NUC's `alloy` ships its container logs to the same Grafana Cloud Loki;
query by `container`. The watchdog's own log is on the NUC only.

## Backups

`scripts/backup-factory-mongo.sh` runs nightly at 02:30:
`mongodump --archive --gzip --db software_factory` through `docker exec` into the
`mongodb` container (found by its compose labels), written to
`/var/backups/software-factory/software_factory-<UTC stamp>.archive.gz`. It keeps the
newest 7, refuses an empty or corrupt archive, never rotates on a failed dump, and
exits non-zero on any failure. Log: `/var/log/prod-health/factory-mongo-backup.log`.

**Scope:** this protects against a bad change or a corrupted database. It does not
protect against losing the NUC: the archives are on the same SSD. There is no
off-box copy yet. Most of what the factory stores can be re-derived (run records,
review learnings; Linear is the record for tickets), which makes the gap tolerable,
not absent.

Restore one:

```bash
docker exec -i simonrowe-factory-mongodb-1 \
  mongorestore --archive --gzip --drop --nsInclude='software_factory.*' \
  < /var/backups/software-factory/software_factory-<stamp>.archive.gz
```

Stop `software-factory` and `deployer` first so nothing writes during the restore.

## The NUC is down: what breaks

The site does not depend on the factory, so **the site itself is unaffected**:
www, api, Term Time, CoParent, Langfuse, Dependency-Track and Temporal all keep
serving from the Pi.

What stops:

- **GitHub webhooks get a 502** from the Pi's nginx (factory-gateway cannot reach
  `FACTORY_HOST:8090`). So: **no code reviews** (the required `Code Review` check
  never appears, which blocks merging except through the admin bypass), and **no
  deploys** to either host, because the deploy is started from the same webhook.
  GitHub keeps failed deliveries; redeliver them from the App's settings once the
  NUC is back, or trigger a review or deploy from the admin console.
- **Page reads fall back to Jina Reader**, then to the stored feed blurb. Articles
  behind a Cloudflare challenge (openai.com) get the blurb only.
- **Writing jobs fall back to OpenAI** (release summaries, newsletter summaries):
  the backend's writer treats an unreachable factory as a failure and writes with
  OpenAI in the same tick.
- The admin Software Factory console and `/status` cannot reach software-factory
  or the NUC's deployer, and say so.
- Scheduled factory work (log watch, CVE scan, static analysis, ticket intake)
  waits. Temporal on the Pi still fires the schedules; a run started while nothing
  polls its queue sits there until the NUC's workers return, or until the 22h
  execution timeout ends it.

## Wi-Fi caveat

The NUC is on Wi-Fi, like the Pi, and the Pi lost 23.5 hours to an access point
outage it never recovered from on its own (`docs/runbooks/wifi-resilience.md`).
What the installer does for this host:

- Wi-Fi power saving off, via `/etc/NetworkManager/conf.d/zz-factory-wifi-powersave-off.conf`
  (`wifi.powersave = 2`). Named `zz-` so it sorts after Ubuntu's own
  `default-wifi-powersave-on.conf`. Takes effect on the next connection or reboot;
  the installer does not reconnect, since that would drop an SSH session. If the
  NUC's Wi-Fi is managed by netplan/networkd rather than NetworkManager (Ubuntu
  Server's default), the installer says so and changes nothing: use
  `iw dev <wlan> set power_save off` from a systemd unit instead.
- DNS pinned in `daemon.json`, so a boot with the Wi-Fi down does not strand every
  container without upstream servers.

What it does not do: the Pi's `wifi-watchdog.sh` (reconnect ladder up to a
rate-limited reboot) is not installed on the NUC. It defaults to `wlan0` and was
written against the Pi's NetworkManager setup; check its device name and profile
handling before using it here. Until then, a NUC that does not rejoin the network
needs a human, and every link above (webhooks, Temporal) goes with it. A wired
connection, if one is ever possible, removes the whole class of problem.

## Cutover from the Pi

The software factory moves in one maintenance window. The site stays up throughout. Webhooks
that arrive while the factory is down are lost, so re-trigger any review from
`/admin/software-factory` afterwards. Read the whole section first: the order matters.

### What merging the split does on its own (nothing, by design)

The pull request that introduces this file also takes `software-factory` and `page-reader` out
of `docker-compose.prod.yml` and adds `factory-gateway`. Its own deploy is **held back**
(`DEPLOYED_IMAGES_ONLY`). The recreate allowlist is read from the *running* deployer, which
predates `factory-gateway` and does not list `temporal`, so `sync-config` declines and leaves
the Pi's checkout where it was (see "A decline does not clear itself" in
[deploy.md](deploy.md)). The Pi keeps running the factory exactly as before until the steps
below, so merging is safe at any time. The NUC can be prepared before or after.

### 0. Prepare the NUC (no production impact)

Do "First-time install" steps 1-4 above. In the NUC's `.env`, besides the variables listed
there, copy from the Pi's `.env` every variable `software-factory` reads, including
`FACTORY_DEPLOY_TRIGGER_ENABLED=true`. Do **not** start the stack yet: two factories polling
one Temporal is exactly the version-skew wedge from 2026-09-15.

### 1. Quiesce (Pi)

- On `/admin/software-factory`, pause the schedules and let running reviews, ticket-agent runs
  and deploys finish. Temporal keeps the history either way, but an activity mid-flight on the
  Pi when its worker stops is retried later on the NUC, from scratch.
- Note the commit the Pi runs (`/status`).

### 2. Stop the Pi's factory and take its data (Pi)

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
docker compose -f docker-compose.prod.yml stop software-factory page-reader
docker exec simonrowe-dev-monorepo-mongodb-1 \
  mongodump --archive --gzip --db software_factory > /tmp/software_factory.archive.gz
ls -l /tmp/software_factory.archive.gz    # must not be empty
```

Copy the archive to the NUC (for example `scp` from the NUC, or Raspberry Pi Connect's file
transfer). This moves the factory's own database, not site data: the "restore through Data Ops"
rule is about the site's `simonrowe` database, which this does not touch.

### 3. Bring the Pi's checkout up to date (Pi)

Fast-forward the held-back checkout as in "Recovering a wedged checkout" in
[deploy.md](deploy.md), running `git` inside the deployer (the checkout's ownership is mixed).
Then set in the Pi's `.env`:

```
FACTORY_HOST=<the NUC's reserved address>
TEMPORAL_BIND_ADDRESS=<the Pi's LAN address>
```

and install the Pi's half of the firewall **before** Temporal is published on the LAN:

```bash
sudo scripts/enable-factory-host-firewall.sh --role site --peer <the NUC's address> --apply
```

### 4. Recreate what changed on the Pi (Pi)

```bash
f=docker-compose.prod.yml
docker compose -f $f up -d --no-deps temporal          # now published on the LAN address
docker compose -f $f up -d --no-deps factory-gateway backend
docker compose -f $f up -d --force-recreate --no-deps nginx   # single-file mount: restart is not enough
docker rm -f simonrowe-dev-monorepo-software-factory-1 simonrowe-dev-monorepo-page-reader-1
docker compose -f $f pull deployer && docker compose -f $f up -d --no-deps deployer
```

The last line matters more than it looks. **The Pi's deploys no longer pull the
software-factory image** (it is not one of the site's services any more), so `:latest` on the
Pi only moves when it is pulled by hand. `up -d` alone recreates the deployer on the image it
already has.

The backend's factory calls fail (page reads fall back to Jina, writing jobs to OpenAI) and
webhooks return 502 until step 5 is done. That is expected.

### 5. Start the factory on the NUC (NUC)

```bash
cd /srv/simonrowe-dev-monorepo && git pull --ff-only
docker compose -f docker-compose.factory.yml up -d mongodb
docker exec -i simonrowe-factory-mongodb-1 \
  mongorestore --archive --gzip --drop --nsInclude 'software_factory.*' < software_factory.archive.gz
docker compose -f docker-compose.factory.yml up -d
```

### 6. Verify: pollers, not health

From the Pi, every queue must have a poller, and the factory's must name the NUC's containers:

```bash
for q in code-review review-feedback cve-fix logwatch static-analysis content-writing \
         ticket-agent page-read page-reader linear deploy-factory deploy platform-backup; do
  echo "== $q"
  docker run --rm --network simonrowe-dev-monorepo_default temporalio/admin-tools:1.31.2 \
    temporal task-queue describe --address temporal:7233 --task-queue "$q"
done
```

`deploy` and `platform-backup` must be polled by the Pi's deployer only. Any queue polled from
both hosts means a factory container is still running on the Pi.

Then the routes:

- `curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.simonrowe.dev/webhooks/github`
  should print `401` (an unsigned delivery is refused), not `502`.
- `/admin/software-factory` loads its modules, and `/status` shows software-factory's commit.
- Re-trigger one code review from the console and watch it complete.

Then resume the schedules.

### 7. Afterwards

- The first merge after cutover proves the two deploys. A docs-only merge runs both workflows
  to `SKIPPED`; a `software-factory/` merge deploys only `deploy-factory`, with the site left up.
- The Pi's `software_factory` database is now written only by its own deployer (`deploy-prod`
  runs and platform backups). The NUC's copy holds everything else. Nothing needs merging back.

### Going back

On the Pi: restore the two services from the commit before the split and point nginx back at
them, then stop the NUC's stack. **Never run both at once.**

```bash
git -C <deploy dir> checkout <pre-split commit> -- docker-compose.prod.yml config/nginx/nginx-proxy.conf
docker compose -f docker-compose.prod.yml up -d software-factory page-reader
docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps nginx
```

Data written on the NUC since cutover stays there: dump it back the same way if it matters.
