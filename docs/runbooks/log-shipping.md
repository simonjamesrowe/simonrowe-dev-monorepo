# Log shipping: Alloy → Grafana Cloud Loki

How production container logs reach Grafana Cloud, what silently stops them, and
how to tell the two "no logs" states apart.

Related: [prod-monitoring.md](prod-monitoring.md) (container health),
[langfuse-observability.md](langfuse-observability.md) (traces, which travel the
same Alloy container but a different pipeline and a different quota).

## The pipeline

`alloy` (one container, `grafana/alloy:latest`) reads `/var/run/docker.sock`
read-only, tails every container's json-file log, and pushes batches to Grafana
Cloud Loki at `logs-prod-035.grafana.net`.

- Config: `config/alloy/config.alloy`, bind-mounted read-only.
- Credentials: `GRAFANA_CLOUD_LOKI_USER` (tenant id) and `GRAFANA_CLOUD_API_KEY`,
  from `.env`. The same key carries both `logs:write` and `logs:read`.
- Read cursors: `positions.yml` under `--storage.path=/var/lib/alloy/data`,
  backed by the **`alloy-data` named volume**.
- Not everything ships. `discovery.relabel "docker_logs"` drops
  `kafka|mongodb|frontend|langfuse-db` outright — they are high volume and low
  diagnostic value. They stay readable via Portainer and `docker logs`. Add a
  service name to that regex to exclude more.

Measured steady-state volume for what does ship: **~20 MB/day = 0.58 GB/month**,
dominated by `backend` (17 MB/day) and `nginx` (3 MB/day). The free-tier
allowance is 50 GB/month, so normal operation uses about 1% of it.

## The failure that cost three weeks of logs

Throughout August 2026 Loki held **nothing**, while:

- `alloy` was `Up (healthy)`, `RestartCount: 0`
- it was tailing containers correctly and building batches
- the read credential worked perfectly

Every batch was rejected:

```
level=error msg="final error sending batch, no retries left, dropping data"
  component_id=loki.write.grafana_cloud host=logs-prod-035.grafana.net
  status=429 error="ingestion rate limit exceeded for user 1539009
    (limit: 0 bytes/sec) while attempting to ingest '1311' lines
     totaling '457403' bytes"
```

**`limit: 0 bytes/sec` means the calendar-month free-tier allowance is spent.**
Grafana Cloud does not throttle proportionally or bill the overage on the free
plan — it sets the tenant's ingest rate to zero for the remainder of the billing
period. 55 GB had been used against the 50 GB allowance.

Three things made it invisible for a month, and all three are worth knowing:

1. **The healthcheck is `alloy --version`.** It passes while every batch is
   dropped. A healthy Alloy proves the binary runs, nothing more.
2. **Reads kept working.** Ingest and query are separately gated, so
   `/api/v1/labels` returned `{"status":"success"}` with an empty body — which
   reads as "the stack is quiet", not as an error. The wrong-tenant control test
   (a deliberately wrong tenant id 401s while the real one 200s) proves the
   *credential* is fine and says nothing about write.
3. **Nothing watches for it.** No alert, no ticket, no dashboard.

## Diagnosing "Loki looks empty"

Run this first, before touching credentials or Alloy config. The 429 names the
problem directly:

```bash
docker logs --since 30m simonrowe-dev-monorepo-alloy-1 2>&1 \
  | grep 'final error sending batch' | tail -1 | sed 's/.*host=//'
```

| What you see | What it means | Fix |
|---|---|---|
| `status=429 ... limit: 0 bytes/sec` | Monthly allowance spent | Below — resets on the 1st |
| `status=401` / `invalid scope` | Credential or scope problem | Access policy on grafana.com |
| `status=429` with a non-zero limit | Genuine rate limit | Reduce volume, or widen the drop regex |
| No send errors at all, Loki still empty | Collection side | Check the relabel drop regex |

Then confirm the account state at
<https://grafana.com/orgs/simonrowedev/my-account/usage> (UI login is Google).
The Logs row shows usage against the 50 GB limit for the current billing period.

Useful cross-check that the credential is not the problem:

```bash
Q="${GRAFANA_CLOUD_LOKI_ENDPOINT%/push}"   # endpoint already includes /loki/api/v1
curl -s -u "999999:$GRAFANA_CLOUD_API_KEY"                "$Q/labels"  # expect 401
curl -s -u "$GRAFANA_CLOUD_LOKI_USER:$GRAFANA_CLOUD_API_KEY" -G "$Q/labels" \
  --data-urlencode "start=$(( $(date +%s) - 2592000 ))000000000"       # expect data
```

## Recovering from an exhausted allowance

**It resets by itself at 00:00 on the 1st of the month.** There is no button, no
support ticket and nothing to run. Corollary worth stating plainly: *logs
reappearing on the 1st is not evidence that anything was fixed.* If the cause is
still present the allowance goes again mid-month.

What needs doing is finding what spent it. Loki holds nothing from the affected
period, and container json-file logs are reset by later restarts, so the
retrospective evidence is usually gone. Measure the present instead:

```bash
# per-container shipped volume, extrapolated from a 10-minute sample
for c in $(docker ps --format '{{.Names}}' | grep -vE 'kafka|mongodb|frontend|langfuse-db'); do
  b=$(docker logs --since 10m "$c" 2>&1 | wc -c)
  echo "$b ${c#simonrowe-dev-monorepo-}"
done | sort -rn | awk '{printf "%8.1f MB/day  %s\n", $1*144/1048576, $2}'
```

Anything above a few tens of MB/day is worth investigating; the whole stack
should total ~20 MB/day.

## The two structural amplifiers

Steady state is 0.58 GB/month, so spending 50 GB takes roughly a hundredfold
amplification. Two mechanisms provided it, and both are now addressed.

### 1. Alloy's read cursors were ephemeral (fixed)

`loki.source.docker` records one cursor per container in `--storage.path`. That
path had **no volume**, so it resolved to the container's writable layer.

`alloy` is in `FACTORY_DEPLOY_RECREATABLE`, so **every deploy recreated it**, and
`monitor-prod.sh`'s minutely `up -d` can too. Each recreate destroyed the
cursors, and Alloy re-tailed every container **from the start** — re-shipping the
whole accumulated history of the stack. Then again on the next deploy.

Nothing logged an error. The entire cost landed as ingested bytes, which is
exactly the kind of failure nobody notices until a quota runs out.

Fixed by the `alloy-data` named volume. Guarded by
`scripts/test/test-log-shipping.sh`, which is in the `run-tests.sh` suite and so
inside the required `Software Factory Build & Test` check.

Verify on the host after the deploy that applies it:

```bash
docker inspect -f '{{range .Mounts}}{{.Destination}} {{end}}' \
  simonrowe-dev-monorepo-alloy-1   # must include /var/lib/alloy/data
```

### 2. No log rotation anywhere (needs a maintenance window)

Every container runs `json-file` with an **empty** options map
(`docker inspect -f '{{.HostConfig.LogConfig.Config}}'` → `map[]`). There is no
`logging:` block in the compose file and, until this change, no
`/etc/docker/daemon.json`. So container logs grow unbounded for the life of the
container — mongodb's was 250 MB after 67 hours — which is what made the re-read
in (1) expensive rather than merely wasteful.

Apply with:

```bash
./scripts/enable-docker-log-rotation.sh --verify   # report current state
./scripts/enable-docker-log-rotation.sh --apply    # write /etc/docker/daemon.json
sudo systemctl restart docker                      # MAINTENANCE WINDOW
```

Defaults are `max-size=20m`, `max-file=5` — 100 MB per container, ~2.2 GB across
22 containers against 53 GB free.

Two things about it that are easy to get wrong:

- **The daemon restart restarts all 22 containers.** That is a full-stack cold
  start, the exact event that broke Langfuse and Dependency-Track on 2026-08-14.
  Afterwards, curl the public hostnames — do not trust a green `compose ps`.
- **The cap applies at container *creation*.** Running containers keep their
  uncapped config until they are next recreated, so `--verify` will keep
  reporting some as unbounded for a while. That is expected, not a failed apply.

### Why rotation is not in the compose file

This is the tempting version, and it wedges production. Adding `logging:` to a
service changes its `docker compose config --hash`, and `sync-config` compares
those hashes against `FACTORY_DEPLOY_RECREATABLE` — an **allowlist of nine
services**. Rotation has to cover all 22, so a compose-file version would make
`sync-config` decline as `held-back`, freezing the deploy directory. The decline
is self-perpetuating (the comparison is host-checkout vs. target, not
previous-target vs. target), which is the wedge that stranded #130 through #136.

`daemon.json` is host configuration: it changes no service hash and applies to
every container regardless of which compose file created it.
`test-log-shipping.sh` asserts the compose file has **no** `logging:` block, so
this cannot be quietly reintroduced.

## Known gap: nothing detects this

There is still no automated signal for "log shipping has stopped". The
`logwatch` module specified in `specs/042-factory-log-watch/spec.md` reads Loki
and files Linear tickets, but as specified it would have reported this outage as
**zero findings — all clear**, because an empty query result and a healthy
system are indistinguishable to it.

Any implementation of that module must assert its source is *alive* — recent
lines exist for containers known to be running, or Alloy's `loki.write`
component is erroring — and file a ticket when it is not. "Cannot see" and
"nothing wrong" must be different outcomes.
