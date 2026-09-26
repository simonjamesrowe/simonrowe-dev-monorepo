# Runbook: production heartbeat

`.github/workflows/prod-heartbeat.yml` runs `scripts/prod-heartbeat.sh` every 15
minutes on GitHub's runners. When production looks wrong the run fails, and
GitHub emails the failure to whoever last changed the workflow's `cron` line.

## Why it runs off the Pi

Every other alarm this stack has runs on the Pi and needs the Pi's outbound
network. `monitor-prod.sh` heals but only writes to a local log. The log watch
reads Grafana Cloud Loki from `software-factory` and files tickets through Linear,
also from `software-factory`. When the Pi loses the network, or its containers
lose DNS, all of them stop at once. Silence then looks the same as health, and
nobody is told.

Two outages have had exactly that shape:

- **August 2026:** the Grafana Cloud free-tier allowance ran out and Loki
  accepted nothing for three weeks while alloy reported `healthy`. See
  [log-shipping.md](log-shipping.md).
- **2026-09-24 to 09-26:** the Pi rebooted with the Wi-Fi down and Docker started
  the stack with no upstream DNS. For ~34 hours alloy shipped nothing,
  Dependency-Track's Auth0 sign-in was missing, and software-factory could reach
  neither Loki nor Linear. It was noticed by hand. See
  [prod-monitoring.md](prod-monitoring.md#why-layer-4-exists-2026-09-24-dns-outage).

## What it checks, and what a failure usually means

| Check | Fails when | Usual cause |
| --- | --- | --- |
| `logs` | Loki has no lines from `simonrowe-dev-monorepo-*` containers in the last 30 minutes | Log shipping has stopped. alloy has no DNS (`scripts/enable-docker-dns.sh --verify` on the Pi), the Grafana Cloud quota is spent (a `429` in `docker logs alloy`), or the Pi is offline. Every hostname failing as well points to the Pi being offline |
| `logs: cannot check` | Loki returned an error or an unreadable answer | The GitHub secrets are wrong or the token was rotated; it does **not** mean prod is fine |
| each hostname | Not the expected status after ~5 minutes of retries | The service behind it is down, or the whole Pi/tunnel is (all of them fail together). The retries absorb a normal deploy's maintenance page; a 503 that outlasts them means the page was left up |
| `dependency-track sign-in` | `/api/v1/oidc/available` is not `true` | The apiserver cannot fetch Auth0's discovery document: almost always outbound DNS. `docker logs <apiserver> \| grep UnknownHost` confirms it; restarting the container fixes it |

Run it by hand from a laptop at any time, with the Loki values from the shared
env file:

```bash
set -a; source ~/workspace/simonjamesrowe/env; set +a
ATTEMPTS=1 ./scripts/prod-heartbeat.sh
```

## Secrets

Three repository secrets, holding the same values alloy uses:
`GRAFANA_CLOUD_LOKI_ENDPOINT` (the **push** URL; the script strips `/push`),
`GRAFANA_CLOUD_LOKI_USER` and `GRAFANA_CLOUD_API_KEY`. That token is alloy's
`alloy-publisher` access policy, which carries logs **write** as well as read. The
workflow only runs on `schedule` and `workflow_dispatch`, never on pull requests,
so fork PRs cannot reach it. A read-only token would still be better: create an
access policy with only `logs:read` in the Grafana Cloud portal and replace
`GRAFANA_CLOUD_API_KEY` with it. When alloy's token is rotated, update the secret
as well, or every run fails as `cannot check`.

```bash
# Wrapped in `bash -c` on purpose: `${!v}` is bash-only, and in zsh (the macOS
# default shell) it fails with "bad substitution" - after which each secret is
# silently set to an EMPTY string and every run fails as `cannot check`.
bash -c 'set -a; source ~/workspace/simonjamesrowe/env; set +a
for v in GRAFANA_CLOUD_LOKI_ENDPOINT GRAFANA_CLOUD_LOKI_USER GRAFANA_CLOUD_API_KEY; do
  [[ -n "${!v}" ]] || { echo "$v is empty - not setting it"; exit 1; }
  printf "%s" "${!v}" | gh secret set "$v"
done'
```

## Limits

- GitHub delays scheduled runs under load, typically by 5-15 minutes, so detection
  takes 30-60 minutes rather than 15. That is fine for the outages this exists
  for, which lasted days.
- GitHub disables scheduled workflows in a repository with no activity for 60
  days. That is not a risk for this repository today, but a disabled heartbeat is
  silent too. The workflow page says so.
- It emails on every failed run (every ~15 minutes during an outage). That is
  deliberate: an alarm that only fires once is easy to miss.
