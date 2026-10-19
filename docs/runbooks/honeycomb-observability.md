# Honeycomb Free backend pilot

The US `simonrowe.dev` team uses Honeycomb Free. Alloy sends a sanitized copy of
existing backend traces; Langfuse's AI branch and Loki logs retain their behavior.
No frontend SDK, browser ingestion route, factory instrumentation or new backend
SDK is included in this pilot. Those increments remain in the
[plan](../honeycomb-observability-plan.md).

## Export policy

`config/alloy/honeycomb.alloy` is shared by local and production configurations.
It admits only `simonrowe-backend`, excludes actuator routes and spans with links,
and drops all span events. The pinned Alloy version cannot clear link attributes;
omitting linked spans is an explicit pilot limitation, including possible gaps in
async traces. Parent/trace IDs survive for admitted spans. It samples consistently
by trace ID (10% default), strips attributes to an allowlist, replaces span names
with route/operation names, clears status text and tracestate, and adds environment
and route-derived `app.module`. Child spans without a route have no inferred module.

Allowed fields are service identity/version, route template, method/status/error
type, database system/operation, model/provider/operation and token counts.
For Spring Boot server spans only, the pipeline maps Micrometer's `uri` route
template, `method` and `status` to HTTP semantic fields before the actuator filter.
Client spans and other instrumentation scopes never receive this mapping.
Prompts, completions, tool arguments, SQL, raw URLs, headers, user/family/session
IDs and exception messages are excluded. This is a trusted backend pipeline;
collector ports must never be exposed to untrusted browser payloads. Existing
instrumentation must continue to use route **templates**, not concrete private paths.

No logs or metrics go to Honeycomb. `SampleRate` describes only this collector's
sampling fraction; it cannot correct for earlier SDK drops. Sampling cannot retain
every error or guarantee a monthly quota. Keep the shared SDK sampler unchanged
so Langfuse retains its existing coverage.

## Configure a short capture

Credentials belong in the existing env source `~/workspace/simonjamesrowe/env`
and deployment `.env`, never git, frontend variables, terminal output or PRs.
Create an **Ingest** key in the intended Honeycomb environment. The ingest key
cannot query Honeycomb or authorize MCP. The account region is US:
`HONEYCOMB_OTLP_ENDPOINT=https://api.honeycomb.io` (base URL, no `/v1/traces`).

Production requires all three: `HONEYCOMB_ENABLED=true`, a nonempty
`HONEYCOMB_API_KEY`, and a future `HONEYCOMB_EXPORT_UNTIL` in absolute Unix seconds.
Zero/past expiry or a missing key drops every Honeycomb span. Restarts cannot
extend the capture. Set `HONEYCOMB_ENVIRONMENT=production` and
`HONEYCOMB_SAMPLE_PERCENTAGE=10` (supported range 0 through 100; zero drops all
Honeycomb spans without interrupting Langfuse or Loki).
For example, `python3 -c 'import time; print(int(time.time()) + 3600)'` prints a
one-hour deadline. Use 100% only for a brief observed diagnostic capture.

Local compose deliberately uses separate `HONEYCOMB_LOCAL_ENABLED`,
`HONEYCOMB_LOCAL_API_KEY`, `HONEYCOMB_LOCAL_SAMPLE_PERCENTAGE` and
`HONEYCOMB_LOCAL_EXPORT_UNTIL`. Use the `test` environment's key locally;
production env settings alone cannot enable local export.

Free includes 20M events/month across the account. Target no more than 14M,
including test traffic. Check Honeycomb Usage before and after each capture and
project the observed volume over the account's usage period. Stop/reduce sampling
if the forecast exceeds that target. **There is no persistent monthly quota
controller yet.** The proposed 18M emergency cutoff is future work; absolute
expiry is the current control. Do not configure indefinite capture or add browser
traffic before budget accounting is implemented. Do not upgrade the plan.

## Validate and deploy

1. Run `bash scripts/test/test-honeycomb.sh` with Docker. It starts both real
   configs (the `validate` command alone does not evaluate runtime expressions)
   and exercises the pinned Alloy against local HTTP sinks: content
   removal, parentage, actuator/link filtering, unchanged Langfuse content and
   disabled/missing-key/expired combinations. It sends nothing to Honeycomb.
2. Follow [production deployment](deploy.md) and the production
   section of `CLAUDE.md`. Check nginx and upstream health before restarting.
   Config is bind-mounted: the Pi checkout must contain both Alloy configuration
   and the new module. Pull `grafana/alloy:v1.19.2` and recreate **Alloy only**
   after the reviewed change lands. Do not use `docker compose config` without
   `--quiet`, because its rendered output exposes env credentials.
3. Complete the [post-deployment browser verification](#post-deployment-browser-verification)
   below. The container's `--version` healthcheck is not proof that export works.
4. Confirm export stops at the deadline. Export retries are bounded to 30 seconds;
   an already buffered/in-flight batch can arrive after expiry. Queues are in
   memory, at most 64 export requests, with batches up to 128 spans.

Rollback: set `HONEYCOMB_ENABLED=false` and recreate Alloy to apply env changes.
For a collector regression, restore the previous validated image/config together.
No data migration or application redeployment is required for the export switch.

## Post-deployment browser verification

Run the agent-setup
[`honeycomb-verify` skill](https://github.com/simonjamesrowe/agent-setup/blob/main/components/skills/honeycomb-verify/SKILL.md)
through **Playwright MCP** after the deployed checkout and running release have
been verified. `prod-deploy` invokes this check when Honeycomb is enabled or its
pipeline changes. This is an agent-driven browser check, separate from Temporal's
automated deployment health checks; a green deploy workflow does not imply it ran.

The skill sends a harmless public API request with a unique W3C trace ID, finds
that exact fresh trace in the correct Honeycomb team/environment, and inspects
service, route template, method/status, environment, sampling weight and allowed
fields. Pipeline changes also require a fresh harmless chat turn: check an actual
parent/child pair in Honeycomb and the same turn in production Langfuse. The
synthetic local `test` trace used during setup is not production evidence.

Use a brief observed 100% capture within the authorized rollout when deterministic
evidence is needed; restore the prior sampling configuration afterwards, including
on failure, and never extend the absolute deadline accidentally. At the normal
10% rate, bounded probes can all be sampled out. Report that as **INCONCLUSIVE**,
not success; do not repeatedly call the model or silently raise sampling.

Save a sanitized screenshot, trace URL/ID, UTC time, deployed SHA, capture
settings/usage and each subcheck's result under `.context/`. Overall **PASS** needs
fresh matching evidence for every required check. Observed export/privacy/field
failures are **FAIL**; missing evidence or access is **INCONCLUSIVE**; intentionally
disabled/expired capture is **NOT RUN**, never a successful ingest check. This
backend pilot does not verify browser, Kafka, STOMP or factory instrumentation.

## Agent access

`agent-setup` offers opt-in `honeycomb` and `amplitude` MCP registrations. Website
Google login and MCP OAuth are separate grants. Use Honeycomb read-only OAuth
(`codex mcp login honeycomb --scopes mcp:read`); enable Team Intelligence if required.
Do not copy ingest keys into MCP configuration. Amplitude uses its own user OAuth
and project roles (`codex mcp login amplitude --scopes all:read,offline_access`);
Enterprise service accounts are outside this setup. Both local Codex connections
were authorized read-only during setup; restart the agent session to load newly
registered servers. Registration/OAuth does not prove every MCP tool is entitled.
