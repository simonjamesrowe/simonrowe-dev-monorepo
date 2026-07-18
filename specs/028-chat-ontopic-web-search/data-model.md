# Data Model: Chat on-topic guardrail + live web search

**Feature**: 028-chat-ontopic-web-search | **Date**: 2026-07-17

No persistent storage changes (no MongoDB collections, no Elasticsearch indices). The entities
below are in-memory / transport types only.

## Entity: Classification (guardrail)

Ephemeral label produced by the pre-call classifier for a single user message. Not persisted.

| Field | Type | Notes |
|-------|------|-------|
| value | enum-like string | One of `SAFE`, `OFF_TOPIC`, `HARMFUL` (matched via `contains`, upper-cased) |

- **Behaviour**: `SAFE` (or unparseable / empty / error) → proceed down the advisor chain
  (fail-open). `OFF_TOPIC` or `HARMFUL` → short-circuit to the canned deflection message.
- **Bias rule**: When uncertain, classifier is instructed to emit `SAFE`.
- **No state transitions** — computed fresh per message.

## Entity: WebSearchResult

Transport record returned by `SearxngClient` and consumed by `WebSearchTools`, then serialized
into the tool result the model reads.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| title | String | yes | Result title |
| url | String | yes | Source URL — cited inline as a markdown link |
| snippet | String | yes | Short excerpt (SearXNG `content`) |

- **Validation**: Results with a null/blank `url` or `title` are skipped when mapping.
- **Cardinality**: 0..`max-results` (default 5) per search.

## Entity: WebSearchConfiguration

Externalised settings that enable and bound web search.

| Setting | Source | Default | Notes |
|---------|--------|---------|-------|
| base-url | `web-search.searxng.base-url` ← `${SEARXNG_URL:}` | empty | Blank ⇒ web search disabled/unavailable; prod = `http://searxng:8080` |
| max-results | `web-search.searxng.max-results` | 5 | Upper bound on results per query (applied client-side) |

- **Derived state**: `configured = base-url is non-blank`. When not configured, `webSearch`
  returns the "unavailable" message and performs no external call.

## Tool surface (no schema change, listed for completeness)

- `webSearch(query, toolContext)` — added to the chat client's `defaultTools` alongside the
  existing `ProfileMcpTools` tools. Returns `List<WebSearchResult>` (or a short string on
  unavailable/blank/failure). Publishes `toolStart`/`toolEnd` labels "Searching the web".
