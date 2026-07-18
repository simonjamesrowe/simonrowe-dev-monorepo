# Implementation Plan: Chat — reliable on-topic answers + live web search

**Branch**: `028-chat-ontopic-web-search` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/028-chat-ontopic-web-search/spec.md`

## Summary

Fix the chat assistant's intermittent false refusals and add live web search. Two changes,
both backend-only:

1. **Domain-aware guardrail** — keep the existing pre-call `gpt-4o-mini` classifier in
   `GuardrailAdvisor` (order 0, temp 0, fail-open), but extract its duplicated prompt into a
   single shared constant and rewrite it to describe the site's allowed topics (Simon, blogs,
   news, events, skills, jobs/companies, code, greetings, meta, follow-ups) and bias to SAFE.
   Deflection message and plumbing unchanged.
2. **Live web search** — a new `WebSearchTools` `@Tool` component backed by a `SearxngClient`
   (Spring `RestClient`) that queries a **self-hosted SearXNG** container (no API key, no
   per-call cost), registered alongside `ProfileMcpTools` in `ChatConfig` and surfaced via the
   existing `ChatStreamPublisher` tool-start/end labels. Results are cited inline as markdown
   links (no frontend changes). Graceful degradation when `SEARXNG_URL` is unset or the call
   fails. System prompt gains a `webSearch` entry with a Simon-grounded usage rule. A `searxng`
   service is added to `docker-compose.prod.yml` (internal-only, no published ports) with a
   `config/searxng/settings.yml` that enables the JSON format and disables the bot limiter.

## Technical Context

**Language/Version**: Java 21 (backend only)

**Primary Dependencies**: Spring Boot 3.5.x, Spring AI 1.1.4 (OpenAI SDK starter + `@Tool`),
Spring `RestClient`, existing `ChatStreamPublisher` (STOMP/WebSocket), OpenTelemetry annotations

**Storage**: None new. No MongoDB / Elasticsearch schema changes.

**Testing**: JUnit 5 + Mockito (`@MockitoBean`), existing `GuardrailAdvisorTest` /
`ChatConfigPromptTest` patterns; `../gradlew test`

**Target Platform**: Linux server container (GraalVM native image via `bootBuildImage`)

**Project Type**: Web application (backend + frontend); this feature touches **backend only**

**Performance Goals**: Web search bounded by short timeout (~5s) and small result count (~5);
no added latency when the model does not call the tool. Guardrail adds one existing-shape
classification call (unchanged from today).

**Constraints**: Fail-open guardrail; graceful web-search degradation (never throws); zero
frontend changes; no new LLM provider SDK; `RestClient` must be a separate mockable bean.

**Scale/Scope**: Single portfolio site chat assistant; low concurrency. Scope: 2 new backend
classes (`WebSearchTools`, `SearxngClient`), edits to `GuardrailAdvisor`, `ChatConfig`,
`application.yml`, env files, and 2 test classes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle II (Modern Java & React Stack)**: ✅ Uses Spring AI 1.1.4 + OpenAI (no new LLM
  provider SDK). `@Tool` component pattern matches existing `ProfileMcpTools`. Web search is an
  external HTTP data source via Spring `RestClient`, not a new framework. Classifier keeps
  `gpt-4o-mini` at temp 0 as today.
- **Principle III (Quality Gates)**: ✅ Google Java Style; new/updated unit tests
  (`WebSearchToolsTest`, `GuardrailAdvisorTest`); `SearxngClient` mocked via Mockito in the tool
  test — it is an external HTTP client, not Testcontainer-backed infrastructure, so no container
  is required. Existing integration tests keep passing.
- **Principle IV (Observability)**: ✅ Reuse `@WithSpan` on the tool and structured logging
  (warn on unavailable/failed web search), consistent with `ProfileMcpTools`.
- **Principle V (Simplicity & Incremental Delivery)**: ✅ Two independently testable increments
  (guardrail fix P1, web search P2). No premature abstraction — `SearxngClient` is a thin,
  single-purpose bean introduced because it is a concrete external dependency and needs to be
  mockable. No persistence added (nothing to query/store). The `searxng` container is an
  internal infra service (like `portainer`/`alloy`), keeping web search self-hosted, free, and
  dependency-light — no external account or API key.

No violations. Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/028-chat-ontopic-web-search/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── searxng-search-api.md
│   └── web-search-tool.md
├── checklists/
│   └── requirements.md  # created by /speckit.specify
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/chat/
│   ├── GuardrailAdvisor.java          # EDIT: extract shared prompt constant, rewrite domain-aware
│   ├── ChatConfig.java                # EDIT: register webSearchTools in .defaultTools(...)
│   └── WebSearchTools.java            # NEW: @Tool component (webSearch), publishes tool labels
├── src/main/java/com/simonrowe/websearch/
│   ├── SearxngClient.java             # NEW: RestClient GET SearXNG /search?format=json, timeout, graceful
│   └── WebSearchResult.java          # NEW: record {title, url, snippet}
├── src/main/resources/
│   └── application.yml                # EDIT: web-search.searxng.* block; system-prompt webSearch line
└── src/test/java/com/simonrowe/
    ├── chat/GuardrailAdvisorTest.java # EDIT: SAFE proceeds, OFF_TOPIC/HARMFUL deflect, prompt asserts
    └── websearch/WebSearchToolsTest.java  # NEW: mock SearxngClient, mapping, blank, graceful failure

config/searxng/settings.yml           # NEW: SearXNG config (JSON format on, limiter off)
docker-compose.prod.yml               # EDIT: add internal `searxng` service; backend SEARXNG_URL env
backend/.env                          # EDIT: SEARXNG_URL= (blank default)
```

**Structure Decision**: Web application layout. Code changes are in the existing `backend/`
Spring Boot module. `WebSearchTools` lives in the `chat` package next to `ChatConfig` (it is a
chat tool and publishes via `ChatStreamPublisher`); `SearxngClient` + `WebSearchResult` live in
a small dedicated `com.simonrowe.websearch` package to keep the HTTP client separable and
mockable. Infra: a new internal-only `searxng` service in `docker-compose.prod.yml` plus
`config/searxng/settings.yml`. No frontend module changes.

## Complexity Tracking

> No constitution violations — table intentionally omitted.
