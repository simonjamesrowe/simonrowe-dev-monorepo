# Contract: Admin Blogs API

Base path `/api/admin/blogs`. Requires `hasRole("DEV_PORTAL_ADMIN")`
(`auth/SecurityConfig.java:26-30`).

**Change summary**: one new field on the DTO and on accepted request bodies.
Backwards compatible — omitting it yields `ENGINEERING`.

`AdminBlogController` has **no DTO record**; it hand-builds a
`LinkedHashMap<String, Object>` in `toDto` (`:160-177`) and accepts raw
`Map<String, Object>` bodies. The change follows that existing shape rather than
introducing a record for one field.

---

## `GET /api/admin/blogs`

Unchanged parameters (`page=0`, `size=20`, optional `published`), returns
`Page<Map<String,Object>>`. Each item gains `contentType`.

```json
{
  "id": "6612f0a1c3d4e5f60718293a",
  "title": "Event sourcing without the ceremony",
  "shortDescription": "...",
  "content": "...",
  "published": true,
  "featuredImageUrl": "/uploads/8f2c.../original.jpg",
  "tags": ["651a...", "651b..."],
  "skills": ["661c..."],
  "createdAt": "2026-07-14T09:12:00Z",
  "updatedAt": "2026-07-14T09:12:00Z",
  "contentType": "ENGINEERING"
}
```

`tags` and `skills` remain arrays of **string ids** (the established
`@DBRef` ↔ id DTO pattern, Constitution VI).

---

## `POST /api/admin/blogs` · `PUT /api/admin/blogs/{id}`

**Request body** — one new optional key:

| Key | Type | Required | Behaviour |
|---|---|---|---|
| `contentType` | `"ENGINEERING"` \| `"DIGEST"` | no | Absent, `null`, or blank → `ENGINEERING`. Unrecognised non-blank value → `400` |

```json
{ "title": "...", "shortDescription": "...", "content": "...",
  "published": true, "featuredImageUrl": "/uploads/...",
  "tags": ["651a..."], "skills": [], "contentType": "ENGINEERING" }
```

**Why blank coerces but nonsense rejects**: a blank value is what an empty
`<select>` submits and should mean "default"; a typo'd value is a client bug and
should be loud. The existing validation block is at
`AdminBlogController.java:199-240`.

**Responses**: `201` (create) / `200` (update) with the DTO above. `400` with the
existing `ValidationErrorResponse` shape on validation failure. `404` on unknown
id for `PUT`. `ContentChangeEvent` publishing (`:96`, `:136`) is unchanged.

---

## `DELETE /api/admin/blogs/{id}`

Unchanged — `204`.

---

## Admin editor UI

Per Constitution VI, the blog editor's top section is two columns: title +
short description left, featured image right, with tags and skills above the
content editor.

The content-type control is a plain `<select>` placed in the **left column, below
Short Description** — it is a property of the post's nature, so it belongs with
the title/description group rather than beside the image picker.

```tsx
<label htmlFor="contentType">Content type</label>
<select id="contentType" value={contentType} onChange={...}>
  <option value="ENGINEERING">Engineering</option>
  <option value="DIGEST">Weekly Digest</option>
</select>
```

Default for a new post: `ENGINEERING`, preselected — not an empty option, so the
default is visible rather than implied.

---

## Contract tests

`backend/src/test/java/com/simonrowe/admin/AdminBlogControllerTest.java`
(extending `AbstractIntegrationTest`, authenticated via `AdminTestAuth.adminJwt()`):

| Test | Asserts |
|---|---|
| create without contentType | persisted as `ENGINEERING`, response echoes it |
| create with DIGEST | persisted and echoed as `DIGEST` |
| create with blank | coerced to `ENGINEERING` |
| create with nonsense | `400` |
| update changes it | `ENGINEERING` → `DIGEST` round-trips |
| update omitting it | does **not** silently reset a `DIGEST` post to `ENGINEERING` — an omitted key on `PUT` preserves the stored value |
| list includes it | `jsonPath("$.content[0].contentType")` present |
| unauthenticated | `401`/`403` as today |

> The "update omitting it" case is the subtle one: `POST` absent → default, but
> `PUT` absent → preserve. Getting this wrong would let the digest generator's
> classification be wiped by any admin edit that does not send the field.
