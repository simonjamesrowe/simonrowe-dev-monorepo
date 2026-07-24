# Data Model: Favourite News & Events

**Date**: 2026-07-24 | **Feature**: 029-favourite-news-events

## New Collection: `favourites`

Java record in `com.simonrowe.favourites`:

```java
@Document(collection = "favourites")
@CompoundIndex(name = "idx_user_type_content", def = "{'userId':1,'type':1,'contentId':1}", unique = true)
public record Favourite(
    @Id String id,
    String userId,        // Auth0 sub of the owner (jwt.getSubject())
    FavouriteType type,   // NEWS | EVENT
    String contentId,     // id of AggregatedArticle / AggregatedEvent
    Instant createdAt) {}
```

### Fields

| Field | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | String | Mongo ObjectId | generated |
| `userId` | String | required | Auth0 `sub`; every query filters on it |
| `type` | FavouriteType | required | enum `NEWS` / `EVENT`; stored as string |
| `contentId` | String | required | references `aggregated_articles._id` or `aggregated_events._id` (soft reference — no DBRef) |
| `createdAt` | Instant | required | drives "most recently favourited first" ordering |

### Indexes

- Unique compound `(userId, type, contentId)` — enforces one favourite per user/item (FR-002) and serves the `ids` + paged listing queries (prefix `(userId, type)`).

### Enum: `FavouriteType`

| Constant | Path segment | Content collection |
| --- | --- | --- |
| `NEWS` | `news` | `aggregated_articles` |
| `EVENT` | `events` | `aggregated_events` |

`fromPathSegment(String)` maps the URL segment; unknown segment → 400 at the controller.

## Relationships

- `Favourite.contentId` is a soft reference (plain id string). Referenced collections are unchanged. Deleted content ⇒ favourite row remains but is skipped when listing (FR-010); no cascade/cleanup needed at this scale.

## Queries

| Operation | Query |
| --- | --- |
| Add | existence check on referenced collection, then `insert`; `DuplicateKeyException` ⇒ success (idempotent) |
| Remove | `deleteByUserIdAndTypeAndContentId` (idempotent) |
| Ids | `findByUserIdAndType` → map to `contentId` set |
| Paged listing | `findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, pageable)` → bulk `findAllById` on content repo → re-order to favourite order, skip missing → `PageImpl` |

## State Transitions

None — a favourite either exists or it doesn't.

## Migration

The collection itself is created lazily on first write. However, **Spring Data Mongo does not
auto-create annotated indexes** (`spring.data.mongodb.auto-index-creation` is unset ⇒ `false`;
existing `@CompoundIndex` annotations in the repo are documentation-only). Since idempotency
(FR-002) relies on the unique index, create it explicitly via the repo's established Mongock
mechanism: change unit `V013CreateFavouritesUniqueIndex` in
`com.simonrowe.migration.changeunits`, using
`mongoTemplate.indexOps("favourites").createIndex(...)` (unique on `userId,type,contentId`).
Index creation is idempotent, so it is safe when Mongock change units run against the shared
Testcontainers Mongo in integration tests.
