# Langfuse Sessions, Content Capture & Evaluations Design

## Overview

Langfuse in production receives Spring AI `gen_ai` spans, but every trace it stores is
**shallow**: unnamed, with no session, no user, and no input or output. Consequently the
Sessions view is empty, generations show blank prompts and completions, and no scores or
evaluators exist.

This design fixes all four, in dependency order: capture content → attach session and
trace-level input/output → enable scores and LLM-as-a-judge evaluators. It also brings the
local stack to production parity so the work is verifiable before it reaches the Raspberry Pi.

Target versions, both source-verified while researching this design:
**Langfuse v3.212.0** (self-hosted) and **Spring AI 1.1.4**.

## 1. Diagnosis

Four complaints, one causal chain. Evidence gathered from the live prod Langfuse API on
2026-07-26.

### 1.1 "Tracing contains non-AI traces" — already fixed; this is backlog

The `ai_only` Alloy filter shipped in PR #77 (2026-07-23) but only took effect when Alloy was
restarted, at approximately **2026-07-25 15:30**. Since that moment no non-AI trace has
arrived.

| Window | Total traces | Trace names |
| --- | --- | --- |
| All time | 83,998 | mostly `security filterchain before/after`, `http get /ws/chat`, `elasticsearch query` |
| Since 2026-07-25T16:00Z | 5 | all `<unnamed>` |
| Since 2026-07-26T00:00Z | 3 | all `<unnamed>` |

The ~84k noise traces are historical rows in ClickHouse. No code change is required — only a
data purge (§6).

The delay between merge and effect has a cause worth recording: **`config/alloy/config.alloy`
is bind-mounted from the deploy directory**, so merging to `main` does not update it. The
deploy dir must be pulled *and* Alloy restarted. This is the same trap as the frontend
`nginx.conf`.

### 1.2 Every trace is `<unnamed>` — the root cause of the missing sessions

From `packages/shared/src/server/otel/OtelIngestionProcessor.ts` at tag `v3.212.0`:

```ts
const isRootSpan =
  !parentObservationId ||
  String(attributes[LangfuseOtelSpanAttributes.AS_ROOT]) === "true";
```

A span is the trace root only if it has no `parentSpanId`. The `ai_only` filter drops the
HTTP/WebSocket root span, so the surviving Spring AI spans arrive **orphaned** — they carry a
non-null `parentSpanId` whose target was never ingested. Langfuse then creates a *shallow
trace* containing only `{ id, timestamp, environment }`: no name, no input, no output, no
session, no user.

Trace identity itself survives filtering, because it derives from the W3C trace id. Only the
trace-level *fields* are lost.

Separately, **no span in the pipeline carries a session id at all.** Spring AI never emits
one. Even without the shallow-trace problem, Sessions would be empty.

### 1.3 Prompts and completions are absent — Spring AI 1.1.4 cannot emit them

`backend/src/main/resources/application.yml` carries a commented block suggesting
`spring.ai.chat.observations.log-prompt: true` / `log-completion: true`. Those property names
are valid and current in 1.1.4 (the `include-*` spellings were the *older* names, renamed in
1.0.0-RC1), **but they do not write span attributes.**

`ChatModelPromptContentObservationHandler` in 1.1.4 does exactly one thing:

```java
logger.info("Chat Model Prompt Content:\n{}", ObservabilityHelper.concatenateStrings(prompt(context)));
```

It emits an SLF4J log line. `AiObservationAttributes` contains no `PROMPT` or `COMPLETION`
constant — `gen_ai.prompt` and `gen_ai.completion` are **never** set as span attributes by the
framework, and `spring.ai.chat.client.user.text` no longer exists in 1.1.4. The
`*ContentObservationFilter` classes that once did this were deleted in commit `ca843e8`.

Capturing content therefore requires a **custom `ObservationFilter` bean that we write**.
The existing comment and the runbook are both misleading and must be corrected.

### 1.4 No scores or evaluators — available, but blocked on 1.3

LLM-as-a-judge, annotation queues, prompt experiments and the playground are **Open Source
tier** features on self-hosted Langfuse (relicensed MIT in June 2025, included from v3.65.0;
prod runs 3.212.0). They are absent from the enterprise-gated feature list. Nothing is
licence-blocked.

They are, however, useless against traces with empty input and output, so they depend on §1.3.

## 2. Decisions taken

| Decision | Choice | Note |
| --- | --- | --- |
| Content capture | **Full prompt and completion, no redaction** | Reverses the 2026-07-17 privacy decision. See §2.1. |
| Scoring sources | Deterministic backend scores **and** LLM-as-a-judge | Visitor thumbs up/down and a promptfoo revival are explicitly out of scope. |
| Historical data | **Wipe the Langfuse project entirely** | Accepts the loss of ~2k legitimate AI traces. |
| Local environment | **Full parity** — Langfuse v3 and Alloy locally | Verification happens against local Langfuse, never prod. |
| Transport | Keep OTel → Alloy → Langfuse OTLP | Direct `/api/public/ingestion` rejected; see §3.1. |

### 2.1 Privacy reversal, recorded deliberately

The 2026-07-17 decision was to keep content capture off because storing visitor chat text in
ClickHouse was not justified for a public portfolio chat. This design reverses that at the
owner's explicit instruction.

The consequence should be stated plainly rather than buried: the chat invites recruiters to
paste job specifications and visitors to submit their name and email via `submitContactForm`.
**Real third-party PII will be persisted in Langfuse.** The `application.yml` comment and
`docs/runbooks/langfuse-observability.md` must be updated so they no longer assert the
opposite.

## 3. Architecture

The transport is unchanged. What changes is what the spans carry.

```
ChatService ── opens ONE "chat-turn" span per message ──────────────┐
            │   session.id                                          │
            │   langfuse.trace.name                                 │
            │   langfuse.trace.input                                │
            │   langfuse.environment                                │
            │   …closed at stream completion, adding                │
            │   langfuse.trace.output = assembled answer            │
            │                                                       │
            ├─ Spring AI child spans (chat / embedding / tool_call) │
            │  + ObservationFilter adds                             │
            │    langfuse.observation.input / .output               │
            │                                                       ▼
            └─ micrometer-tracing-bridge-otel → OTLP gRPC → alloy:4317
                    └─ otelcol.processor.filter "ai_only"  (keep-list gains chat-turn)
                       └─▶ http://langfuse:3000/api/public/otel

ChatService ── at turn end, out-of-band ──▶ POST /api/public/scores
               (trace id from Span.current(), fire-and-forget)
```

### 3.1 Why a non-root span still names the trace

`OtelIngestionProcessor.hasTraceUpdates()` patches the trace with name, input, output,
session, user, tags and metadata taken from **any** ingested span carrying those attributes —
root or not. So the HTTP root span can stay dropped.

This is why the design does **not** use `langfuse.internal.as_root`. That attribute exists and
works in 3.212.0, but it is marked `// Internal` in the source, is absent from the public
documentation, and could change between releases. The `hasTraceUpdates()` path relies only on
publicly documented attribute names.

Calling `/api/public/ingestion` directly was rejected: it would reimplement token usage, model
attribution, timings and span nesting that Spring AI observations already produce, and would
orphan Alloy.

### 3.2 Why the chat-turn span, and not a filter on the ChatClient observation

The chat is streamed. The final answer only exists once `doOnComplete` fires and
`ChatController` has assembled `fullResponse`. Every Spring AI observation closes before that
point, so no `ObservationFilter` can see the complete answer. A span whose lifecycle we control
across the `Flux` is the only thing that can carry `langfuse.trace.output`.

### 3.3 Verified Langfuse attribute names

Taken verbatim from `packages/shared/src/server/otel/attributes.ts` at `v3.212.0`.

| Purpose | Attribute | Notes |
| --- | --- | --- |
| Session | `session.id` | `langfuse.session.id` also works and takes precedence |
| User | `user.id` | `langfuse.user.id` also works and takes precedence |
| Trace name | `langfuse.trace.name` | falls back to the root span's OTel span name |
| Trace input | `langfuse.trace.input` | trace-domain only |
| Trace output | `langfuse.trace.output` | trace-domain only |
| Trace tags | `langfuse.trace.tags` | string array or JSON-array string |
| Observation input | `langfuse.observation.input` | highest-precedence input mapping |
| Observation output | `langfuse.observation.output` | highest-precedence output mapping |
| Environment | `langfuse.environment` | separates `development` from `production` |

`langfuse.observation.input`/`.output` are preferred over `gen_ai.prompt`/`gen_ai.completion`.
Both work, but the latter route through `convertKeyPathToNestedObject`, whose behaviour depends
on whether sibling `gen_ai.prompt.N.content` keys are present.

## 4. Components

### 4.1 `ChatTurnTracer` (new)

`backend/src/main/java/com/simonrowe/chat/ChatTurnTracer.java`

Wraps the chat `Flux` with span lifecycle management, extracted from `ChatService` so that
`ChatService` stays about chat.

Responsibilities:

- Start a `chat-turn` span; set `session.id`, `langfuse.trace.name`, `langfuse.trace.input`,
  `langfuse.environment`.
- Make the span current for the duration of the subscription, so Spring AI spans nest beneath
  it and share its trace id.
- Accumulate streamed text to reconstruct the final answer.
- On completion, set `langfuse.trace.output`, then end the span.
- On error, record the exception, set `langfuse.observation.level=ERROR`, then end the span.
- Use `doFinally` as a backstop so a span can never leak.
- After the span ends, hand the trace id, verdict, tool-call count and error/empty flags to
  `LangfuseScoreClient`.

Replaces the existing `@WithSpan` annotation on `ChatService.processMessage`, which closes when
the method returns the `Flux` — before any streaming happens.

### 4.2 `LangfuseContentObservationFilter` (new)

`backend/src/main/java/com/simonrowe/observability/LangfuseContentObservationFilter.java`

`ObservationFilter` beans that copy prompt and completion out of `ChatModelObservationContext`
into high-cardinality key values named `langfuse.observation.input` and
`langfuse.observation.output`. A second filter does the same for tool-call observations.

- Content is serialised to JSON.
- Each value is **truncated to 32 KB**, with a `…[truncated]` marker appended (§7.2).
- Serialisation failures are caught and degrade to omitting the attribute.

Registered in a new `ObservabilityConfig`, guarded by
`langfuse.content-capture.enabled` (default `true`) so capture can be switched off without a
redeploy.

### 4.3 `GuardrailVerdictRegistry` (new)

`backend/src/main/java/com/simonrowe/chat/GuardrailVerdictRegistry.java`

A session-keyed `ConcurrentHashMap<String, String>` holding the most recent classification,
mirroring the existing `ChatContactTracker` pattern. `GuardrailAdvisor` publishes; `ChatTurnTracer`
reads and removes at turn end. Entries are removed on read and on session eviction, so the map
cannot grow unbounded.

### 4.4 `GuardrailAdvisor` (refactor)

`adviseCall` (lines 77–115) and `adviseStream` (lines 117–157) are near-identical copies of the
same classification logic. Extract a private `classify(ChatClientRequest)` returning the verdict,
and have both paths call it and publish to the registry. This is in scope because both paths
need the new publication step; without extraction the duplication doubles.

Behaviour is unchanged, including failing open on classifier error.

### 4.5 `LangfuseScoreClient` (new)

`backend/src/main/java/com/simonrowe/observability/LangfuseScoreClient.java`

A `RestClient` posting to `POST /api/public/scores` with HTTP Basic auth (public key as
username, secret key as password). Scores have **no OpenTelemetry path** — a direct API call is
required regardless of transport choice.

Request fields used: `traceId`, `name`, `value`, `dataType`, `comment`. The trace id is the
32-hex-character W3C id from `Span.current().getSpanContext().getTraceId()`, which Langfuse
stores verbatim as the trace id for OTLP-ingested traces.

Note: `stringValue` is a **response-only** field. Categorical scores pass the string in `value`.

Four deterministic scores per turn:

| Name | Data type | Value |
| --- | --- | --- |
| `guardrail` | `CATEGORICAL` | `SAFE` / `OFF_TOPIC` / `HARMFUL` |
| `tool-call-count` | `NUMERIC` | tool invocations in the turn, counted by `ChatTurnTracer` as the number of streamed `ChatResponse`s for which `hasToolCalls()` is true |
| `error` | `BOOLEAN` | whether the turn terminated in error |
| `empty-answer` | `BOOLEAN` | whether the assembled answer was blank |

Latency is deliberately excluded: Langfuse already records it as a first-class field, so a score
would duplicate it.

Configuration under a `langfuse.*` prefix: `scores.enabled` (default `false`, enabled per
environment), `host`, `public-key`, `secret-key`, `environment`.

### 4.6 `config/alloy/config.alloy`

The `ai_only` OTTL drop condition gains one clause so the chat-turn span survives:

```
attributes["gen_ai.operation.name"] == nil and attributes["gen_ai.system"] == nil
  and attributes["spring.ai.kind"] == nil and attributes["langfuse.trace.name"] == nil
```

The comment block is updated to explain that the chat-turn span is the carrier of trace-level
session and input/output, and that dropping it returns every trace to shallow state.

### 4.7 Local parity — `docker-compose.yml`

Bring local in line with `docker-compose.prod.yml`:

- `langfuse` image `2.95.1` → `3.212.0`, plus a `langfuse-worker` at the same version.
- Add `langfuse-clickhouse`, `langfuse-redis`, `langfuse-minio` and the volumes they need,
  reusing the prod service definitions.
- Add the `LANGFUSE_INIT_*` bootstrap block so a local project with fixed keys exists on boot.
- Add an `alloy` service bound to a **new, traces-only** `config/alloy/config.local.alloy`.

`config.local.alloy` contains the OTLP receiver, the batch processor, the `ai_only` filter and
the Langfuse exporter, and **deliberately omits all Loki and Docker log-shipping blocks** so
that local container logs are never sent to Grafana Cloud.

Cost: four additional local containers. Accepted as the price of verifiability.

### 4.8 `scripts/bootstrap-langfuse-evaluators.sh` (new)

Idempotent shell script, in the style of `scripts/verify-langfuse-trace.sh`, reading keys from
the environment or the directory's `.env`, defaulting `LANGFUSE_HOST` to
`https://langfuse.simonrowe.dev`.

1. `PUT /api/public/llm-connections` — upsert an OpenAI connection keyed on `provider`, using
   `OPENAI_API_KEY`. The judge model must support structured output.
2. `POST /api/public/unstable/evaluators` — create the evaluators. Re-posting an existing `name`
   creates a new version and migrates existing rules to it, so re-runs are safe.
3. `POST /api/public/unstable/evaluation-rules` — one rule per evaluator, `target: observation`,
   `sampling: 0.2`, with a complete variable mapping.

Evaluators: **hallucination, helpfulness, toxicity, context-relevance** — all available as
managed templates. The exact managed-template catalogue is not published; the script enumerates
`GET /api/public/unstable/evaluators` first and falls back to a custom prompt for any template
that is missing.

Every evaluator is given an explicit `modelConfig`, because there is **no public API for setting
the project default evaluation model** — that is UI-only.

The script header must state that `/api/public/unstable/*` is explicitly unstable and pinned to
Langfuse 3.212.0.

### 4.9 Documentation corrections

- `backend/src/main/resources/application.yml` — replace the misleading `log-prompt` /
  `log-completion` comment block with an accurate note that those properties only write log
  lines, and that content capture is performed by `LangfuseContentObservationFilter`.
- `docs/runbooks/langfuse-observability.md` — correct the "Notes" section, document the
  content-capture reversal, add the project-wipe procedure, and record that
  `config/alloy/config.alloy` is bind-mounted and needs a deploy-dir pull plus an Alloy restart.
- `.env.example` — add `LANGFUSE_SCORES_ENABLED`, `LANGFUSE_ENVIRONMENT`.

## 5. Error handling

Observability must never break chat. Three rules:

1. **Score submission is fire-and-forget** on a small bounded executor. Failures are logged at
   WARN and swallowed. A saturated queue drops scores rather than blocking the chat thread.
2. **Span closure is guaranteed.** `doOnComplete`, `doOnError` and a `doFinally` backstop. A
   leaked span produces a trace that never closes and is worse than no trace.
3. **Content filters degrade quietly.** Serialisation failure, or content exceeding the cap,
   results in a truncated or absent attribute — never a thrown exception inside an observation.

## 6. Data purge

Destructive, and performed on the Pi by the owner.

Langfuse data-retention policies are enterprise-gated, so there is no scheduled-deletion route.
The chosen approach is to **delete the project in the Langfuse UI**, then restart the `langfuse`
container. The idempotent `LANGFUSE_INIT_*` bootstrap recreates the org, project, admin
membership and — critically — the **same fixed project keys**, so Alloy's OTLP basic auth
continues to match without any key copying.

`docker-compose.prod.yml` needs no change for this.

**Do not restart nginx** during the purge. It resolves all four upstreams at startup and aborts
if any is down, which would also take Portainer offline.

## 7. Risks

### 7.1 Streaming completion capture is unverified

`LangfuseContentObservationFilter` reads `ChatModelObservationContext.getResponse()`. Whether
1.1.4 populates that with the aggregated response for a **streaming** call has not been
confirmed.

**Mitigation:** prove it with a spike as the first implementation task. Blast radius is
contained — trace-level input and output come from the chat-turn span, which is what the
evaluators read. If per-generation capture proves impossible for streaming, the feature still
delivers sessions, trace IO and evaluators.

### 7.2 Attribute size

The system prompt is roughly 6 KB before RAG context is appended, and a turn produces several
generations. Unbounded capture risks the default 4 MB gRPC message limit and inflates ClickHouse
storage on a Raspberry Pi.

**Mitigation:** the 32 KB per-attribute cap in §4.2.

### 7.3 Unstable evaluator API

`/api/public/unstable/evaluators` and `/api/public/unstable/evaluation-rules` are marked unstable
by Langfuse, pending a data-model redesign. The bootstrap script will break on some future
upgrade.

**Mitigation:** accepted. Pin the version and say so in the script header.

### 7.4 Judge cost

Four evaluators against every trace is a recurring OpenAI bill for little benefit on a
low-traffic site.

**Mitigation:** `sampling: 0.2` initially, raised manually if the signal proves worth it.

## 8. Testing

| Level | Coverage |
| --- | --- |
| Integration (highest value) | OTel `InMemorySpanExporter`: one chat turn produces exactly one `chat-turn` span carrying `session.id`, `langfuse.trace.input` and `langfuse.trace.output`; Spring AI spans share its trace id. |
| Unit | `LangfuseContentObservationFilter` attribute mapping, including the truncation boundary and a serialisation failure. |
| Unit | `GuardrailVerdictRegistry` publish/read/evict; entries removed on read. |
| Unit | `GuardrailAdvisor` refactor — existing tests must pass unchanged, plus verdict publication from both the call and stream paths. |
| Unit | `LangfuseScoreClient` against a mocked server: request shape, basic auth, and that a server error never propagates. |
| End-to-end (local) | Full local stack; send a chat message; assert via the local Langfuse API that the trace has a name, session, input and output. |

The OTTL filter cannot be unit-tested. Local parity exists precisely to cover it.

`scripts/verify-langfuse-trace.sh` gains `--expect-session` and `--expect-io` flags so
verification is a command with an exit code rather than a visual inspection.

All backend work must satisfy Checkstyle and the pre-commit hook (see the `backend-test` skill).

## 9. Rollout

Local parity lands first so that every subsequent step is verifiable before it reaches the Pi.

1. Spike: confirm whether streaming populates `ChatModelObservationContext.getResponse()` (§7.1).
2. Local compose → Langfuse v3 plus traces-only Alloy.
3. Backend: `ChatTurnTracer`, content filters, `GuardrailVerdictRegistry`, `GuardrailAdvisor`
   refactor, `LangfuseScoreClient`.
4. Alloy keep-list, in both `config.alloy` and `config.local.alloy`.
5. Verify end-to-end locally; extend `verify-langfuse-trace.sh`.
6. Documentation corrections.
7. Merge → the Publish workflow builds images → deploy to the Pi.
8. **On the Pi**, as a single copy-paste block: pull the deploy dir, delete the Langfuse project
   in the UI, restart `langfuse` (bootstrap recreates it with the same keys), restart `alloy`.
9. Run `bootstrap-langfuse-evaluators.sh` against prod.
10. Send a real chat message; verify session, input, output, scores and evaluator results.

Production runs on a Raspberry Pi with **no SSH access from the development machine**. Steps 8
and 9 are emitted as copy-paste command blocks for the owner to run, with their output reported
back.

## 10. Out of scope

- Visitor thumbs up/down feedback UI.
- Reviving the promptfoo eval suite from PR #58 as a Langfuse dataset experiment.
- PII redaction or masking of captured content (§2.1).
- Re-enabling the Grafana Cloud Tempo exporter, which remains disabled on a wrong-region endpoint.
- Metrics scraping — `/actuator/prometheus` is still unscraped.
