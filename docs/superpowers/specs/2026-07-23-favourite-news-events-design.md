# Favourite News & Events — Design

**Date:** 2026-07-23
**Status:** Approved (pending spec review)

## Summary

Allow the authenticated owner to save News articles and Events as favourites, then
filter each listing to show only saved items. Favourites are stored server-side per
user (keyed to the Auth0 identity) so they follow the owner across devices.

This is an **owner-only** feature. Today the only authenticated identity is the
`DEV_PORTAL_ADMIN`, and the public News/Events pages have no login affordance. The
favourite endpoints are gated on *authentication* (any valid JWT), not the admin role,
and scope every read/write to the caller's Auth0 `sub`.

## Goals

- Mark/unmark any news article or event as a favourite.
- View "favourites only" on the existing News and Events pages via a toggle.
- Persist favourites server-side, per user, surviving across devices.
- Prompt login when an unauthenticated visitor attempts to save, then complete the
  save automatically without leaving the page.

## Non-goals

- No public sign-up / multi-visitor favourites (owner-only).
- No dedicated combined favourites page (favourites live inline on News/Events).
- No changes to how news/events are aggregated, stored, or displayed otherwise.

## Data Model

New MongoDB collection `favourites`, one document per saved item:

```
Favourite {
  id:        String   // Mongo id
  userId:    String   // Auth0 sub of the owner
  type:      FavouriteType   // NEWS | EVENT
  contentId: String   // id of the AggregatedArticle / AggregatedEvent
  createdAt: Instant
}
```

- Unique compound index on `(userId, type, contentId)` — a save is idempotent and
  scoped per user.
- Favourites *reference* existing `AggregatedArticle` / `AggregatedEvent` ids. Those
  collections are unchanged.
- `FavouriteType` is an enum mapping the `{type}` path segment (`news` → `NEWS`,
  `events` → `EVENT`).

## Backend API

New `com.simonrowe.favourites` package. All endpoints require authentication and
derive the user from `jwt.getSubject()` (`@AuthenticationPrincipal Jwt jwt`).

| Method & path | Purpose | Response |
| --- | --- | --- |
| `PUT /api/favourites/{type}/{id}` | Add (idempotent). 404 if the referenced article/event does not exist. | 204 No Content |
| `DELETE /api/favourites/{type}/{id}` | Remove. Idempotent (no error if not present). | 204 No Content |
| `GET /api/favourites/{type}/ids` | Ids the user has favourited — used to fill hearts on the normal listing. | `Set<String>` |
| `GET /api/favourites/{type}` | The favourited items themselves, ordered by `createdAt` desc — backs the "favourites only" view. | `Page<ArticleResponse \| EventResponse>` |

- `{type}` ∈ `{news, events}`; anything else → 400.
- `SecurityConfig` gains `.requestMatchers("/api/favourites/**").authenticated()`
  ahead of the existing `.anyRequest().permitAll()`.
- The full-listing endpoint (`GET /api/favourites/{type}`) returns favourited items
  **regardless of their `visible` flag** — it is the owner's private list. (The public
  `/api/news` and `/api/events` endpoints keep filtering `visible = true`.)
- Reuses existing `ArticleResponse` / `EventResponse` DTOs.

### Favourites-listing query

For `GET /api/favourites/{type}`: page the `favourites` documents for
`(userId, type)` ordered by `createdAt` desc, then load the referenced articles/events
by id and map them back into the favourited order. Missing/deleted referenced content
is skipped.

## Auth / Login Flow

Clicking a heart (or the "favourites only" toggle) while logged out triggers
**`loginWithPopup`**, not a full-page redirect:

- The popup reuses the already-registered `/admin` callback URL, so **no Auth0 tenant
  configuration change is required**.
- The main page never navigates away.
- On popup resolve, the pending save (or toggle) completes automatically — satisfying
  "auto-complete the save on return."

`useAuth` is extended to expose `loginWithPopup` (currently only `loginWithRedirect`).

## Frontend

- **`services/favouritesApi.ts`** — mirrors the `adminApi` pattern: each function takes
  a `getAccessToken` fn, obtains a token, and calls a shared `authFetch` with a
  `Bearer` header. Functions: `getFavouriteIds(type, getToken)`,
  `getFavourites(type, page, size, getToken)`, `addFavourite(type, id, getToken)`,
  `removeFavourite(type, id, getToken)`.
- **`hooks/useFavourites(type)`** — owns the favourite-id `Set` for one content type.
  Exposes `isFavourite(id)`, `toggleFavourite(id)` (handles the login popup + save/unsave
  and updates local state optimistically), and `loading`. Loads the id set when a
  session exists (`isAuthenticated`); renders empty hearts when logged out. One instance
  on News, one on Events — no global state.
- **`components/common/FavouriteButton.tsx`** — presentational Lucide `Heart` icon,
  filled when active, positioned in the corner of each card. Takes `active` + `onClick`.
- **`NewsPage` / `EventsPage`** — render a `FavouriteButton` on each card and a
  heart-labelled "Show favourites only" toggle in the page header next to the title.
  Favourites-only mode fetches from `GET /api/favourites/{type}` instead of the public
  listing; toggling it while logged out triggers the login popup.
- CSS follows the existing BEM `styles.css` convention (e.g. `news-card__favourite`,
  `news-page__favourites-toggle`).

## Testing

**Backend (Testcontainers integration test):**
- Add then list — favourited item appears in `GET /api/favourites/{type}` and its id in `/ids`.
- Remove — item disappears from both.
- Idempotent add — adding twice yields a single document.
- 401 without a token; requests scoped per user (user A cannot see user B's favourites).
- 404 when favouriting a non-existent article/event.

**Frontend (Vitest):**
- `favouritesApi` — correct URLs, `Bearer` header, response parsing.
- `useFavourites` — marks state from `/ids`, optimistic toggle, login-gate when logged
  out (popup invoked, save completes after resolve).
- `FavouriteButton` — renders filled vs empty by `active`.

## Rollout / Notes

- No data migration; the `favourites` collection is created lazily on first write.
- No Auth0 tenant change (popup reuses the `/admin` callback).
- No changes to the news/events aggregation pipeline.
