# Production Health Monitoring

The production environment uses a cron-based health monitor that checks whether
`simonrowe.dev` is reachable and automatically restarts the Pinggy tunnel
container when it isn't.

## How it works

`scripts/monitor-prod.sh` is a single-run script designed to be called by cron.
Each invocation:

1. Checks if `https://simonrowe.dev` is reachable (10s timeout)
2. Tracks consecutive failures in a state file (`/tmp/prod-health/failure_count`)
3. After 3 consecutive failures, restarts the Pinggy container
4. Limits restarts to 3 per 10-minute window to prevent restart storms

The Docker Compose file also includes a process-level health check on the Pinggy
container (`kill -0 1`) so `docker ps` and `scripts/status-prod.sh` can report
its status.

## Setting up the cron job on Raspberry Pi

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
* * * * * /home/simonrowe/simonrowe-dev-monorepo/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
```

Adjust the path to match where the repository is cloned on your Raspberry Pi.

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
| `CHECK_URL` | `https://simonrowe.dev` | URL to check for reachability |
| `FAILURE_THRESHOLD` | `3` | Consecutive failures before restart |
| `MAX_RESTARTS` | `3` | Maximum restarts per backoff window |
| `BACKOFF_WINDOW` | `600` | Backoff window in seconds (10 minutes) |
| `STATE_DIR` | `/tmp/prod-health` | Directory for state files |

Example cron entry with custom settings:

```cron
* * * * * FAILURE_THRESHOLD=2 MAX_RESTARTS=5 /home/simonrowe/simonrowe-dev-monorepo/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
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

The site has been unreachable for an extended period and Pinggy restarts aren't
helping. Check:

- Host internet connectivity: `curl -I https://google.com`
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
