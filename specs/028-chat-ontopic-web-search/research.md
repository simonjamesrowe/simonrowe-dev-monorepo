# Research: Chat on-topic guardrail + live web search

**Feature**: 028-chat-ontopic-web-search | **Date**: 2026-07-17

All Technical Context items were resolvable from the approved design doc and the existing
codebase; no open NEEDS CLARIFICATION remained. This file records the decisions and rationale.

## R1. Guardrail mechanism — keep classifier, make it domain-aware

- **Decision**: Keep the pre-call `gpt-4o-mini` classifier in `GuardrailAdvisor`
  (`getOrder() == 0`, temp 0, fail-open). Extract the classification prompt — currently
  duplicated verbatim in `adviseCall` and `adviseStream` — into one shared `private static
  final` constant/helper, and rewrite it to describe the allowed domain (boundary A) with a
  SAFE bias.
- **Rationale**: The root cause is a domain-blind prompt, not the gate architecture. The
  minimal, lowest-risk fix is to change the prompt, not remove the classifier (removing it is
  explicitly out of scope). Extracting the constant removes the drift risk between the call and
  stream paths (FR-008).
- **Alternatives considered**: (a) Remove the classifier entirely and rely on the system
  prompt — rejected, out of scope and loses the injection/harmful gate. (b) Pass conversation
  history into the classifier — deferred; SAFE-bias wording covers follow-ups without the extra
  complexity and token cost.

## R2. Classification categories and SAFE bias

- **Decision**: Three labels retained — `SAFE` / `OFF_TOPIC` / `HARMFUL`. Deflection still
  fires on `OFF_TOPIC`/`HARMFUL` with the unchanged canned message. Prompt spells out:
  - SAFE = Simon (career, bio, background, contact); blogs, skills, jobs/companies, code;
    aggregated tech/AI/Spring news and community events; general questions about technologies,
    companies, or people connected to his work ("what is Kafka", "tell me about <a company he
    worked at>"); greetings; meta ("who are you", "what can you do"); short follow-ups
    ("I don't think you answered that").
  - OFF_TOPIC = clearly unrelated (weather, cooking, "write my essay", general life advice).
  - HARMFUL = jailbreak / prompt-injection / malicious / hateful.
  - "Bias to SAFE when uncertain. Only block the obvious cases."
- **Rationale**: Matches the design's boundary A and directly addresses the observed false
  refusals while preserving the deflection contract (FR-001..FR-005, FR-007).
- **Alternatives considered**: Binary SAFE/BLOCK — rejected; loses the harmful-vs-off-topic
  distinction, no behavioural benefit, larger diff.

## R3. Web search mechanism — self-hosted SearXNG via `@Tool` + `RestClient`

- **Decision**: New `WebSearchTools` `@Tool` component exposing `webSearch(query, toolContext)`,
  backed by a `SearxngClient` bean using Spring `RestClient` GETting a **self-hosted SearXNG**
  instance's JSON API (`{base-url}/search?q=...&format=json`). Register alongside
  `ProfileMcpTools`: `.defaultTools(profileMcpTools, webSearchTools)`. The model decides when to
  call it. SearXNG runs as an internal-only container in `docker-compose.prod.yml`.
- **Rationale**: `@Tool` is the established chat-tool pattern (`ProfileMcpTools`). SearXNG is a
  free, open-source metasearch engine that self-hosts inside the existing Docker Compose stack —
  **no external account, no API key, no per-call cost** — and returns clean `{title, url,
  content}` results suitable for inline citation. `RestClient` is the modern Spring HTTP client;
  a dedicated bean keeps it mockable in tests (parallels `RestTemplate` in `RecaptchaService`).
- **Alternatives considered**: (a) **Tavily** (free tier ~1k/mo) — purpose-built for LLMs but
  requires an external signup + API key and a metered quota; rejected in favour of a
  zero-account, self-hosted option. (b) **Brave Search API** (free ~2k/mo) — good quality but
  still an external account + key. (c) OpenAI native web-search tool — not part of the current
  Spring AI 1.1.4 tool wiring and less controllable/citable. (d) `RestTemplate` — works, but
  `RestClient` is the current idiom and gives a clean per-client timeout config. The provider is
  isolated behind `SearxngClient`, so swapping to Tavily/Brave later is a one-class change.

## R4. Package placement

- **Decision**: `WebSearchTools` in `com.simonrowe.chat` (next to `ChatConfig`; it publishes via
  `ChatStreamPublisher` and is a chat tool). `SearxngClient` + `WebSearchResult` in a new
  `com.simonrowe.websearch` package.
- **Rationale**: Keeps the already-large chat package focused on the tool surface while isolating
  the external HTTP client and its DTO in their own package for testing and reuse. Consistent
  with the design's "kept separate from the already-large `ProfileMcpTools`".
- **Alternatives considered**: Everything in `chat` — acceptable, but mixes the raw HTTP client
  with chat wiring. Everything in a new package — the tool needs `ChatStreamPublisher` and sits
  naturally beside the other chat tools, so the tool stays in `chat`.

## R5. Request/response shape and bounds

- **Decision**: `GET {base-url}/search?q={query}&format=json`. Map the response `results[]` to
  `WebSearchResult { title, url, snippet }` (snippet from SearXNG's `content`), skipping entries
  with blank `title`/`url` and capping client-side at `max-results` (default 5). Short
  connect/read timeout (~5s). SearXNG requires the JSON output format to be enabled in its
  `settings.yml` (`search.formats: [html, json]`).
- **Rationale**: Matches SearXNG's JSON API; small result cap bounds prompt size/latency.
- **Alternatives considered**: POST to `/search` — GET with query params is simpler and the
  documented JSON usage. Requesting more results — unnecessary for inline citations.

## R6. Graceful degradation and empty-query handling

- **Decision**: If `SEARXNG_URL` is blank/unset → the tool returns a short
  "web search is unavailable" string and logs a warning (no external call). If the query is
  null/blank → return an empty result without calling SearXNG. Any `RestClient`/timeout
  exception (e.g. the SearXNG container is down) is caught, logged at warn, and converted to the
  same "unavailable" string. The tool never throws.
- **Rationale**: FR-013/FR-014; keeps the overall answer working even when web search or the
  SearXNG container is unavailable, and avoids wasted calls. Mirrors `ProfileMcpTools`
  catch-and-return-empty style. Because the backend degrades gracefully, it needs no hard
  `depends_on: searxng` and there is no nginx-upstream coupling (SearXNG is not fronted by
  nginx).
- **Alternatives considered**: Throwing and letting the model retry — rejected; risks breaking
  the whole answer and burning tokens.

## R7. Streaming tool labels

- **Decision**: Publish `toolStart(sessionId, "Searching the web")` / `toolEnd(...)` via
  `ChatStreamPublisher`, reading `sessionId` from `ToolContext` exactly like `ProfileMcpTools`
  (null-safe; skip publishing when sessionId is absent). No widget payload — results are cited
  inline as markdown links only.
- **Rationale**: FR-011/FR-012; consistent UX with existing tools; zero frontend changes.
- **Alternatives considered**: New "web results" widget — rejected, explicitly out of scope.

## R8. Config & env

- **Decision**: `application.yml` gains
  `web-search.searxng.base-url: ${SEARXNG_URL:}` and `web-search.searxng.max-results: 5`.
  Add `SEARXNG_URL` (blank default) to `backend/.env`; set `SEARXNG_URL: http://searxng:8080` in
  the backend service env in `docker-compose.prod.yml`, and add the internal `searxng` service +
  `config/searxng/settings.yml`. System prompt gains a `webSearch` tool line + a nudge to use
  `getRecentBlogs`/`searchNews` for "what's he writing about lately" / "what's new in Spring".
- **Rationale**: FR-015; blank default means web search ships inert locally until a SearXNG URL
  is provided, while prod points at the in-cluster container. Externalised config matches project
  conventions. `SearxngClient` reads config via `@Value` (matches `RecaptchaService`).
- **SearXNG settings**: `settings.yml` enables the JSON format and sets `server.limiter: false`
  and `public_instance: false` — the instance is internal-only (backend-to-container), so the
  browser-oriented bot limiter would otherwise block programmatic queries. `secret_key` uses the
  image's `ultrasecretkey` placeholder, substituted from `SEARXNG_SECRET` at startup (so the
  mount is not read-only).
- **Alternatives considered**: Read-only settings mount with a hardcoded secret — rejected; the
  entrypoint needs write access to substitute the secret, and committing a signing key is worse.

## R9. Testing approach

- **Decision**: `GuardrailAdvisorTest` — mock `ChatModel`; assert SAFE proceeds to chain,
  OFF_TOPIC/HARMFUL returns the canned deflection (both call and stream), fail-open on
  null/exception, and that the classification prompt now contains the domain description.
  `WebSearchToolsTest` — mock `SearxngClient`; assert result mapping, empty/blank-query handling,
  unavailable-when-unconfigured, graceful failure when the client throws, and that missing
  sessionId skips tool labels. `../gradlew test` passes.
- **Rationale**: FR unit-level coverage; `SearxngClient` is an external HTTP client mocked with
  Mockito (no Testcontainer needed per Principle III).
- **Alternatives considered**: Integration test hitting a stub HTTP server — heavier than needed
  for the mapping/degradation logic; unit tests with a mocked client are sufficient.
