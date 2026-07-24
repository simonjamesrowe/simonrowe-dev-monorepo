# Global favourites design

Date: 2026-07-24

## Problem

Favourites on the News & Events page were built as **per-user** storage (scoped to the
Auth0 subject, unique on `(userId, type, contentId)`). The intended behaviour is that
favourites are **global**: a single shared set of picks that every visitor sees. Auth is
only needed to *change* the set, not to read it.

A side effect of the per-user model is that viewing "Show favourites only" required an
auth token round trip (to know *whose* favourites to load), contributing to the perceived
load lag.

## Behaviour

- **Reads are global and public.** `GET /api/favourites/{type}/ids` and
  `GET /api/favourites/{type}` require no auth, no user filter — every visitor (logged in
  or not) sees the same favourited set and filled hearts.
- **Writes require auth (any authenticated user).** `PUT` / `DELETE
  /api/favourites/{type}/{id}` toggle the shared set. Not admin-gated — any logged-in user
  can curate, matching the previous auth posture.

## Data model

`Favourite` drops `userId`:

```
Favourite(id, type, contentId, createdAt)
```

Indexes (created via Mongock — auto-index-creation is off):

- `idx_type_content` — unique `{type: 1, contentId: 1}` (idempotency + `existsBy` + `ids`
  filter prefix)
- `idx_type_created` — `{type: 1, createdAt: -1}` (covers the sorted listing query)

## Backend changes

- `Favourite` — remove `userId`, update `@CompoundIndexes`.
- `FavouriteRepository` — `findByType`, `findByTypeOrderByCreatedAtDesc`,
  `existsByTypeAndContentId`, `deleteByTypeAndContentId`.
- `FavouritesService` — drop the `userId` parameter from every method.
- `FavouritesController` — GET endpoints no longer take a JWT; PUT/DELETE stay auth-gated
  via `SecurityConfig` (no subject needed).
- `SecurityConfig` — GET `/api/favourites/**` public; `PUT`/`DELETE
  /api/favourites/**` authenticated.
- `V014MakeFavouritesGlobal` change unit — drop the per-user index, dedupe existing rows by
  `(type, contentId)` keeping the earliest, unset `userId`, create the new indexes.
  (Replaces the earlier `V014CreateFavouritesListIndex`, which targeted the per-user model.)

## Frontend changes

- `favouritesApi` — `getFavouriteIds` / `getFavourites` become tokenless plain fetches;
  `addFavourite` / `removeFavourite` keep the bearer token.
- `useFavourites` — load the id set on mount for everyone (not gated on `isAuthenticated`);
  the heart toggle still calls `ensureAuthenticated` before writing. Drop the
  fresh-login re-sync (ids are already global).
- `NewsEventsPage` — the favourites listing fetch drops the token argument; the "Show
  favourites only" toggle no longer requires login (viewing is public).

## Testing

- Backend integration test: reads work without a JWT; a favourite added by user A is
  visible to user B and removable by user B (global); writes without a JWT return 401.
- Frontend: `favouritesApi` reads issue no `Authorization` header; `useFavourites` loads
  ids when logged out.
