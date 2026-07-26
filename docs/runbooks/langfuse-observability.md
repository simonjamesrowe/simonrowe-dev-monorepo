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

## Purging trace data

Langfuse data-retention policies are enterprise-gated, so there is no scheduled deletion. To
wipe a project's traces, delete the project in the UI and let the bootstrap recreate it — the
idempotent `LANGFUSE_INIT_*` block restores the org, project, admin membership and the **same
fixed project keys**, so Alloy's OTLP basic auth keeps matching with no key copying.

1. Langfuse UI → project settings → Delete project.
2. Restart Langfuse so the bootstrap runs:
   ```bash
   docker compose -f docker-compose.prod.yml up -d langfuse
   ```
3. Confirm the project is back with the same keys: `scripts/verify-langfuse-trace.sh`.

**Do not restart nginx** as part of this. It resolves all four upstreams at startup and aborts
if any is down, which would also take Portainer offline.

## Notes

- **Content capture is ON** (decision 2026-07-26, reversing 2026-07-17). Visitor chat text —
  including recruiter-pasted job specs and contact-form details — is stored in Langfuse.
  Toggle with `LANGFUSE_CONTENT_CAPTURE_ENABLED`. Setting it to `false` is a **complete**
  off-switch: it gates both our `LangfuseContentObservationFilter` and Spring AI's own
  `spring.ai.tools.observations.include-content`. Both must stay bound to it — Spring AI's filter
  writes tool arguments as span attributes independently of ours, and those include the contact
  form's name, email, subject and message.
- **"No scores in Langfuse?" check the startup log first.** `LangfuseScoreClient` logs exactly once
  at boot whether score submission is enabled, and if not, whether that is because
  `LANGFUSE_SCORES_ENABLED=false` or because `LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY` are
  missing. Submission itself is silent — it is a per-turn hot path.
- Scores carry `environment` (from `LANGFUSE_ENVIRONMENT`) to match the `langfuse.environment` on
  their trace. Without it, scores file under `default` and environment-filtered dashboards read
  empty.
- `spring.ai.chat.observations.log-prompt` / `log-completion` **do not work** for this purpose
  and never did. Verified in Spring AI 1.1.8 source: `ChatModelPromptContentObservationHandler`
  only calls `logger.info()`, and `AiObservationAttributes` has no prompt/completion constant,
  so `gen_ai.prompt` / `gen_ai.completion` are never emitted as span attributes at any 1.x or
  2.x version. Content capture is done by `com.simonrowe.observability.LangfuseContentObservationFilter`.
- **Traces are named and grouped into Sessions by the `chat-turn` span**
  (`com.simonrowe.chat.ChatTurnTracer`), which carries `session.id`, `langfuse.trace.name` and
  `langfuse.trace.input`/`.output`. Langfuse's `hasTraceUpdates()` applies these to the trace
  even though the span is not the trace root — the HTTP root is still dropped by `ai_only`.
  If the chat-turn span is ever filtered out, every trace reverts to shallow: unnamed,
  sessionless and empty.
- **⚠️ `config/alloy/config.alloy` is bind-mounted from the deploy directory.** Merging to
  `main` does NOT update it. The deploy dir must be `git pull`ed *and* Alloy restarted. This is
  why the `ai_only` filter appeared broken for two days after shipping on 2026-07-23 — it only
  took effect at the 2026-07-25 restart. Same trap as the frontend `nginx.conf`.
- **`POST /api/public/otel` is not a useful v2-vs-v3 probe** — it returns **404 on both**
  Langfuse v3 and v2, so a 404 there tells you nothing about which version is running. The real
  OTLP ingest path is `POST /api/public/otel/v1/traces`, which returns **401** without auth
  credentials; that 401 (not a 404) is the proof the route exists. Despite this, do **not** add
  `/v1/traces` to the exporter endpoint in `config/alloy/config.alloy` — `otelcol.exporter.otlphttp`
  appends `/v1/traces` itself, so the configured `http://langfuse:3000/api/public/otel` is
  already correct, and appending the suffix there would double the path and break ingest.
- Init is idempotent: leaving `LANGFUSE_INIT_*` in place is safe and keeps observability
  working across redeploys and volume resets.
