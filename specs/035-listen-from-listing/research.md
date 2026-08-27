# Phase 0 Research: Listen from the listing

**Date**: 2026-08-26 | **Plan**: [plan.md](./plan.md)

Every question below was resolved by reading the current code rather than by guessing, so no
`NEEDS CLARIFICATION` markers survive into Phase 1. File references are to the state of the
repository on the feature branch.

---

## R1. How do listing cards learn which items are listenable?

**Decision**: one new public endpoint,
`GET /api/narrations/ready?contentType=BLOG|ARTICLE_SUMMARY`, returning
`[{contentId, audioUrl, durationSeconds}]`.

**Rationale**: this is a correctness requirement, not a performance one.
`RateLimitInterceptor.preHandle` (`backend/src/main/java/com/simonrowe/ratelimit/RateLimitInterceptor.java`)
buckets `/api/blogs/{id}/narration` at `config.narration().requestsPerMinute()` (10) for **every**
method. The POST-only exemption —

```java
if (!HttpMethod.POST.matches(request.getMethod())) {
  return true;
}
```

— sits inside the `isSummaryPath(path)` branch only. So a blog listing showing 12 cards that each
polled its own narration status would exhaust the bucket on first render and 429 the rest, and the
reader would then be unable to press Listen at all. A single bulk call is the only workable read
path.

`/api/narrations/ready` does not start with `/api/blogs/` and does not end with `/narration`, so
`isNarrationPath` returns false and it falls to the chat bucket (`config.chat()`), which is far
larger and not shared with the narration action the reader is about to take. That is the desired
outcome (FR-004, SC-006) and it needs no interceptor change.

**Alternatives considered**:
- *Per-card `GET /api/blogs/{id}/narration`* — rejected: 429s as above.
- *Widen the interceptor's POST-only exemption to the blog branch* — rejected: it would make the
  10/min narration bucket meaningless for reads across the whole site, and would still cost N
  requests per page load, failing SC-005.
- *Embed `audioUrl`/`durationSeconds` in `BlogSummary` and `ArticleResponse`* — rejected: it
  couples two unrelated read models to the narration pipeline, and gives the provider nowhere to
  publish a newly-ready track to (FR-030) without refetching the whole list.

---

## R2. How is "the newest ready narration per content id" computed?

**Decision**: a `MongoTemplate` aggregation on the `narrations` collection —
`match(contentType = ?, status = READY)` → `sort(updatedAt DESC)` → `group("contentId").first(...)`
→ project to a `ReadyNarration` record. Implemented as `NarrationService.readyNarrations(contentType)`.

**Rationale**: narrations are fingerprint-addressed — the `_id` is a hash over script text plus
voice settings (`Narration.java`, `NarrationScriptBuilder.fingerprint`) — so one `contentId`
legitimately accumulates several rows over its lifetime, and older ones are marked `STALE` rather
than deleted (`markStale`). The reduction to one row per `contentId` must therefore happen
server-side, and `status = READY` in the `match` stage is what guarantees `STALE`, `FAILED`,
`UNCERTAIN`, `QUEUED` and `PROCESSING` rows are never advertised (FR-002).

`idx_narration_content_updated` (`{contentType: 1, contentId: 1, updatedAt: -1}`), declared on the
`Narration` document, already supports the `contentType` equality plus the `updatedAt` ordering.
**No new index, therefore no Mongock change unit.** (`auto-index-creation` is off in this project, so
a *new* `@CompoundIndex` would have needed one — this feature adds none.)

The repo precedent for `MongoTemplate.aggregate(...).getMappedResults()` into a projection record is
`NewsController.listSources()`, which does exactly this shape for source counts.

**Alternatives considered**:
- *`NarrationRepository.findByContentTypeAndStatus(...)` + reduce in Java* — rejected: correct but
  ships every stale-sibling document over the wire and reimplements a `$group` in application code.
- *A derived query with `Sort` and dedupe by first-seen* — same objection, and the intent is less
  legible than a named aggregation.

---

## R3. Is the endpoint public?

**Decision**: yes, no auth. It sits under `.anyRequest().permitAll()` in `SecurityConfig` and needs
no new matcher.

**Rationale**: the audio is globally shared — one generated MP3 serves everybody — exactly as with
`GET /api/news/summaries/ids` and `GET /api/favourites/{type}/ids`, both of which are public and are
the established pattern for "give a listing page cheap bulk state in one call". Knowing which posts
have audio reveals nothing per-reader. Making it authenticated would also defeat its purpose: a
signed-out reader must see `▶ 12 min` and be able to press it (FR-003, User Story 1).

---

## R4. Where does audio state live, and what plays it?

**Decision**: a `NarrationAudioProvider` mounted **above `<Routes>`** and **inside `AuthProvider`**,
holding a single **detached `new Audio()`** in a ref. The visible `NarrationPlayerBar` renders
inside `PublicLayout` and reads the provider through context.

**Rationale**: `App.tsx` wraps each public route in its own `<PublicLayout>` element:

```tsx
<Route element={<PublicLayout><BlogListingPage /></PublicLayout>} path="/blogs" />
<Route element={<PublicLayout><NewsEventsPage /></PublicLayout>} path="/news-events" />
```

React reconciles those as different elements, so `PublicLayout` — and every provider inside it
(`ChatProvider`, `DrawerProvider`, `TourProvider`) — remounts on navigation. A provider placed there
would lose its state on the first route change, and a JSX `<audio>` element rendered there would be
unmounted and stop playing. Both failures are exactly what the feature exists to prevent (FR-015).

The provider needs `getAccessToken` and `useEnsureAuthenticated` for the generation chain, so it must
sit **inside** `AuthProvider` — which is itself above `<Routes>` already, for favourites. That gives
a single valid position: `AuthProvider` → `NarrationAudioProvider` → `Suspense` → `Routes`.

The bar itself renders inside `PublicLayout` and may freely remount; it holds no state. That is what
keeps it off `/admin` (FR-019) without any path sniffing.

The detached element is still a real `<audio>` to `document.querySelectorAll('audio')`, so
`NarrationPanel`'s existing "pause every other audio on the page" handler continues to work in both
directions (FR-021) with no change to `NarrationPanel`. The provider adds the mirror-image handler on
its own element.

**Alternatives considered**:
- *Provider inside `PublicLayout`* — rejected: remounts, per above.
- *Hoist `PublicLayout` to a layout route with `<Outlet />`* — a real improvement, but it rewrites
  the routing of every public page including `ChatProvider`/`TourProvider` mounting semantics. Out of
  scope for this change; the provider-above-`Routes` placement works without it.
- *A module-level singleton `Audio` outside React* — rejected: state would not be observable by
  cards without a subscription mechanism the provider already gives us for free.

---

## R5. Where does the duration formatter live?

**Decision**: extract `formatApproximateDuration` from `NarrationPanel.tsx` into
`components/narration/formatDuration.ts`; both `NarrationPanel` and `ListenButton` import it.

**Rationale**: FR-008 requires the card's label to be formatted identically to the detail-page
player's. The function is currently a private helper in `NarrationPanel.tsx`
(`About ${Math.max(1, Math.round(s / 60))} min`). Copying it guarantees eventual drift. The card
needs the bare `12 min` next to a play glyph rather than the sentence-like `About 12 min`, so the
extracted module exports both `formatApproximateDuration` (unchanged, `"About 12 min"`) and
`formatCompactDuration` (`"12 min"`) over one shared minute calculation — so the *number* cannot
drift even though the two surfaces word it differently.

---

## R6. Does the chain introduce a second polling policy?

**Decision**: no. `useNarration.ts` exports its existing `LONG_POLL_SECONDS = 25` and
`MAX_LONG_POLLS = 4`; the provider imports them and runs its own small await-loop.

**Rationale**: the design is explicit — reuse the constants rather than invent a second policy. A
full extraction of the poll loop was considered and rejected: `useNarration`'s loop is bound up with
five pieces of React state (`narration`/`checking`/`requesting`/`delayed`/`clientError`) and a
component-scoped `AbortController` ref, whereas the provider needs a *per-track* controller, a
two-step chain (summary → narration) and a "was this track dismissed?" check between iterations.
Forcing both through one abstraction would contort `useNarration`, which has a passing regression
suite. Exporting the two constants gets the thing that actually matters — that the client and the
server's `@Max(25)` bound cannot drift, and that "gave up" means the same number of attempts
everywhere. `MAX_LONG_POLLS` exhaustion maps to the bar's manual re-check, mirroring
`NarrationPanel`'s `delayed` state (FR-042).

---

## R7. What does tightening the blog narration POST actually touch?

**Decision**: five code/doc sites plus one inverted test.

1. `SecurityConfig.filterChain` — add
   `.requestMatchers(HttpMethod.POST, "/api/blogs/*/narration").authenticated()` and rewrite the
   comment block that currently explains the asymmetry.
2. `BlogNarrationController`'s class javadoc — currently says *"Deliberately frozen: same path, same
   public (unauthenticated) POST"*. Rewrite to record why that changed.
3. `SecurityConfigTest.blogNarrationPostRemainsPublic` — currently asserts a 404 (i.e. the request
   reached the controller) and its javadoc says *"so nobody 'harmonises' the two by accident"*.
   Invert to assert 401 anonymously, plus a companion asserting a valid JWT reaches the controller
   (404 for a missing blog), plus one asserting `GET` stays public.
4. `docs/runbooks/article-summaries.md` — the paragraph *"**`POST /api/blogs/{id}/narration` is still
   public** — that asymmetry is deliberate and `SecurityConfigTest` asserts it, so do not
   'harmonise' the two."* is now actively wrong.
5. `CLAUDE.md` (repo root), in the `034-article-summary-audio` Recent Changes entry:
   *"`/api/blogs/{blogId}/narration` keeps its path and its public `POST`"*.
6. `frontend/src/services/blogApi.ts` — `requestBlogNarration(blogId, signal)` sends no
   `Authorization` header. It gains a `getAccessToken` parameter and sends `Bearer`, mirroring
   `requestSummaryNarration` in `articleSummaryApi.ts`.

Consequence for the frontend: `BlogNarration.tsx` gains the `useEnsureAuthenticated()` +
`getAccessToken` pair that `SummaryNarration.tsx` already has, returning an `UNAVAILABLE` response
with `"Sign in to generate audio"` when the popup is dismissed (FR-035). `BlogNarration.test.tsx`
mocks `requestBlogNarration` at the module boundary, so only the mock's call signature and the two
tests that exercise requesting need adjusting; the remaining assertions are untouched, and
`NarrationPanel.test.tsx` is unaffected.

**Rationale**: the same monthly TTS character budget backs both endpoints
(`NarrationBudgetService`), and summary narration is already authenticated. Gating only the new
listing surface would leave the identical post anonymously narratable from the detail page — the
budget would still be drainable, just from a different URL.

---

## R8. Who owns "this is now ready", and who owns "this now has a summary"?

**Decision**: the provider owns the ready-audio map and adds finished tracks to it. It also exposes
the last completed chain result; `useArticleSummaries` gains `noteSummarised(articleId)` and
`NewsEventsPage` calls it when it sees a completed news chain.

**Rationale**: two facts change when a chain finishes and each already has exactly one owner —
duplicating either produces the classic two-sources-of-truth bug.

- *Audio became ready.* The provider fetched and holds the bulk map, and cards read the map through
  the provider, so adding the finished track there flips the card to `▶ 12 min` whether or not the
  bar is still showing that track. This is precisely what makes dismissing the bar mid-generation
  safe (FR-030, FR-032, SC-009).
- *A summary became ready.* `useArticleSummaries` owns `summarisedIds`, and it is mounted by
  `NewsEventsPage` — *below* the provider. So the provider cannot write to it; it publishes, and the
  page relays. `noteSummarised` is the same local-flip trick `store()` already performs
  (`setSummarisedIds(prev => new Set(prev).add(articleId))`) when a drawer generation completes,
  rather than refetching `/api/news/summaries/ids`. Without it, a card whose summary came from the
  Listen chain would keep reading "Summarise" (FR-031).

**Alternatives considered**:
- *Hoist `useArticleSummaries` above `<Routes>` too* — rejected: it is news-page state, and hoisting
  it would keep per-article summary state alive across unrelated pages for no benefit.
- *Refetch `/api/news/summaries/ids` on chain completion* — rejected: a whole-set refetch to learn
  one id the provider already knows.

---

## R9. What does dismissing the bar mid-generation do?

**Decision**: stop watching, clear the bar, leave the server-side work running, and do **not**
auto-play when it lands — but still add the track to the ready map so the card flips.

**Rationale**: the work is already paid for the moment the POST is accepted, so cancelling it would
waste budget; and there is no cancellation API. Auto-play, though, is only justified by the reader
still watching — auto-playing into a bar the reader deliberately closed would be unsolicited audio.
Marking it ready anyway is what keeps the card honest. The provider therefore keeps a
"dismissed for this track" flag that suppresses auto-play while the chain's completion handler still
writes to the map (FR-032, SC-009).

---

## R10. What do the cards and the bar look like structurally?

**Decision**:

- **`ListenButton`** — one component, three states: `▶ 12 min` (ready), `Listen` (cold, secondary
  weight), spinner + stage label (in flight). In-flight state is read from provider state keyed on
  `contentId`, never from local component state, so a list re-render cannot lose it (FR-010).
  It calls `preventDefault()` + `stopPropagation()` exactly as `SummaryButton` does, because news
  cards are `<a>` anchors (FR-012).
- **Blog listing placement** — `FeaturedArticle` and `ArticleCard` have no actions row today; each
  gains one (`featured-article__actions` / `article-card__actions`) beside its existing "Read post"
  link. `ArticleCard` is shared with the home page's `FeaturedWriting`, which inherits the control
  deliberately.
- **News placement** — inside the existing `.feed__card-actions` container on both
  `feed__hero-card` and `feed__card`, alongside the unchanged `SummaryButton` and `FavouriteButton`.
  That container is already `position: absolute; display: flex; gap: 0.4rem` (`styles.css:8573`), so
  a third child needs no layout change. Three controls maximum (FR-013).
- **Events** — never (FR-011). Events are not summarised, so they can have no audio.
- **`BlogCard`/`BlogGrid`** — untouched. Nothing imports `BlogGrid`; they look like dead code and
  removing them is out of scope.
- **The bar** — a labelled `region` with an `aria-live="polite"` status for stage changes, matching
  how `NarrationPanel` announces its pending states. Reuses `NarrationPanel`'s `PLAYBACK_SPEEDS`
  values. On narrow viewports the speed control drops; `useMediaQuery` already exists for that.

---

## R11. Error taxonomy

**Decision**: errors live in the bar only; the card returns to rest (FR-037).

| Condition | Detected from | Bar message | Retryable |
|---|---|---|---|
| Budget exhausted | narration `state: 'UNAVAILABLE'` (the backend maps `failureCode: BUDGET_EXHAUSTED` → `UNAVAILABLE`, `NarrationResponse.from`) | "Audio is unavailable this month." | no |
| Narration failed | `state: 'FAILED'`, `retryable: true` | server `message` + retry control | yes |
| Insufficient source text | summary `state: 'FAILED'` with `INSUFFICIENT_SOURCE_TEXT` | "There isn't enough of this article to summarise." | no |
| Rate limited | HTTP 429 + `Retry-After` header | "Too many requests. Try again in Ns." | yes |
| Sign-in dismissed | `useEnsureAuthenticated()` → false | *nothing* — silent, no request issued | n/a |
| Bulk fetch failed | rejected `fetchReadyNarrations` | *nothing* — map stays empty, all cards cold | n/a |
| Poll exhausted | `MAX_LONG_POLLS` reached | "This is taking longer than usual." + re-check | re-check |
| Audio 404 at playback | `<audio>` `error` event | "This audio is no longer available." + clear track | no |

Note the 429 case is the one place a raw `Response` status must be read rather than a parsed
`NarrationResponse` — the existing `readNarration` helpers throw on non-2xx/503, so the provider's
own fetch wrapper inspects `response.status === 429` and the `Retry-After` header before delegating.
