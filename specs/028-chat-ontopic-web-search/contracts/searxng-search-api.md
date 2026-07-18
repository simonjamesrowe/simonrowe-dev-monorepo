# Contract: SearXNG Search API (self-hosted, internal)

**Consumer**: `SearxngClient` (Spring `RestClient`)
**Endpoint**: `GET {base-url}/search?q={query}&format=json`
**Base URL**: `web-search.searxng.base-url` / `SEARXNG_URL` (prod: `http://searxng:8080`)
**Auth**: none — internal-only container, not exposed publicly and not an nginx upstream.

## Request

```
GET http://searxng:8080/search?q=recent%20news%20about%20a%20company%20he%20worked%20at&format=json
```

| Param | Value |
|-------|-------|
| q | non-blank query (URL-encoded by `RestClient`) |
| format | `json` (must be enabled in SearXNG `settings.yml`: `search.formats: [html, json]`) |

Client-side, results are capped at `web-search.searxng.max-results` (default 5).

## Response body (subset consumed)

```json
{
  "query": "...",
  "results": [
    { "title": "…", "url": "https://…", "content": "short excerpt …" }
  ]
}
```

Mapping → `WebSearchResult`: `title ← results[].title`, `url ← results[].url`,
`snippet ← results[].content`. Entries with blank `title`/`url` are skipped; the list is
truncated to `max-results`. Fields beyond these are ignored.

## SearXNG instance requirements (`config/searxng/settings.yml`)

- `search.formats` includes `json` (JSON API is disabled by default).
- `server.limiter: false` and `server.public_instance: false` — server-to-server calls have no
  browser fingerprint, so the bot limiter must be off for the internal instance.
- `secret_key` provided via the `SEARXNG_SECRET` env substitution.

## Failure & timeout behaviour (client contract)

- Short connect/read timeout (~5s). On timeout, non-2xx, the container being down, or an
  unparseable body, `SearxngClient` surfaces the failure to `WebSearchTools`, which logs a
  warning and returns the "web search is unavailable" message. **No exception propagates to the
  chat flow.**
- When `base-url` is blank, `SearxngClient.isConfigured()` is false and it is not invoked at all
  (short-circuited by the tool). Because of this graceful degradation the backend needs no hard
  `depends_on: searxng`.
