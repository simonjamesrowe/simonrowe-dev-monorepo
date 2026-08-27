# Quickstart: Production Health Monitoring & Lifecycle Scripts

## Prerequisites

- Raspberry Pi with Docker and Docker Compose installed
- `.env` file configured at project root (see `.env.example`)
- `curl` available on host (standard on Debian/Ubuntu)

## Quick Start

### Start production

```bash
./scripts/start-prod.sh
```

Starts all 9 services and waits for health checks to pass. Reports final status.

### Check status

```bash
./scripts/status-prod.sh
```

Shows health of each service plus external reachability of simonrowe.dev.

### Set up the health monitor cron job

```bash
crontab -e
```

Add:

```cron
* * * * * /path/to/scripts/monitor-prod.sh >> /var/log/prod-health/monitor.log 2>&1
```

See `docs/runbooks/prod-monitoring.md` for full Raspberry Pi setup instructions.
(This document originally pointed at `docs/prod-health-monitoring.md`, which has
since been merged into that runbook.)

### Stop production

```bash
./scripts/stop-prod.sh
```

Stops all services. Data volumes are preserved.

## Configuration

Environment variables for the monitor (all optional):

| Variable | Default | Description |
| -------- | ------- | ----------- |
| `FAILURE_THRESHOLD` | `3` | Consecutive failures before restart |
| `MAX_RESTARTS` | `3` | Maximum restarts per backoff window |
| `BACKOFF_WINDOW` | `600` | Backoff window in seconds (10 minutes) |
| `CHECK_URL` | `https://simonrowe.dev` | URL to check for reachability |
| `STATE_DIR` | `/tmp/prod-health` | Directory for state files between cron runs |
