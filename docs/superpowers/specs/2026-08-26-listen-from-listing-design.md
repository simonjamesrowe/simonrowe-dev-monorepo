# Listen from the listing

Date: 2026-08-26

## Problem

Generated audio exists for blog posts and for AI summaries of aggregated news articles,
but it can only be reached by drilling in: a blog post's narration lives on
`/blogs/{id}`, and a summary's narration lives inside the news summary drawer. A reader
browsing `/blogs` or `/news-events` cannot start listening without first committing to a
single item, and playback stops the moment they navigate back to carry on browsing.

The goal is to make audio playable directly from the two listing pages, and to keep it
playing while the reader carries on browsing.

## What already exists

- `useNarration` — long-poll orchestration (initial read, poll while pending, abort on
  unmount, a "taking a while" escape hatch). Parameterised by a transport.
- `NarrationPanel` — a seven-state render machine plus an `<audio>` player with a
  playback-speed control. Pauses every other `<audio>` on the page when it starts.
- `BlogNarration` (detail page) and `SummaryNarration` (summary drawer) — thin wrappers
  over that pair, differing only in transport.
- `GET/POST /api/blogs/{blogId}/narration` — blog narration. **POST is public today.**
- `GET/POST /api/news/{articleId}/summary/narration` — summary narration. POST is
  authenticated, because a text-to-speech render draws on a monthly character budget.
- `GET /api/news/summaries/ids` and `GET /api/favourites/{type}/ids` — the established
  pattern for giving a listing page cheap bulk state in one public call.

Two constraints found while designing, both load-bearing:

1. **`/api/blogs/*/narration` is rate-limited at 10/min per IP on `GET` as well as
   `POST`.** The `RateLimitInterceptor`'s POST-only exemption exists only in the summary
   branch. Per-card status polling from a listing page would therefore 429 on the first
   render. A bulk endpoint is not an optimisation here, it is the only workable read path.
2. **`PublicLayout` wraps each route individually**, so any provider placed inside it
   remounts on navigation. Audio state must live above `<Routes>`, and the audio element
   must be a detached `new Audio()` held in a ref rather than a JSX `<audio>` — otherwise
   playback dies on the first route change, which is the specific thing a shared player is
   for.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Cold cards (no audio yet) | Visible but secondary affordance, not hidden | A control that appears on an unpredictable subset of cards reads as broken while narrations are still sparse |
| Where audio plays | One persistent mini-player docked bottom-of-viewport | The point is to keep browsing while it plays; an inline player dies on filter change, and nesting a seek bar inside the news cards' `<a>` anchors is an accessibility mess |
| How cards learn what is playable | One bulk endpoint returning `contentId` + `audioUrl` + `durationSeconds` | Play is instant with no round-trip, and the duration on the card is itself the reason to press play |
| Surfaces | Featured + grid on both pages | The most prominent item on each page should not be the one you cannot play. `ArticleCard` is shared with the home page's Featured Writing, which inherits the control deliberately |
| Blog narration POST auth | Tighten the backend to require a JWT everywhere | It draws on the same monthly budget as summary narration, which is already authenticated. Gating only the listing would leave the same post anonymously narratable from one surface and not the other |
| News two-step | One escalating "Listen" button | Chosen over keeping generation drawer-only. The chain's cost is made visible through the sign-in prompt and per-stage labels rather than a second button |
| In-flight progress | Handed to the mini-player immediately, auto-play on ready | Card-local progress is the state most likely to be destroyed by a filter change or "Load more"; auto-play is warranted because pressing Listen and completing a sign-in is explicit consent |

## Architecture

One new frontend module owns everything:

- **`NarrationAudioProvider`** — mounted above `<Routes>`, inside `AuthProvider` (it needs
  `getAccessToken`). Holds a single detached `HTMLAudioElement`, the current track,
  playback state, and the generation chain.
- **`ListenButton`** — the per-card control. A view over provider state.
- **`NarrationPlayerBar`** — the docked player. Rendered inside `PublicLayout` so it never
  appears under `/admin`; reads state from the provider above it.

`NarrationPanel`, `useNarration`, `BlogNarration` and `SummaryNarration` keep their current
structure. The detail page and the summary drawer are unchanged apart from the sign-in gate
that the auth tightening requires.

### Backend: bulk ready lookup

`GET /api/narrations/ready?contentType=BLOG|ARTICLE_SUMMARY`

```json
[{ "contentId": "...", "audioUrl": "/uploads/...", "durationSeconds": 734 }]
```

Public, no auth — the audio is globally shared, the same reasoning as
`/api/news/summaries/ids`.

Mongo aggregation: match `contentType` and `status = READY`, sort `updatedAt` descending,
group taking the first document per `contentId`. Narrations are fingerprint-addressed, so
one content id can have several rows; this returns only its newest ready one and never
surfaces `STALE`, `FAILED` or `UNCERTAIN` rows. `idx_narration_content_updated`
(`{contentType: 1, contentId: 1, updatedAt: -1}`) already orders this.

For `ARTICLE_SUMMARY` the `contentId` **is the aggregated article id**, not the summary id
— `ArticleSummaryNarrationSource` states this explicitly — so the news page keys straight
off the ids it already has, with no join.

The path is deliberately outside the `RateLimitInterceptor`'s `/api/blogs/*/narration`
pattern (`/api/narrations/ready` does not match it), so a page load does not spend from the
10/min narration bucket.

### Backend: auth tightening

`POST /api/blogs/{blogId}/narration` becomes authenticated in `SecurityConfig`, matching
the summary narration POST. Consequences to carry out, not leave dangling:

- `BlogNarrationController`'s javadoc currently records the public POST as deliberate and
  frozen. Rewrite it to record why that changed.
- The CLAUDE.md note describing the public/authenticated asymmetry as intentional becomes
  wrong. Rewrite it.
- `BlogNarration` on the detail page gains the `useEnsureAuthenticated()` call that
  `SummaryNarration` already has, so a signed-out reader gets the sign-in popup rather than
  a 401. A dismissed popup returns an `UNAVAILABLE` response carrying "Sign in to generate
  audio", the pattern `SummaryNarration` already uses.

`GET` stays public on both.

### Frontend: the provider

State:

```ts
track: { contentType, contentId, title, href, audioUrl, durationSeconds } | null
stage: 'idle' | 'summarising' | 'narrating' | 'ready'
playing: boolean
position: number
rate: number
error: { message: string; retryable: boolean } | null
```

One method, `listen(request)`, drives the escalating chain:

| starting state | chain |
| --- | --- |
| audio ready in the bulk map | load, play — no network |
| blog, no audio | sign in → `POST /api/blogs/{id}/narration` → poll → auto-play |
| news, summary exists, no audio | sign in → `POST /api/news/{id}/summary/narration` → poll → auto-play |
| news, no summary | sign in → `POST /api/news/{id}/summary` (blocks 15–30s) → `POST …/summary/narration` → poll → auto-play |

Calling `listen()` for a different track aborts the in-flight one. The chain reuses
`useNarration`'s long-poll constants (`LONG_POLL_SECONDS = 25`, `MAX_LONG_POLLS = 4`)
rather than introducing a second polling policy; where the shared logic can be extracted
without contorting `useNarration`, extract it, otherwise import the constants.

Rate limits worth respecting in the UI: summary POST is 5/min per IP, narration POST
10/min. A 429 surfaces as a retryable error in the bar with the server's `Retry-After`
wording, not as a silent failure.

**Who owns "this is now ready".** Two facts change when a chain completes, and both have an
existing owner, so the provider must publish rather than duplicate:

- *Audio became ready.* The provider holds the bulk ready map, so it adds the finished
  track to its own map. Cards read the map through the provider, so a card flips to
  `▶ 12 min` on completion whether or not the bar is still on screen. This is what makes
  dismissing the bar mid-generation safe: the work continues server-side, and the card
  still ends up playable without a page reload.
- *A summary became ready.* `useArticleSummaries` owns `summarisedIds` for the news page,
  and the provider sits above the route that mounts it. The provider therefore exposes the
  last completed chain result, and `useArticleSummaries` gains a `noteSummarised(articleId)`
  entry point that the news page calls when it sees one — the same local-flip trick
  `store()` already does when a drawer generation completes, rather than refetching the ids
  set. Without this, a card whose summary was generated by the Listen chain would keep
  reading "Summarise".

Dismissing the bar during generation stops watching and clears the bar. It does not cancel
server-side work, and it does not auto-play when the work lands — auto-play only follows a
chain the reader is still watching.

### Frontend: the cards

A single `ListenButton` with three states:

- **`▶ 12 min`** — audio ready. Duration formatted by the same helper as
  `NarrationPanel`'s `formatApproximateDuration`, extracted so the two cannot drift.
- **`Listen`** — cold. Secondary weight, so it reads as an offer rather than the card's
  primary action.
- **spinner + stage label** — in flight. Mirrored from provider state keyed on `contentId`,
  so a re-render cannot lose it.

Placement:

- `/blogs`: `FeaturedArticle` and `ArticleCard`. Both need a new actions row — neither has
  one today. `ArticleCard` is shared with the home page's `FeaturedWriting`, which inherits
  the control deliberately.
- `/news-events`: `feed__hero-card` and `feed__card`, inside the existing
  `feed__card-actions` alongside the unchanged summary and heart buttons. Three controls
  maximum.
- Events: never. Events are not summarised, so they can have no audio.

News cards are `<a>` anchors, so `ListenButton` performs the same `preventDefault` /
`stopPropagation` as `SummaryButton` to stop a click opening the original article.

`BlogCard` and `BlogGrid` are not touched: nothing imports `BlogGrid`, so they appear to be
dead code, and removing them is not this change's business.

### Frontend: the bar

Contents when `stage === 'ready'`: title (linking to the post or article), play/pause,
seek, elapsed and total, the existing `PLAYBACK_SPEEDS` control, dismiss.

When `stage` is `'summarising'` or `'narrating'`: title plus "Summarising…" /
"Preparing audio…", no transport controls, dismiss still available — see "Who owns *this is
now ready*" above for exactly what dismissing does and does not stop.

Mobile: title, play/pause and a progress line; the speed control is dropped.

Because the detail page keeps its own `NarrationPanel`, two `<audio>` elements can exist at
once. `NarrationPanel`'s existing "pause every other `<audio>` on the page" behaviour
already handles that — the bar's detached element is a real `<audio>` to
`document.querySelectorAll`.

Accessibility: the bar is a labelled `region`; stage changes are announced through an
`aria-live="polite"` status, matching how `NarrationPanel` announces its pending states.

## Error handling

Failures surface in the bar, never on the card — the card returns to its resting state.

| condition | treatment |
| --- | --- |
| narration `UNAVAILABLE` from `BUDGET_EXHAUSTED` | "Audio is unavailable this month." Not retryable |
| narration `FAILED`, `retryable: true` | Message plus a retry control in the bar |
| summary `FAILED`, `INSUFFICIENT_SOURCE_TEXT` | "There isn't enough of this article to summarise." Not retryable |
| 429 from either POST | Retryable, using the server's `Retry-After` |
| sign-in popup dismissed | Silent. No request issued, no error shown — as `useArticleSummaries` does today |
| bulk ready fetch fails | Map stays empty, every card reads "Listen". Never blocks the list |
| polling exhausts `MAX_LONG_POLLS` | Bar offers a manual re-check, mirroring `NarrationPanel`'s `delayed` state |
| audio URL 404s at playback time (narration deleted or restored over) | Bar reports "This audio is no longer available" and clears the track |

## Testing

Backend:

- Controller test for `GET /api/narrations/ready`: both content types, empty result, and
  the newest-per-`contentId` case with a `STALE` sibling present.
- Security test that `POST /api/blogs/{id}/narration` returns 401 anonymously and succeeds
  with a valid JWT, and that `GET` stays public.

Frontend:

- Provider tests for each chain branch in the table above, plus abort-on-new-track,
  auto-play on ready, and 429 handling.
- A provider test that dismissing the bar mid-chain suppresses auto-play but still marks
  the track ready, so the card flips to `▶`.
- A `useArticleSummaries` test for `noteSummarised`, and a `NewsEventsPage` test that a
  card whose summary came from the Listen chain flips to "Read summary".
- `ListenButton` state tests: ready with duration, cold, in-flight.
- A `NewsEventsPage` test that an article with ready audio renders its duration and one
  without renders the cold state.
- `BlogNarration.test.tsx` and `NarrationPanel.test.tsx` stay green apart from the detail
  page's new sign-in gate.

## Out of scope

- Auto-advance and queueing. Sampling from a listing is the use case; binge-listening is
  not, and a queue is UI nobody asked for.
- Replacing the detail page's inline `NarrationPanel` with the bar. Worthwhile later — it
  would make playback survive a detail-to-listing navigation — but it rewrites a component
  with a passing regression suite and changes a page this request did not ask about.
- `ARTICLE_FULL` narration (narrating a third party's article body rather than our summary
  of it), which `NarrationContentType` already documents as deliberately deferred.
- Audio for events.
- Removing the apparently-dead `BlogCard` / `BlogGrid`.
