# Runbook: Langfuse observability (prod)

Owner-executed. These steps touch the production deploy dir
(`~/workspace/simonjamesrowe/simonrowe-dev-monorepo`) and are intentionally **not**
automated in the app workspace.

## Goal

`admin@simonrowe.dev` sees a project on login, and a chat message produces a visible trace
within ~1 minute — provisioned deterministically so it survives redeploys.

## Background

- Trace path: `backend → Alloy :4317 (gRPC) → http://langfuse:3000/api/public/otel`,
  authed with `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` (`config/alloy/config.alloy`).
- `docker-compose.prod.yml` bootstraps the org, project, admin membership, and **fixed**
  project keys via `LANGFUSE_INIT_*` (idempotent — only creates what's missing).

### Getting the chat generations into Langfuse (fixed 2026-07-23)

Spring AI's ChatClient/ChatModel emit `gen_ai` telemetry through the **Micrometer Observation
API**, not the OpenTelemetry API directly. The `opentelemetry-spring-boot-starter` only creates
spans from its own library instrumentation (HTTP server, MongoDB) and bridges Micrometer
**metrics** — it does **not** bridge the Micrometer Observation API to spans. So before the fix,
Langfuse received only HTTP/Mongo noise and **zero** chat generations (Model cost / usage empty;
`GET`/`POST`/`find …` trace names). Two changes fixed it:

1. **`micrometer-tracing-bridge-otel`** (backend dependency) registers the tracing
   `ObservationHandler`, so Spring AI observations become OTel spans. It is wired to export via
   `management.otlp.tracing.endpoint` (= `OTEL_EXPORTER_OTLP_ENDPOINT`, Alloy gRPC :4317) with
   `management.tracing.sampling.probability: 1.0` (see `backend/.../application.yml`).
2. **Alloy `ai_only` filter** (`config/alloy/config.alloy`) drops every span that is not a Spring
   AI span before the Langfuse exporter, so **Langfuse captures AI traces only** — no HTTP/Mongo/
   `@WithSpan` spans. It keys off `gen_ai.operation.name` / `gen_ai.system` / `spring.ai.kind`.

Note on cost: Langfuse derives cost from its model-price table. A brand-new model id (e.g.
`gpt-5.4-nano`) may not be priced out of the box — token **usage** will still show; add a custom
model price in Langfuse (Settings → Models) if you want the **cost** figure populated.

## One-time / after key changes

1. **Reconcile the deploy-dir `.env`** so the OTLP keys and the init project keys match
   (compose wires `LANGFUSE_INIT_PROJECT_PUBLIC_KEY`/`SECRET_KEY` to
   `${LANGFUSE_PUBLIC_KEY}`/`${LANGFUSE_SECRET_KEY}`, so setting the two OTLP keys is enough).
   Also set:
   - `LANGFUSE_INIT_ORG_ID`, `LANGFUSE_INIT_PROJECT_ID` (or accept the defaults in compose),
   - `LANGFUSE_INIT_USER_EMAIL=admin@simonrowe.dev`, `LANGFUSE_INIT_USER_NAME`,
   - `LANGFUSE_INIT_USER_PASSWORD` — **required** for the admin user + org membership to be
     created. Auth0 SSO then links to that user by email, so the SSO login lands in the org.
   See `.env.example` for the full block.

2. **Restart only `langfuse` and `alloy`** (do NOT restart nginx unless all four upstreams —
   frontend, backend, portainer, langfuse — are running, per the nginx restart gotcha):
   ```bash
   docker start simonrowe-dev-monorepo-langfuse-1
   docker start simonrowe-dev-monorepo-alloy-1
   # or, to reconcile the whole stack respecting depends_on:
   docker compose -f docker-compose.prod.yml up -d langfuse alloy
   ```

3. **Log in** to `https://langfuse.simonrowe.dev` as `admin@simonrowe.dev` (Auth0 SSO) and
   confirm the project is visible.

## Verify a trace

1. Send a chat message on the site (e.g. open the "Ask AI" drawer and ask anything).
2. From the deploy dir, run:
   ```bash
   scripts/verify-langfuse-trace.sh --since-minutes 5
   ```
   It queries the Langfuse public API with the project keys and confirms a matching trace
   exists. Read-only.

## Notes

- Content capture (prompt/completion text) is **off by default** — gen_ai spans are exported
  but visitor chat text is not stored, for privacy. See the commented block in
  `backend/src/main/resources/application.yml` to enable temporarily (verify the exact
  Spring AI 1.1.4 property names first).
- Init is idempotent: leaving `LANGFUSE_INIT_*` in place is safe and keeps observability
  working across redeploys and volume resets.
