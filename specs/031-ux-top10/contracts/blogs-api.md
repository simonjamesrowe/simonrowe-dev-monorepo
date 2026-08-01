# Contract: Blogs API

Base: `https://api.simonrowe.dev` (prod) / `http://localhost:8080` (local).
All three endpoints are `permitAll()` per `auth/SecurityConfig.java:26-30`.

**Change summary**: one new field on every blog payload, one new optional query
parameter on `/latest`. No breaking change — existing clients keep working.

---

## `GET /api/blogs`

List every published post, newest first. Returns a plain JSON array (not a
`Page`). Unchanged except for the new field.

**Response** `200` — `BlogSummaryResponse[]`

```json
[
  {
    "id": "6612f0a1c3d4e5f60718293a",
    "title": "Event sourcing without the ceremony",
    "shortDescription": "What we kept and what we threw away.",
    "featuredImageUrl": "/uploads/8f2c.../8f2c..._medium.jpg",
    "createdDate": "2026-07-14T09:12:00Z",
    "tags": [{ "name": "Kafka" }],
    "skills": [{ "id": "651a...", "name": "Apache Kafka" }],
    "url": "/blogs/6612f0a1c3d4e5f60718293a",
    "contentType": "ENGINEERING"
  }
]
```

| Field | Type | Notes |
|---|---|---|
| `contentType` | `"ENGINEERING" \| "DIGEST"` | **NEW.** Never `null` — the DTO coerces a missing stored value to `ENGINEERING` |

---

## `GET /api/blogs/latest`

**Query parameters**

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `limit` | integer | no | `3` | `@Min(1) @Max(10)` — unchanged |
| `contentType` | `ENGINEERING` \| `DIGEST` | no | — | **NEW.** Absent → no filtering (current behaviour) |

**Response** `200` — `BlogSummaryResponse[]`, same shape as above, at most
`limit` items, newest first, filtered to `contentType` when supplied.

**Examples**

```http
GET /api/blogs/latest?limit=3&contentType=ENGINEERING
GET /api/blogs/latest?limit=3
```

The first is what the home page's Featured writing section calls.

**Errors**

| Status | When |
|---|---|
| `400` | `limit` outside 1–10, or `contentType` not one of the two enum names |

Filtering happens after the existing in-memory limit logic is reordered so the
limit applies to the *filtered* list — otherwise asking for 3 engineering posts
could return fewer when digests occupy the top of the list. This reordering is the
substantive behavioural detail of the change.

**Backing query**: `BlogRepository.findByPublishedTrueOrderByCreatedDateDesc()`
(unchanged) then filter + limit in `BlogService.getLatest`. At ~43 posts this is
cheaper than a second derived query and avoids a new repository method.

---

## `GET /api/blogs/{id}`

**Response** `200` — `BlogDetailResponse`: the summary fields plus `content`,
minus `url`, plus the new `contentType`.

**Errors** — `404` when the id is unknown or the post is unpublished (unchanged,
`BlogService.java:32-40`).

---

## Contract tests (backend)

`backend/src/test/java/com/simonrowe/blog/BlogControllerTest.java`, extending
`AbstractIntegrationTest`:

| Test | Asserts |
|---|---|
| list includes contentType | `jsonPath("$[0].contentType")` is `ENGINEERING` or `DIGEST` |
| stored null coerces | a post saved with `contentType = null` serializes as `ENGINEERING` |
| latest filters | 2 digests newer than 3 engineering posts; `?limit=3&contentType=ENGINEERING` returns exactly the 3 engineering posts |
| latest unfiltered unchanged | `?limit=3` returns the 3 newest of any type |
| bad enum rejected | `?contentType=NONSENSE` → `400` |
| limit bounds unchanged | `?limit=0` → `400`, `?limit=11` → `400` |
| detail includes contentType | `jsonPath("$.contentType")` present |
