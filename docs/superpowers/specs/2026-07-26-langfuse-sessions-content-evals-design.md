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
**Langfuse v3.212.0** (self-hosted) and **Spring AI 1.1.8** (upgraded from 1.1.4 as part of this
work — see §2.2). Every Spring AI finding below was verified at both 1.1.4 and 1.1.8, which are
byte-identical across the observability source tree.

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

### 1.3 Prompts and completions are absent — no Spring AI version emits them

(Diagnosed at the pinned 1.1.4. It holds unchanged at 1.1.8, and — verified — at 2.0.0 too, so
no upgrade fixes this.)

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
| Dependency versions | Patch-level bumps only; **stay on Spring AI 1.x** | See §2.2. |

### 2.2 Dependency upgrades — patch level only

Current pins are behind. The bumps taken here are all patch-level and carry no behavioural
change relevant to this work:

| Component | Pinned | Target | Rationale |
| --- | --- | --- | --- |
| Spring Boot | 3.5.9 | **3.5.16** | Patch-level. Spring AI 1.1.8 itself targets 3.5.15. |
| Spring AI | 1.1.4 | **1.1.8** | Patch-level. Buys nothing for this design (see below) but keeps the floor current. |
| `opentelemetry-spring-boot-starter` | 2.25.0 | **2.30.0** | Keep the OTLP exporter current; realign the `opentelemetry.version` ext pin (currently `1.59.0`). |

**Spring AI 2.0.0 is explicitly rejected.** It is GA, but it does not solve any part of this
problem and costs a great deal:

- It still emits **zero** prompt/completion span attributes, **zero** GenAI content span events
  (`gen_ai.user.message`, `gen_ai.choice`, `gen_ai.client.inference.operation.details` are all
  absent from the tree), and still uses `spring.ai.chat.client.conversation.id` rather than
  `gen_ai.conversation.id`. The custom filter in §4.2 would be required regardless. The class
  javadoc was even softened from "Based on" to "*Inspired by* the OpenTelemetry Semantic
  Conventions" — a deliberate signal they are not tracking semconv.
- Its `pom.xml` sets `<spring-boot.version>4.1.0</spring-boot.version>`, and this is a **hard
  requirement**: its autoconfigure modules import Boot 4's modularised packages
  (`org.springframework.boot.elasticsearch.autoconfigure`, `…data.mongodb.autoconfigure`,
  `…restclient.autoconfigure`, …) which do not exist in 3.5.x. It would force a
  Spring Boot 4.1 / Spring Framework 7 migration of the entire backend.
- It **deletes `spring-ai-starter-model-openai-sdk`**, which this project depends on, and renames
  `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`.
- Most insidiously, chat-memory advisors move outside the tool-call loop and stop seeing tool
  messages — a silent behavioural change to conversation history, not a compile error.

Verified by diffing tags: `git diff v1.1.4 v1.1.8 -- '*/src/main/java/*bservation*'` is **empty**,
and `AiObservationAttributes` is byte-identical between the two. The 1.1.8 bump is therefore
opportunistic hygiene, not a fix.

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
- On error, record the exception, then end the span. (The originally specified
  `langfuse.observation.level=ERROR` attribute was **dropped** — see §11.)
- Use `doFinally` as a backstop so a span can never leak.
- After the span ends, hand the trace id, verdict, tool-call count and error/empty flags to
  `LangfuseScoreClient`.

Replaces the existing `@WithSpan` annotation on `ChatService.processMessage`, which closes when
the method returns the `Flux` — before any streaming happens.

### 4.2 `LangfuseContentObservationFilter` (new)

`backend/src/main/java/com/simonrowe/observability/LangfuseContentObservationFilter.java`

An `ObservationFilter` bean that copies prompt and completion out of
`ChatModelObservationContext` into high-cardinality key values named
`langfuse.observation.input` and `langfuse.observation.output`.

- Content is serialised to JSON.
- Each value is **truncated to 32 KB**, with a `…[truncated]` marker appended (§7.2).
- Serialisation failures are caught and degrade to omitting the attribute.

Registered in a new `ObservabilityConfig`, guarded by
`langfuse.content-capture.enabled` (default `true`) so capture can be switched off without a
redeploy.

**Tool call content needs almost no code.** Spring AI already ships
`ToolCallingContentObservationFilter` (present in 1.1.4 and unchanged in 1.1.8), auto-configured
behind a property that is `false` by default:

```yaml
spring:
  ai:
    tools:
      observations:
        include-content: true
```

That alone adds `spring.ai.tool.call.arguments` and `spring.ai.tool.call.result` as span
attributes. Langfuse has no native mapping for the `spring.ai.*` prefix, so those land in
observation metadata rather than input/output.

This design originally had the filter **remap** those two key values onto
`langfuse.observation.input`/`.output`, on the assumption that `@Order(LOWEST_PRECEDENCE)` would
place our filter after Spring AI's. **It does not, and the remap was a silent no-op.**
`Ordered.LOWEST_PRECEDENCE` is `Integer.MAX_VALUE`, which is *also* the implicit order of the
unannotated `toolCallingContentObservationFilter` bean, and Boot's
`ObservationRegistryConfigurer.registerFilters` sorts via `ObjectProvider.orderedStream()` — a
*stable* sort, so equal orders fall back to bean-registration order, and component-scanned user
`@Configuration` beans register **before** deferred auto-configuration beans. Our filter ran
first and found nothing to copy.

The shipped filter instead reads tool content straight off `ToolCallingObservationContext` via its
public `getToolCallArguments()` / `getToolCallResult()` accessors, exactly as it already handled
`ChatModelObservationContext`, and carries no `@Order` at all. Registration order is now
irrelevant. **Do not reintroduce an order-dependent remap.**

`spring.ai.tools.observations.include-content` is nevertheless still bound — to
`${LANGFUSE_CONTENT_CAPTURE_ENABLED:true}`, the same switch as `langfuse.content-capture-enabled`.
It must not be hardcoded `true`: Spring AI's filter writes tool arguments as span attributes
independently of ours, Alloy keeps those spans (they carry `spring.ai.kind`), and tool arguments
include `ProfileMcpTools.submitContactForm`'s `firstName`/`lastName`/`email`/`subject`/`message` —
the third-party PII §2.1 singles out. Binding both to one variable is what makes
`LANGFUSE_CONTENT_CAPTURE_ENABLED=false` a genuine, complete off-switch rather than a partial one.

**Expect several chat-model observations per turn.** `OpenAiSdkChatModel.internalStream`
*recurses* after each tool execution, opening a fresh observation per model round-trip. A
tool-using turn therefore produces N generation spans, each carrying the prompt and completion
for *its own* round-trip, not the whole conversation. This is correct and is what Langfuse's
nested view expects — the whole-turn view is the chat-turn span's `langfuse.trace.input`/
`.output`.

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
| `tool-call-count` | `NUMERIC` | tool invocations in the turn, counted by `CountingToolCallingManager` (see the correction below) |
| `error` | `BOOLEAN` | whether the turn terminated in error |
| `empty-answer` | `BOOLEAN` | whether the assembled answer was blank |

Latency is deliberately excluded: Langfuse already records it as a first-class field, so a score
would duplicate it.

Every score also carries `environment`, taken from `LangfuseProperties.getEnvironment()` — the
same value the chat-turn span puts in `langfuse.environment`. Omitting it files the score under
Langfuse's `default` environment while its own trace is tagged `production`, so every
environment-filtered score view and dashboard reads empty.

#### Correction — how `tool-call-count` is actually counted

This design originally specified the count as *"the number of streamed `ChatResponse`s for which
`hasToolCalls()` is true"*, read by `ChatTurnTracer` off the response stream. **That is
impossible, and was replaced during implementation.** `OpenAiSdkChatModel.internalStream` never
emits the aggregated tool-call `ChatResponse` to its subscriber: it consumes that response
internally and replaces it with a *recursive* call into the model with the tool results appended.
A `ChatResponse` with `hasToolCalls() == true` therefore never reaches `ChatService`, and the
counter as designed is unreachable dead code that always reports zero.

The shipped mechanism is `CountingToolCallingManager`, a decorator around Spring AI's
autoconfigured `ToolCallingManager`. `ToolCallingManager.executeToolCalls(Prompt, ChatResponse)`
is the one place upstream that provably sees every tool-execution round — including several
parallel calls within a single round — so the decorator counts there and hands the total to
`ToolCallCounter`, which `ChatTurnTracer` reads at the end of the turn. Counting failures are
swallowed so tool execution can never break on telemetry bookkeeping.

**Do not "fix" this back to reading `hasToolCalls()` off the stream.** It was tried; it is
structurally unreachable, not merely buggy.

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

Shell script in the style of `scripts/verify-langfuse-trace.sh`, reading keys from the environment
or the directory's `.env`, defaulting `LANGFUSE_HOST` to `https://langfuse.simonrowe.dev`.

Two corrections to the original wording of this section (both in §11):

- It is **not** idempotent throughout. Steps 1 and 2 below are re-run safe; step 3 is not, so rule
  creation is gated behind `--create-rules` and skips evaluators that already have a rule.
- An explicitly-set variable must win over `.env`. `.env`'s assignments are unconditional, so
  `set -a; . .env` clobbered a caller-supplied `LANGFUSE_HOST` (also `JUDGE_MODEL`, `SAMPLING`,
  `OPENAI_API_KEY`) — turning the documented local invocation into a run against **production**.
  The script now snapshots caller-supplied values before sourcing and restores them after.

1. `PUT /api/public/llm-connections` — upsert an OpenAI connection keyed on `provider`, using
   `OPENAI_API_KEY`. The judge model must support structured output.
2. `POST /api/public/unstable/evaluators` — create the evaluators. Re-posting an existing `name`
   creates a new version and migrates existing rules to it, so re-runs are safe.
3. `POST /api/public/unstable/evaluation-rules` — one rule per evaluator, `target: observation`,
   `sampling: 0.2`, with a complete variable mapping. **Only with `--create-rules`**, and only for
   evaluators with no existing rule: this endpoint has no upsert, so a bare re-post duplicates
   every rule and multiplies the OpenAI judge spend.

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

### 7.1 Streaming completion capture — wrongly closed as resolved; confirmed open defect

An earlier draft flagged this as the design's main unknown. It was closed early on the strength of
a source reading, which concluded there was no risk, and the planned verification spike was
deleted as unnecessary on that basis. **The end-to-end proof (plan Task 11) disproved it: this is
now a confirmed open defect (Defect A — see §11).** The original reasoning is kept below, unedited,
because the mistake is the useful part of the record: a source reading is not a substitute for
executing the code.

**What was believed:**

`OpenAiSdkChatModel.internalStream` calls `observationContext.setResponse(aggregated)`
*synchronously inside the stream body*, while `.doOnError(observation::error)` and
`.doFinally(s -> observation.stop())` are the outer terminal handlers. The response is therefore
populated **before** the observation stops. Micrometer computes convention key values and applies
`ObservationFilter.map()` in that same `stop()` call, so a filter reading `getResponse()` sees
exactly what the convention sees: the fully aggregated completion.

Spring AI's own `OpenAiChatModelObservationIT.observationForStreamingChatOperation()` corroborates
this — it asserts `gen_ai.usage.*`, `gen_ai.response.id` and `gen_ai.response.finish_reasons` on a
*stopped* streaming observation, and every one of those is derived from `context.getResponse()`.

The conclusion drawn from this reading was "No spike is needed." **That conclusion was wrong.**

**What the end-to-end run actually showed:**

In a real `chat-turn` trace, both `chat gpt-5.4-nano` GENERATION observations carry `input`
populated and `output` null. The non-streaming guardrail generation (`chat gpt-4o-mini`) captures
both input and output correctly, so `LangfuseContentObservationFilter` itself is not at fault —
only the streaming path is affected.

A temporary log statement in the filter (added to diagnose this, since reverted) established why:
at `ObservationFilter` time, the `ChatResponse` on the `ChatModelObservationContext` **is**
present, but has one result with **zero-length text**. `doFinally(observation.stop())` in fact runs
**upstream** of the `MessageAggregator` that assembles the full streamed text and calls
`setResponse` with the complete aggregate — the opposite ordering from what the source reading
concluded. The cited IT doesn't catch this because it asserts usage/id/finish-reason metadata,
none of which requires the aggregated text to be present; it never asserts on the completion text
itself.

**Impact — genuinely limited:** the LLM-as-a-judge evaluators (§4.8) map from `object: "trace"`
fields, not per-generation ones, and trace-level `langfuse.trace.output` **is** correctly
populated by `ChatTurnTracer` — confirmed by the same end-to-end run (§11). So this defect costs
per-generation debugging detail in the Langfuse UI, not evaluator function or Sessions.

**Status: open.** No fix has been attempted. See §11 for the full write-up and the fix direction.

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
| Unit | `LangfuseContentObservationFilter` attribute mapping, including the truncation boundary, a serialisation failure, and tool content read from a real `ToolCallingObservationContext` (§4.2 — asserting against hand-injected `spring.ai.tool.call.*` key values passed while production was broken). |
| Unit | `GuardrailVerdictRegistry` publish/read/evict; entries removed on read. |
| Unit | `GuardrailAdvisor` refactor — existing tests must pass unchanged, plus verdict publication from both the call and stream paths. |
| Unit | `LangfuseScoreClient` against a mocked server: request shape, basic auth, and that a server error never propagates. |
| End-to-end (local) | Full local stack; send a chat message; assert via the local Langfuse API that the trace has a name, session, input and output. |

The OTTL filter cannot be unit-tested. Local parity exists precisely to cover it.

`scripts/verify-langfuse-trace.sh` was to gain `--expect-session` and `--expect-io` flags so
verification is a command with an exit code rather than a visual inspection. **Not delivered** —
see §11.

All backend work must satisfy Checkstyle and the pre-commit hook (see the `backend-test` skill).

## 9. Rollout

Local parity lands first so that every subsequent step is verifiable before it reaches the Pi.

1. Dependency bumps (§2.2): Spring Boot 3.5.16, Spring AI 1.1.8, OTel starter 2.30.0. Land this
   first and on its own, so a regression here is unambiguously separable from the feature work.
2. Local compose → Langfuse v3 plus traces-only Alloy.
3. Backend: `ChatTurnTracer`, content filter, `spring.ai.tools.observations.include-content`,
   `GuardrailVerdictRegistry`, `GuardrailAdvisor` refactor, `LangfuseScoreClient`.
4. Alloy keep-list, in both `config.alloy` and `config.local.alloy`.
5. Verify end-to-end locally; extend `verify-langfuse-trace.sh`.
6. Documentation corrections.
7. Merge → the Publish workflow builds images → deploy to the Pi.
8. **On the Pi**, as a single copy-paste block: pull the deploy dir, delete the Langfuse project
   in the UI, restart `langfuse` (bootstrap recreates it with the same keys), restart `alloy`.
9. Run `bootstrap-langfuse-evaluators.sh` against prod, then **once** with `--create-rules`
   (rule creation is opt-in because `POST /evaluation-rules` has no upsert — see §11).
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

## 11. Delivered differently / not delivered

Recorded rather than silently edited away, so that a later reader does not "restore" a design
decision that was tried and found broken. Each entry says what this document originally specified,
what shipped, and why.

### Delivered differently

| Originally specified | What shipped | Why |
| --- | --- | --- |
| §4.5 — `tool-call-count` counted by `ChatTurnTracer` from streamed `ChatResponse`s with `hasToolCalls() == true` | `CountingToolCallingManager`, a `ToolCallingManager` decorator counting at `executeToolCalls` | `OpenAiSdkChatModel.internalStream` consumes the tool-call response internally and replaces it with a recursion, so `hasToolCalls()` is never true at the subscriber. The design was unreachable, not merely buggy. Detail in §4.5. |
| §4.2 — tool content obtained by remapping Spring AI's `spring.ai.tool.call.*` key values, ordered with `@Order(LOWEST_PRECEDENCE)` | Tool content read directly from `ToolCallingObservationContext.getToolCallArguments()` / `getToolCallResult()`; no `@Order` at all | `LOWEST_PRECEDENCE` equals Spring AI's own implicit order, and Boot's stable ordered sort then puts our component-scanned bean first, so the remap always found nothing. Detail in §4.2. |
| §4.8 — the bootstrap script described as idempotent throughout | Only the LLM connection and the evaluators are re-run safe; evaluation **rules** are opt-in behind `--create-rules`, plus a best-effort existence check | `POST /api/public/unstable/evaluation-rules` has no upsert: re-running duplicated all four rules, multiplying the per-trace OpenAI judge spend that `sampling: 0.2` exists to control. The failure mode was a bill, not an error. |

### Dropped

- **§4.1's `langfuse.observation.level=ERROR` attribute.** Not set. `ChatTurnTracer` records the
  exception on the observation and lets the Micrometer → OTel bridge set the span status to
  `ERROR`, which Langfuse already surfaces. An explicit attribute would have been a second,
  independently-maintained source of the same truth.

### Not delivered

- **§8's `scripts/verify-langfuse-trace.sh --expect-session` / `--expect-io` flags.** The task was
  blocked: the local Docker environment was wedged (VM inconsistent after disk exhaustion), so the
  flags could not be written against a running Langfuse, let alone verified. Production must not be
  used for this — the evaluator bootstrap creates real resources and incurs real OpenAI spend.
  End-to-end trace verification therefore remains a manual UI inspection, as it was before this
  branch. This is outstanding work, not a decision.

### Known open defects (found by the Task 11 end-to-end proof)

Recorded here, not minimized or deleted, because the branch must not ship a spec asserting a
defect is resolved when it is not. Both were found by actually running the stack end to end —
neither was visible from source reading alone, which is itself the lesson of §7.1.

**Defect A — streaming generation output is never captured.** In a `chat-turn` trace, both
`chat gpt-5.4-nano` GENERATION observations show `input` present and `output` null. The
non-streaming guardrail generation (`chat gpt-4o-mini`) captures both correctly, so
`LangfuseContentObservationFilter` itself works — only the streaming path loses output. Root
cause, confirmed with a temporary (since reverted) log statement: at `ObservationFilter` time the
`ChatResponse` on the context has one result but zero-length text, because
`doFinally(observation.stop())` runs upstream of the `MessageAggregator` that assembles the full
streamed text and calls `setResponse`. Full account, including the wrong source-reading reasoning
that originally closed this as resolved, is in §7.1. **Impact is limited**: the LLM-as-a-judge
evaluators map from `object: "trace"` fields, and trace-level `langfuse.trace.output` is correctly
populated by `ChatTurnTracer`, so this costs per-generation debugging detail, not evaluator
function or Sessions. Status: open, no fix attempted.

**Defect B — span orphaning.** One chat turn produces roughly 6–7 Langfuse traces instead of one:
measured 19 traces from 3 chat turns (16 orphans + 3 `chat-turn`). `tool_call`, `embedding` and
the vector-store span (`elasticsearch query`, which carries `spring.ai.kind=vector_store` — see
the correction below) lose the observation context across `Schedulers.boundedElastic` and become
their own root traces with `sessionId` null. The `chat-turn` trace itself correctly nests all 8
observations that stay on the synchronous part of the chain (`spring_ai chat_client`, `guardrail`,
the memory and QA advisors, and both generations), so Reactor-context propagation works throughout
the synchronous path and is lost only where Spring AI hops schedulers. **Impact:** cosmetic
clutter in the Langfuse UI, and the orphaned traces carry no session; Sessions itself is
unaffected, because the `chat-turn` trace is correctly grouped. **The real fix is restoring
observation context across `Schedulers.boundedElastic`**, not filtering by trace name in tooling —
a name filter would hide the orphans from view without un-orphaning them or giving them a session.
Status: open, no fix attempted.

**Defect B follow-up — the OTel baggage mechanism from PR #81 does not work.** PR #81 attempted to
tag the Defect B orphans by putting `langfuse.session.id` into OTel baggage in
`ChatService.processMessage` and relying on
`OTEL_JAVA_EXPERIMENTAL_SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE` to copy it onto every span it
was current for. It was merged into this branch and kept on the theory it might reach the orphans
the tracer cannot. It does not: testing showed `langfuse.session.id` on zero of 16 spans from a
real chat turn. Root cause — that env var is an OpenTelemetry **Java agent** property; this backend
runs `opentelemetry-spring-boot-starter`, which does not implement it (`BaggageSpanProcessor` is on
the classpath but nothing registers it). A secondary, unrefuted obstacle: the baggage `Scope` closed
in a `try-with-resources` around `processMessage` anyway, while the orphaned spans are created later
at subscription time, after the method has already returned. The mechanism has been removed. The
only approach that survives the `Schedulers.boundedElastic` hop is restoring the observation context
there, which is the real Defect B fix above — it also makes the orphans nest under `chat-turn`
instead of becoming root traces, which removes any need to tag them with a session at all.

**Correction — `elasticsearch query` is not noise.** The plan's Task 11 end-to-end verification
step treated `elasticsearch query` as noise the `ai_only` filter should drop, in the same category
as `security filterchain` and `http get` (§1.1's "All time" row lists all three together as
historical pre-fix noise, but that row is about traces from before the `ai_only` filter existed at
all — it does not claim `elasticsearch query` should disappear afterwards). **The Task 11
expectation was wrong.** `elasticsearch query` is Spring AI's vector-store span, carrying
`spring.ai.kind=vector_store`, and the `ai_only` keep-list (§4.6) keeps anything carrying
`spring.ai.kind` — correctly. The filter was never broken here; the expected-trace-name list used
to verify it was. `elasticsearch query` traces are expected and correct; their appearance as their
own root traces is Defect B (orphaning), not an `ai_only` regression. (The plan document has been
corrected to match.)

### Confirmed working (the same end-to-end proof)

So the above reads as a balanced account rather than a list of failures — the following was
verified by actually sending a chat message through the local stack, not merely designed:

- The `chat-turn` trace has a name, a non-null `sessionId`, and both `langfuse.trace.input` and
  `langfuse.trace.output` populated.
- All four deterministic scores land on the trace: `guardrail=SAFE`, `tool-call-count=4`
  (non-zero, proving `CountingToolCallingManager` counts real tool rounds rather than reporting the
  dead-code zero described in §4.5), `error=False`, `empty-answer=False`.
- Tool-call observations carry both input and output.
- Generation `input` is captured correctly — Defect A above is an output-only loss.
- No `security filterchain` or `http get` trace noise reached Langfuse: the `ai_only` fix in §1.1
  holds under real traffic.
