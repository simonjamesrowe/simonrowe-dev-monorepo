# Quickstart: Listen from the listing

**Date**: 2026-08-26 | **Plan**: [plan.md](./plan.md)

## Run the tests

```bash
# Backend — the three suites this feature touches
cd backend
../gradlew test --tests 'com.simonrowe.narration.NarrationReadyControllerTest' \
                --tests 'com.simonrowe.narration.NarrationReadyAggregationTest' \
                --tests 'com.simonrowe.auth.SecurityConfigTest'
../gradlew checkstyleMain checkstyleTest    # Google Java Style — blocking

# Backend — full suite before opening a PR (Testcontainers: Docker must be running)
../gradlew test

# Frontend
cd ../frontend
npm test
npm run lint                                 # blocking CI step; must exit 0
```

The pre-commit hook runs backend tests plus checkstyle — see the `backend-test` skill if it fails in
a way that looks like infrastructure rather than code.

## Run the app

```bash
./scripts/start.sh          # backend :8080 (management :8082) + frontend :5173
```

If ports clash with another Conductor workspace, use the `local-env` skill.

## Verify the backend by hand

```bash
# The new bulk endpoint — public, so no token
curl -s 'http://localhost:8080/api/narrations/ready?contentType=BLOG' | jq
curl -s 'http://localhost:8080/api/narrations/ready?contentType=ARTICLE_SUMMARY' | jq

# Bad content type → 400
curl -s -o /dev/null -w '%{http_code}\n' \
  'http://localhost:8080/api/narrations/ready?contentType=NONSENSE'      # 400

# The tightened POST — 401 without a token (this used to be a 404/202)
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:8080/api/blogs/any-id/narration                        # 401

# GET stays public
curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:8080/api/blogs/any-id/narration                        # 404, not 401

# It must NOT spend from the narration bucket. Twelve bulk reads in a row, then a narration
# GET: the last call must not be 429.
for i in $(seq 1 12); do
  curl -s -o /dev/null 'http://localhost:8080/api/narrations/ready?contentType=BLOG'
done
curl -s -o /dev/null -w 'narration GET after 12 bulk reads: %{http_code}\n' \
  http://localhost:8080/api/blogs/any-id/narration                        # 404, NOT 429
```

## Seed data to look at

The bulk endpoint returns `[]` on a clean database, which makes every card cold — correct, but not
much to look at. Two options:

1. **Restore prod data** — use the `prod-data-restore` skill. Prod has real narrations, so blog
   cards come up with durations immediately. Note that a restore drops collections, so
   `NarrationRestoreValidator.ensureIndexes()` (not Mongock) is what puts the narration indexes
   back — including the one this endpoint's aggregation relies on.
2. **Generate one** — sign in as `admin@simonrowe.dev`, open a blog post, press the detail page's
   Listen control, wait for it to finish, then go back to `/blogs`. That card should now show a
   duration.

## Manual verification checklist

Maps to the spec's success criteria.

**Story 1 — play what already exists (SC-001, SC-002, SC-003, SC-005)**

- [ ] `/blogs` — a post with audio shows `▶ N min`; one without shows a secondary `Listen`.
- [ ] Press `▶` — playback starts with no intermediate spinner; the bar docks at the bottom.
- [ ] Switch the blog tab (Engineering ↔ All) — audio keeps playing, position holds.
- [ ] Navigate `/blogs` → `/news-events` → `/profile` — audio keeps playing throughout.
- [ ] On `/news-events`, press "Load more" — audio keeps playing.
- [ ] `/news-events` — an article whose summary has audio shows a duration; its card has exactly
      three controls (listen, summarise, favourite).
- [ ] Event cards in the timeline have **no** listen control.
- [ ] The home page's Featured Writing cards show the control too (they reuse `ArticleCard`).
- [ ] Go to `/admin/dashboard` — the bar is **not** rendered.
- [ ] DevTools Network, on a fresh `/blogs` load: exactly **one** `/api/narrations/ready` request
      per content type, regardless of card count. No `/api/blogs/*/narration` requests at all.
- [ ] Pause, seek, change speed, dismiss — all work; dismiss stops playback.
- [ ] Open a blog detail page and press its inline player while the bar is playing — the bar
      pauses, and vice versa.

**Story 2 — generate from the listing (SC-004, SC-009)**

- [ ] Signed out, press `Listen` on a cold blog card → sign-in popup → generation starts → the bar
      shows "Preparing audio…" → it auto-plays when ready → the card now shows a duration.
- [ ] Dismiss the sign-in popup instead → nothing happens at all. No request in the Network tab, no
      error on screen.
- [ ] Press `Listen` on a news article that has **no** summary → the bar shows "Summarising…" then
      "Preparing audio…" → auto-plays → **and the card's summary button flips from "Summarise" to
      "Read summary"**.
- [ ] Start a generation, then press `Listen` on a different card → the first is abandoned, the bar
      switches.
- [ ] Start a generation, dismiss the bar, wait → nothing auto-plays, but the card ends up showing
      a duration with no page reload.

**Story 4 — auth everywhere (SC-007)**

- [ ] Signed out on a blog **detail** page, press its Listen control → sign-in popup, not a 401.
- [ ] Dismiss it → the panel reads "Sign in to generate audio", not "temporarily unavailable".

**Story 3 — errors (SC-008)**

- [ ] Force a 429 (hammer the POST past 10/min) → the bar reports it with the server's wait time
      and offers a retry; the card returns to rest.
- [ ] Block `/api/narrations/ready` in DevTools and reload → the listing renders normally, every
      card cold.
- [ ] Point a track's `audioUrl` at a deleted file → the bar reports "This audio is no longer
      available" and clears.

**Accessibility (SC-010)**

- [ ] Tab to the bar's controls and operate them by keyboard alone.
- [ ] The bar is a labelled region; stage changes are announced via `aria-live="polite"`.
- [ ] Narrow the viewport to phone width — title, play/pause and progress remain; the speed
      control is gone; nothing overflows.

## Gotchas

- **`FORMAT_VERSION` is off limits.** `NarrationScriptBuilder`'s `FORMAT_VERSION` stays the literal
  `blog-narration-v1`; it feeds the fingerprint that *is* the narration `_id`, so changing it
  orphans every stored MP3.
- **The provider's position in `App.tsx` is load-bearing.** It must be above `<Routes>` and inside
  `AuthProvider`. `PublicLayout` wraps each route individually, so anything inside it remounts on
  navigation — a provider there loses state and a JSX `<audio>` there stops playing. If audio dies
  on a route change, this is why.
- **The audio element must be `new Audio()` in a ref**, not JSX. It is still a real `<audio>` to
  `document.querySelectorAll`, which is what keeps `NarrationPanel`'s pause-the-others behaviour
  working in both directions.
- **Local Langfuse has no OTLP ingest** (compose is still v2), so expect no local traces. Nothing in
  this feature emits LLM traces anyway beyond the existing summary generation.
