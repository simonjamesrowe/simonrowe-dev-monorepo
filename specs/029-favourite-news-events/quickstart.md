# Quickstart: Favourite News & Events

**Feature**: 029-favourite-news-events

## Run it locally

```bash
./scripts/start.sh          # backend :8080 + frontend :5173 (sources .env files)
```

Open `http://localhost:5173/news-events`.

## Try the feature

1. **Logged out**: hearts on news/event cards render empty. Click one → Auth0 login popup
   opens (page does not navigate). Complete login → the save finishes automatically and the
   heart fills.
2. **Logged in**: click hearts to save/unsave; state persists across reloads.
3. Toggle **"Show favourites only"** in the feed filter bar → only your saved articles/events
   show, newest-saved first. Toggle off → full feed returns.

## Exercise the API directly

```bash
TOKEN=... # a valid Auth0 access token for audience https://api.simonrowe.dev

# Save / unsave
curl -i -X PUT    -H "Authorization: Bearer $TOKEN" localhost:8080/api/favourites/news/<articleId>
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/api/favourites/news/<articleId>

# Heart-state ids
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/favourites/news/ids

# Favourites-only listing (paged)
curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/favourites/events?page=0&size=20'

# Expect 401 without a token, 400 for a bad type, 404 for an unknown content id
curl -i localhost:8080/api/favourites/news/ids
curl -i -X PUT -H "Authorization: Bearer $TOKEN" localhost:8080/api/favourites/podcasts/x
```

## Run the tests

```bash
cd backend && ../gradlew test --tests '*Favourites*'   # Testcontainers (Docker required)
cd frontend && npm test -- favourites FavouriteButton  # Vitest

# End-to-end (real Auth0 popup login against the running local stack)
cd frontend && E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... \
  npx playwright test e2e/favourites.local.spec.ts --project=local
```

## Key files

| Area | Path |
| --- | --- |
| API contract | `specs/029-favourite-news-events/contracts/favourites-api.yaml` |
| Backend package | `backend/src/main/java/com/simonrowe/favourites/` |
| Index migration | `backend/src/main/java/com/simonrowe/migration/changeunits/V013CreateFavouritesUniqueIndex.java` |
| Security rule | `backend/src/main/java/com/simonrowe/auth/SecurityConfig.java` |
| Frontend service | `frontend/src/services/favouritesApi.ts` |
| Hook | `frontend/src/hooks/useFavourites.ts` |
| Button | `frontend/src/components/common/FavouriteButton.tsx` |
| Page | `frontend/src/pages/NewsEventsPage.tsx` |
