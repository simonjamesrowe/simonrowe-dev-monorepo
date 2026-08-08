# Contract: News API

**Change summary**: one new endpoint. `GET /api/news` is **unchanged** — the
frontend simply starts using the `page`/`size`/`source` parameters it already
accepts.

---

## `GET /api/news/sources` — NEW

> **Superseded by `2026-08-08-ai4jvm-ingest-and-source-pills`.** The response shape
> below (`string[]`) is historical: the endpoint now returns
> `[{ "name": string, "count": number }]`, sorted by count descending then name
> ascending, so the news page can collapse low-volume sources behind a "More"
> overflow. The rest of this contract — visible-articles-only, never `null`/`404`,
> and the `/{id}` route-ordering note — still holds.

The complete set of distinct article source names, so filter chips can list every
source the site holds rather than only those in the first page of results
(FR-037, FR-039).

**Query parameters**: none.

**Response** `200` — `string[]`, alphabetically sorted, distinct, visible articles
only.

```json
["Ars Technica", "Claude Blog", "Dan Vega", "InfoQ", "The Pragmatic Engineer"]
```

**Empty case**: `[]` — never `null`, never `404`.

**Route ordering caution**: `NewsController` already has
`@GetMapping("/{id}")` (`:47`). Spring matches the literal `/sources` mapping
ahead of the `{id}` template, so no reordering is required — but a test asserting
`/api/news/sources` does not fall through to the by-id handler is worth having,
because that failure mode would surface as a confusing `404`.

**Implementation**: `MongoTemplate.findDistinct("sourceName", query(where("visible").is(true)),
AggregatedArticle.class, String.class)`, then sort. Spring Data has no derived
projection for a distinct scalar, so a `MongoTemplate` call is the direct route
rather than adding an aggregation pipeline.

`AggregatedArticle.sourceName` is the source field (`aggregation/AggregatedArticle.java`);
`sourceUrl` is a different field and is not exposed here.

---

## `GET /api/news` — unchanged, newly exercised

| Name | Type | Default | Notes |
|---|---|---|---|
| `page` | integer | `0` | Now actually used by the frontend |
| `size` | integer | `20` | Frontend passes `24` |
| `source` | string | — | Exact match on `sourceName` |

**Response** `200` — a Spring `Page<ArticleResponse>` serialized directly:

```json
{
  "content": [ { "id": "...", "title": "...", "sourceName": "InfoQ", "originalUrl": "...",
                 "summary": "...", "author": null, "publishedDate": "2026-07-28T00:00:00Z",
                 "fetchedAt": "2026-07-28T04:00:11Z", "visible": true, "imageUrl": "/uploads/..." } ],
  "totalElements": 412,
  "totalPages": 18,
  "number": 0,
  "size": 24,
  "first": true,
  "last": false,
  "numberOfElements": 24,
  "empty": false
}
```

The frontend needs `content`, `number` and `last`. `last` is what hides the
"Load more" button (FR-038) — no client-side arithmetic on `totalPages` required.

---

## Frontend service change

`frontend/src/services/newsApi.ts`:

```ts
// existing, unchanged signature — now called with real paging arguments
fetchNews(page = 0, size = 20, source?: string): Promise<Page<Article>>

// NEW
fetchNewsSources(): Promise<string[]>
```

Both route through `fetchWithRetry` (see `research.md` D2).

---

## Contract tests

**Backend** — `backend/src/test/java/com/simonrowe/aggregation/NewsControllerTest.java`,
extending `AbstractIntegrationTest`:

| Test | Asserts |
|---|---|
| distinct sources | 5 articles across 3 sources (one duplicated) → 3 names |
| sorted | response is alphabetical |
| hidden excluded | an article with `visible = false` contributes no source |
| empty | no articles → `[]`, status `200` |
| not shadowed by `/{id}` | `GET /api/news/sources` returns an array, not `404` |

**Frontend** — `frontend/tests/pages/NewsEventsPage.test.tsx`:

| Test | Asserts |
|---|---|
| initial page size | `fetchNews` called with `(0, 24, undefined)` |
| load more appends | second page's articles are added below the first page's, both still rendered |
| button hidden on last page | `last: true` → no "Load more" |
| chip re-queries | selecting a source calls `fetchNews(0, 24, 'InfoQ')`, not an in-memory filter |
| chips from endpoint | chips render source names returned by `fetchNewsSources` that are absent from page 0's articles |
| stale response discarded | a slow page-2 response arriving after a source switch does not append |
| favourites unaffected | toggling favourites behaves as before (FR-040) |
