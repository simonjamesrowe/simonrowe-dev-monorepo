# Phase 1 Data Model: UX Top-10 Improvements

Only three collections change, and only additively. No collection is created, no
field is removed, no field changes type.

---

## 1. `blogs` — new field `contentType`

### New enum

```java
package com.simonrowe.blog;

/** Distinguishes hand-written engineering posts from generated weekly digests. */
public enum BlogContentType {
  ENGINEERING,
  DIGEST
}
```

Stored as its enum **name** (`"ENGINEERING"` / `"DIGEST"`) — Spring Data's default
enum serialization, so the change unit's string literals and the record's enum
agree without a converter.

### Both `Blog` records gain the component

`blog/Blog.java` (public/read model) — append at the end so existing positional
`new Blog(...)` calls fail to compile rather than silently shifting arguments:

```java
@Document(collection = "blogs")
@CompoundIndex(name = "idx_published_created", def = "{'published': 1, 'createdDate': -1}")
public record Blog(
    @Id String id,
    String title,
    String shortDescription,
    String content,
    boolean published,
    String featuredImageUrl,
    @Field("createdDate") Instant createdDate,
    @Field("updatedDate") Instant updatedDate,
    @DBRef List<Tag> tags,
    @DBRef List<Skill> skills,
    BlogContentType contentType          // NEW
) {}
```

`admin/Blog.java` (write model) — same, appended after `legacyId`.

> **Component order differs between the two records** (`admin.Blog` has
> tags/skills *before* the dates, plus `legacyId`). Appending to the end of each is
> the only safe move; do not try to align them in this feature.

### Field semantics

| Property | Value |
|---|---|
| Nullable in MongoDB | Yes, transitionally — any document written before `V015` runs |
| Nullable in the API | **No** — DTOs coerce `null` → `ENGINEERING` |
| Default for new posts | `ENGINEERING` |
| Indexed | No (see below) |
| Mutable | Yes, via the admin editor |

**No index.** Spring Data auto-index-creation is disabled project-wide (a
`@CompoundIndex` annotation alone is decorative), so an index would need its own
change-unit step. With ~43 documents, all of which `GET /api/blogs` returns as a
single unpaged list anyway, an index has no benefit.

### Migration: `V015BackfillBlogContentType` (order `015`)

```text
digestTagIds ← { t._id | t ∈ tags, lower(trim(t.name)) == "weekly digest" }

for each b ∈ blogs where b.contentType is absent:
    b.contentType ← DIGEST     if any DBRef in b.tags has $id ∈ digestTagIds
                    ENGINEERING otherwise
```

- **Idempotent**: the `contentType is absent` filter makes a second run a no-op,
  and it also means a later manual reclassification is never overwritten.
- **Case/whitespace insensitive** on the tag name, as FR-020 requires.
  `TagRepository.findByName` is exact and case-sensitive, so this is done in the
  change unit against raw `org.bson.Document`s, following the
  `V014MakeFavouritesGlobal` house pattern.
- **Tag references are `@DBRef`**, stored as sub-documents carrying `$id` — match
  on `$id`, not on a plain string.
- **Rollback**: `$unset` `contentType` across `blogs`.
- **Expected outcome** (per spec assumptions): 15 → `DIGEST`, 28 → `ENGINEERING`.

### Write paths

| Path | Sets `contentType` |
|---|---|
| `admin/AdminBlogController` create/update | From the request body; absent/blank/unrecognised → `ENGINEERING`. Included in `toDto()` so the editor round-trips |
| `agents/WeeklyDigestAgent` (`:108-113`) | `BlogContentType.DIGEST` |
| `V015` | Tag-derived, once |

### Read paths

`BlogSummaryResponse` and `BlogDetailResponse` each gain a `contentType`
component, populated in `fromEntity` with `blog.contentType() == null ?
ENGINEERING : blog.contentType()`.

---

## 2. `media_assets` + `skill_groups` + `jobs` — icon and logo assets

No schema change. `V016InstallSkillAndCompanyIcons` (order `016`) **creates rows
and repoints references**.

### `MediaAsset` (unchanged shape, new rows)

```java
record MediaAsset(
    @Id String id,
    String fileName,          // e.g. "original.svg"
    String mimeType,          // "image/svg+xml"
    long fileSize,
    String originalPath,      // "/uploads/{assetId}/original.svg"
    Map<String, VariantInfo> variants,   // {} — ImageVariantGenerator skips SVG
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId)   // idempotency key
```

**`legacyId` is the idempotency key.** Each bundled asset gets a deterministic id
(`icon:<skill-group-slug>`, `logo:<company-slug>`). The change unit looks it up
first and reuses the existing `assetId` if present — so a re-run creates no
duplicate rows and rewrites no files. This reuses the meaning `MediaSyncService`
already gives `legacyId`.

### On-disk layout

Matching what `MediaService` and `ExternalImageDownloader` already produce:

```text
{uploads.path}/{assetId}/original.svg
```

served at `/uploads/{assetId}/original.svg` by the `ResourceHandlerRegistry`
mapping in `WebConfig.java:51-59`.

Source of the bytes: `backend/src/main/resources/media/icons/*.svg`, read via
`ClassPathResource`.

### `skill_groups.image` / `jobs.companyImage` (unchanged type, new value)

Both are a `common.Image` **record**, not a string:

```java
record Image(String url, String name, Integer width, Integer height,
             String mime, ImageFormats formats)
```

The change unit writes:

```text
image = { url:  "/uploads/{assetId}/original.svg",
          name: "<display name>",
          mime: "image/svg+xml",
          width: null, height: null, formats: null }
```

`formats` stays absent because SVGs have no variants
(`ImageVariantGenerator:36-38`). Both resolvers
(`MediaVariantResolver`, `MediaImageHydrator`) already fall back to the original
when a preferred variant is missing, and the frontend's
`group.image?.formats?.thumbnail?.url ?? group.image?.url`
(`SkillGroupCard.tsx:10`) lands on `.url`. **No frontend change is needed for
this.**

### Matching rule

| Target | Matched by | Guard |
|---|---|---|
| `skill_groups` | `name` (trimmed, case-insensitive) against the manifest | Only repoint when the manifest has an entry; leave others alone |
| `jobs` | `company` (trimmed, case-insensitive) | Same |

Documents with no manifest entry keep whatever they have. Nothing is ever set to
`null` — a job or group without a logo keeps its existing placeholder behaviour
(`SkillGroupCard.tsx:26-28` renders the first letter).

**Rollback**: delete the `media_assets` rows created by this unit (identified by
their `legacyId` prefix) and `$unset` the `image`/`companyImage` fields it set.
Files on disk are left in place — harmless and unreferenced.

**Manifest** lives in `contracts/asset-manifest.md` and is subject to the FR-032
human approval gate before this change unit is written.

---

## 3. `social_medias` — GitHub link names

No schema change. `V017NameGithubSocialLinks` (order `017`) sets `name` on two
existing documents.

```java
@Document(collection = "social_medias")
record SocialMediaLink(
    @Id String id,
    String type,              // "github" | "linkedin" | "twitter"
    String name,              // ← the field this migration sets
    String link,              // NB: exposed to the API as "url"
    Boolean includeOnResume,
    Instant createdAt,
    Instant updatedAt)
```

> **Field-name mismatch to respect**: the entity field is `link`; the DTO renames
> it to `url` (`SocialMediaLinkResponse.java:3,11`). The change unit matches on
> `link`; the frontend reads `url`.

```text
for each d ∈ social_medias where d.type == "github":
    target ← "GitHub — personal"   if d.link is the personal account URL
             "GitHub — this site"  if d.link is the repository URL
    if target ≠ null and d.name ≠ target: set d.name ← target
```

- **Idempotent**: guarded by `d.name != target`.
- **Both target URLs must be read from live data before writing this unit** — they
  are not guessed. Recorded as a task precondition.
- **Rollback**: `$unset` `name` on the two matched documents.

Consuming change: `SocialLinks.tsx:34,41` inverts its fallback from
`platformLabels[link.type] ?? link.name` to `link.name ?? platformLabels[link.type]`.

---

## 4. Frontend types

`frontend/src/types/blog.ts`:

```ts
export type BlogContentType = 'ENGINEERING' | 'DIGEST'

export interface BlogSummary {
  id: string
  title: string
  shortDescription: string
  featuredImageUrl?: string
  createdDate: string
  tags: TagRef[]
  skills?: SkillRef[]
  contentType: BlogContentType   // NEW — non-optional; backend never returns null
}
```

`BlogDetail` gains the same component.

Non-optional is deliberate: the tab filter and the featured-post selection both
read it on every item, and the backend coerces `null` server-side (§1), so an
optional type would push a redundant `?? 'ENGINEERING'` into every call site.

---

## 5. Entity relationship summary

```text
blogs ──@DBRef──> tags          (unchanged; "Weekly Digest" tag is V015's input)
blogs ──@DBRef──> skills        (unchanged)
blogs.contentType                NEW enum, no reference

skill_groups.image  ──url──> /uploads/{assetId}/original.svg ──> media_assets.originalPath
jobs.companyImage   ──url──>                  "                          "
                                 V016 creates the media_assets rows and both references

social_medias.name               V017 sets it on the two type=="github" rows
```

No new collections. No new indexes. No cascading deletes.
