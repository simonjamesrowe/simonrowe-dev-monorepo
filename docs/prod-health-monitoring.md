# Production Health Monitoring

The production environment uses a cron-based health monitor that checks whether
`www.simonrowe.dev` is reachable and automatically reconciles the Docker Compose
stack when it isn't.

## How it works

`scripts/monitor-prod.sh` is a single-run script designed to be called by cron.
Each invocation:

1. Checks if `https://www.simonrowe.dev` is reachable (10s timeout)
2. Tracks consecutive failures in a state file (`/tmp/prod-health/failure_count`)
3. After 3 consecutive failures, runs `docker compose -f docker-compose.prod.yml up -d`
   to reconcile the stack — this starts any container that is stopped or stuck in
   `Created` (e.g. after an interrupted `docker compose up`), in dependency order,
   and is a no-op for services that are already healthy
4. Restarts `nginx` so it re-resolves the frontend/backend/portainer/langfuse
   hostnames (see the stale-DNS gotcha below) — safe at this point since step 3
   guarantees all upstreams are up
5. Limits restarts to 3 per 10-minute window to prevent restart storms

> **Why `www.simonrowe.dev` and not the bare domain?** `https://simonrowe.dev`
> 301-redirects to `www.simonrowe.dev` via a Cloudflare edge rule, answered before
> the request ever reaches origin. `curl -f` treats a 3xx response as success, so
> checking the bare domain reports "healthy" even when nginx/frontend/backend/pinggy
> are all down — this blind spot let a real outage run undetected. Always check a
> URL Cloudflare can only satisfy by proxying to origin.

> **Stale upstream DNS after `restart-prod.sh` / image updates — fixed in commit
> `62d26cc`, kept here as history.** nginx used to have no `resolver` directive, so
> it resolved `frontend`/`backend`/`portainer`/`langfuse` once at container startup
> and cached those IPs for its whole lifetime. `docker compose up -d` only recreates
> containers whose image/config actually changed, so pulling a new `backend`/
> `frontend` image gave that container a fresh IP while nginx (unchanged) kept
> running against the old, now-dead address — producing `502`/connection-refused
> errors while every container reported healthy. `config/nginx/nginx-proxy.conf` now
> sets `resolver 127.0.0.11 valid=10s ipv6=off;` and uses a variable in every
> `proxy_pass`, so nginx re-resolves each upstream at request time (within the 10s
> TTL) and picks up new container IPs on its own. Two consequences:
>
> - The `nginx` restart that `scripts/restart-prod.sh` and `scripts/monitor-prod.sh`
>   do after `up -d` is now belt-and-braces rather than load-bearing.
> - **The old rule that all four upstreams had to be running before restarting nginx
>   no longer applies.** nginx boots regardless of what is down and 502s only the
>   affected hostname. Its own healthcheck hits `/healthz` in a `default_server`
>   block that proxies to nothing, so nginx health no longer depends on the frontend
>   (which previously kept `pinggy` from starting and took every hostname offline).
>   See `CLAUDE.md`, "nginx resolves upstreams at runtime, not just at boot".

The Docker Compose file also includes a process-level health check on the Pinggy
container (`kill -0 1`) so `docker ps` and `scripts/status-prod.sh` can report
its status.

## Setting up the cron job on Raspberry Pi

The quickest path is to run the installer script from the repo root:

```bash
./scripts/install-prod-monitoring.sh
```

This will:

- enable and start `cron`
- create `/var/log/prod-health/monitor.log`
- install `/etc/logrotate.d/prod-health`
- register the cron job with the correct absolute repo path for the current machine

The remaining steps below describe the manual setup.

### 1. Verify the scripts work

```bash
# Start production
./scripts/start-prod.sh

# Run a manual health check
./scripts/monitor-prod.sh

# Check status
./scripts/status-prod.sh
```

### 2. Create a log directory

```bash
sudo mkdir -p /var/log/prod-health
sudo chown "$USER:$USER" /var/log/prod-health
```

### 3. Add the cron job

Open the crontab editor:

```bash
crontab -e
```

Add the following line to run the health check every minute:

```cron
* * * * * /absolute/path/to/repo/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
```

Save and exit. Verify the cron job is registered:

```bash
crontab -l
```

### 4. Verify cron is running

```bash
# Check cron service status
systemctl status cron

# If not running, enable and start it
sudo systemctl enable cron
sudo systemctl start cron
```

### 5. Set up log rotation (optional but recommended)

Create `/etc/logrotate.d/prod-health`:

```
/var/log/prod-health/monitor.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
}
```

## Configuration

The monitor script accepts these environment variables. To use them with cron,
set them inline in the crontab entry:

| Variable | Default | Description |
| -------- | ------- | ----------- |
| `CHECK_URL` | `https://www.simonrowe.dev` | URL to check for reachability |
| `FAILURE_THRESHOLD` | `3` | Consecutive failures before restart |
| `MAX_RESTARTS` | `3` | Maximum restarts per backoff window |
| `BACKOFF_WINDOW` | `600` | Backoff window in seconds (10 minutes) |
| `STATE_DIR` | `/tmp/prod-health` | Directory for state files |

Example cron entry with custom settings:

```cron
* * * * * FAILURE_THRESHOLD=2 MAX_RESTARTS=5 /absolute/path/to/repo/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
```

## State files

The monitor stores state between cron invocations in `STATE_DIR` (default
`/tmp/prod-health/`):

- `failure_count` — current consecutive failure count (reset on success or restart)
- `restart_timestamps` — epoch timestamps of recent restarts (pruned each run)

These files survive between cron runs but are cleared on reboot (since they live
in `/tmp`). After a reboot the failure counter resets to zero, which is the
desired behaviour since production will need to be started fresh anyway.

## Troubleshooting

### Monitor logs show repeated CRIT messages

The site has been unreachable for an extended period and stack reconciliation
isn't helping. Check:

- Host internet connectivity: `curl -I https://google.com`
- Container states: `docker compose -f docker-compose.prod.yml ps -a` — look for
  anything stuck in `Created` or `Restarting`, and check its logs
- Pinggy token validity: `docker compose -f docker-compose.prod.yml logs pinggy`
- Pinggy service status: check [status.pinggy.io](https://status.pinggy.io)

### Cron job not running

```bash
# Check cron is active
systemctl status cron

# Check syslog for cron execution
grep CRON /var/log/syslog | tail -20

# Verify the script is executable
ls -la scripts/monitor-prod.sh
```

### State files accumulating

State files in `/tmp/prod-health/` are automatically pruned each run. Old
restart timestamps outside the backoff window are removed. If you want to reset
state manually:

```bash
rm -rf /tmp/prod-health
```

## Production scripts reference

| Script | Purpose |
| ------ | ------- |
| `scripts/start-prod.sh` | Start all production services and wait for health |
| `scripts/stop-prod.sh` | Stop all production services (preserves data volumes) |
| `scripts/status-prod.sh` | Show health of all services + external reachability |
| `scripts/monitor-prod.sh` | Single-run health check (designed for cron) |
| `scripts/install-prod-monitoring.sh` | Install cron, log file, and logrotate config for the monitor |
