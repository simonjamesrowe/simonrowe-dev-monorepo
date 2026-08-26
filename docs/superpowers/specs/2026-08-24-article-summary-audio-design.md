# On-demand article summaries with audio

Date: 2026-08-24
Branch: `simonrowe/article-ai-summary-audio`

## Summary

Aggregated news articles on `/news-events` today show a one-paragraph blurb
(`AggregatedArticle.summary`, written by the aggregation classifier at ingest) and a
link out to the original. Some of those articles are long and substantive, and the
blurb does not do them justice.

This feature adds an authenticated, on-demand **in-depth summary** for any aggregated
article, shown in a right-side drawer, with an on-demand **audio narration** of that
summary.

Most of the machinery already exists and is being generalised rather than rebuilt:

- `AggregatedArticle.fullContent` already stores scraped article text.
- `ArticleSectionWriter` already solves "get trustworthy source text for an aggregated
  article" — a fresh re-scrape, falling back to stored `fullContent`, falling back to
  the stored blurb, with length floors that detect paywall and consent-wall
  interstitials.
- The `narration` package is already a complete async text-to-speech pipeline (Kafka
  queue, lease-based claim, Google TTS long-running operations, MP3 storage with
  checksum validation, a monthly character budget, a long-poll status API and a
  recovery scheduler). It is hard-wired to blogs and needs generalising.
- Favourites already establish the "globally shared artefact, public reads,
  authenticated writes" pattern this feature copies.

## Decisions

These were settled during brainstorming and are the load-bearing constraints.

| # | Decision | Rationale |
|---|---|---|
| 1 | Summaries are **globally shared** | Matches the favourites precedent (`V014MakeFavouritesGlobal`). One LLM call and one TTS render per article, ever. The second visitor gets it instantly. |
| 2 | Summary is **long-form prose**, 4–6 paragraphs | Chosen over a bulleted structured brief. |
| 3 | Register is **neutral third person** | The article is someone else's. First person would read as though Simon wrote the piece or holds the opinions in it. |
| 4 | **Summary audio only**; full-article audio deferred | See "Deferred scope". |
| 5 | UI is a **right-side drawer**; audio stops when it closes | The page has source filters, paging and a favourites toggle — state a navigation would discard. A hidden audio element playing with no visible controls is a bad surprise, and there is no persistent mini-player to hand playback off to. |
| 6 | Summary generation is **synchronous with an insert-first dedup guard** | See "Why synchronous". |

### Why synchronous

The narration pipeline is fully async through Kafka with leases, claims and a recovery
scheduler because Google's long-form TTS *forces* it: the provider returns a
long-running operation handle that has to be polled and survive restarts.

An LLM call has no such handle. It is a single blocking call of roughly 15–30 seconds,
and every other LLM call in this codebase is made inline — `ChatService` per request,
`ContentAggregationAgent` and `WeeklyDigestAgent` on scheduler threads. With
`spring.threads.virtual.enabled: true`, holding a virtual thread for 30 seconds is
cheap.

Reproducing the lease/claim/recovery machinery here would be building infrastructure to
track a remote operation that does not exist. The one property that machinery *does*
give us for free — never spending twice on the same artefact — is obtained instead from
a unique index and an insert-first guard.

## Backend

### Data model

New collection `article_summaries`, one document per article per prompt version.

```java
@Document(collection = "article_summaries")
public class ArticleSummary {
  @Id String id;              // sha256(SUMMARY_FORMAT_VERSION + articleId)
  @Indexed String articleId;
  SummaryStatus status;       // GENERATING | READY | FAILED
  long version;               // drives long-poll afterVersion
  String body;                // markdown prose
  String model;
  int sourceCharacterCount;
  Instant requestedAt;
  Instant completedAt;
  Instant updatedAt;
  String failureCode;
  boolean retryable;
}
```

`SummaryStatus` is `GENERATING | READY | FAILED`. As with `NarrationResponse.PublicState`,
the wire response carries one additional state that is never persisted: `NOT_REQUESTED`,
returned when no document exists for the article. There is no `UNAVAILABLE` equivalent —
narration has one because the TTS provider can be unconfigured, whereas the chat model
is a hard dependency of the running application.

**Why the id is `articleId`-based and not content-addressed.** `Narration` fingerprints
the script text, which is correct for blogs: editing a post changes the hash, and the
stale audio is marked `STALE` automatically. Aggregated articles behave differently.
They are immutable snapshots of third-party content, but their *source text* comes from
a fresh re-scrape that varies between runs. Content-addressing would therefore produce
spurious cache misses and re-spend on every scrape drift. Keying on `articleId` plus a
`SUMMARY_FORMAT_VERSION` constant yields one stable summary per article, and bumping
that constant cleanly invalidates every summary when the prompt changes.

Indexes are created by a Mongock change unit — `auto-index-creation` is off in this
project, so `@Indexed` alone is decorative.

### API

| Endpoint | Auth | Behaviour |
|---|---|---|
| `POST /api/news/{articleId}/summary` | authenticated | Insert-first dedup, then generate |
| `GET /api/news/{articleId}/summary` | public | `?afterVersion=&waitSeconds=` long-poll |
| `GET /api/news/summaries/ids` | public | Article ids that have a `READY` summary |
| `POST /api/news/{articleId}/summary/narration` | authenticated | Queue TTS for the summary |
| `GET /api/news/{articleId}/summary/narration` | public | Existing `NarrationResponse` long-poll |

The `GET` long-poll contract is identical to `BlogNarrationController.getStatus`
(`afterVersion`, `waitSeconds` bounded `@Min(0) @Max(25)`), so the frontend polling
logic is shared rather than reimplemented.

`SecurityConfig` gains two matchers next to the existing favourites ones:

```java
.requestMatchers(HttpMethod.POST, "/api/news/*/summary").authenticated()
.requestMatchers(HttpMethod.POST, "/api/news/*/summary/narration").authenticated()
```

Reads are public because the artefact is globally shared; writes cost money, so they
need a session. Any valid JWT suffices — not admin-role gated, exactly as favourites
writes work.

The narration `POST` is authenticated even though the equivalent blog endpoint
(`/api/blogs/{blogId}/narration`) is public. A TTS render is the more expensive of the
two operations, and leaving it open would let an anonymous caller drain the
1,000,000 characters/month budget.

`GET /api/news/summaries/ids` sits on the same controller as `GET /api/news/{id}`.
Spring matches the literal `/summaries` segment ahead of the `{id}` template regardless
of declaration order — the same situation as the existing `/api/news/sources` endpoint,
whose javadoc already records this. Declare it before `/{id}` for readability.

It mirrors `GET /api/favourites/{type}/ids`. It is what lets
a logged-out visitor's card read **"Read summary"** and open instantly, versus
**"Summarise"** which triggers the login popup — the same way hearts render filled for
everyone but only toggle with a session.

### Generation flow

1. `POST` computes `id = sha256(SUMMARY_FORMAT_VERSION + articleId)` and attempts an
   insert with status `GENERATING`.
2. **Insert succeeds** → resolve source text, call the model, store the prose, return
   `200 READY` with the body.
3. **`DuplicateKeyException`** → another caller is generating. Return `202 GENERATING`;
   the client polls the `GET` until it flips.
4. **Existing `READY` doc** → return `200 READY` immediately, no spend.
5. **Existing `GENERATING` doc whose `updatedAt` is older than
   `generation-timeout`** → the generating process died. Reclaim it with a conditional
   `findAndModify` (guarded on both `status` and `updatedAt` so two reclaimers cannot
   both win), bump `version`, and generate. This is the entire crash-recovery story.

Failure modes are stored on the document rather than only returned, so a retry does not
silently re-spend:

| `failureCode` | Retryable | Cause |
|---|---|---|
| `INSUFFICIENT_SOURCE_TEXT` | no | Best available source text is under the hard floor |
| `MODEL_ERROR` | yes | Model call threw or returned blank |
| `ARTICLE_NOT_FOUND` | no | Article missing or not `visible` |

### Source text

`ArticleSectionWriter.sourceTextFor` — the re-scrape → `fullContent` → stored-blurb
cascade, with `MIN_USABLE_SOURCE_CHARS` (500, the "this is probably a consent wall, not
the body" floor), `HARD_MIN_SOURCE_CHARS` (200) and `MAX_SOURCE_CHARS` (12,000) — is
exactly what the summariser needs but is currently private to that class.

Extract it to a shared `ArticleSourceTextProvider` component. `ArticleSectionWriter`
becomes a consumer of it. This is the only change to existing aggregation code.

Below the 200-character hard floor the summary is stored `FAILED` /
`INSUFFICIENT_SOURCE_TEXT`, non-retryable, and the drawer says so plainly. The model is
not asked to invent five paragraphs from a feed snippet.

### Prompt

New `ArticleSummaryService` calling Embabel `Ai` with the model from
`aggregation.summary.model`. The prompt requires: neutral third person; 4–6 paragraphs;
Markdown; no heading (the drawer supplies it); does not restate the title; summarises
what the piece *says* rather than describing that it is an article.

`SUMMARY_FORMAT_VERSION` is a constant in the service, versioned alongside the prompt
text. Changing the prompt without bumping it will serve stale summaries.

### Narration generalisation

- `Narration.blogId` → `contentType` (`BLOG` | `ARTICLE_SUMMARY`) + `contentId`.
- Mongock change unit: backfill `contentType: BLOG`, copy `blogId` → `contentId`, unset
  `blogId`, replace index `idx_narration_blog_updated` with
  `idx_narration_content_updated` on `{contentType: 1, contentId: 1, updatedAt: -1}`.
- Extract a `NarrationSource` strategy — `scriptFor(contentId)` and
  `isCurrent(narration)` — with `BlogNarrationSource` and `ArticleSummaryNarrationSource`
  implementations. `BlogNarrationService` becomes `NarrationService`, resolving the
  source by `contentType` from a registry map.
- `BlogNarrationScriptBuilder` → `NarrationScriptBuilder`. It is already generic
  Markdown-stripping; the name is only blog-specific by accident. Its
  `FORMAT_VERSION` constant stays `blog-narration-v1` so existing blog narrations keep
  their fingerprints and are not invalidated by the rename.
- `/api/blogs/{blogId}/narration` keeps its current path and contract. No blog-side
  frontend change.
- The summary narration script is the article title plus the summary body, through the
  same `NarrationScriptBuilder`.
- `Narration`'s fingerprint stays content-addressed, so regenerating a summary yields a
  new narration id and marks the previous audio stale — that behaviour comes free.
- Audio still lands at `uploads/narrations/{id}/narration.mp3`, so
  `NarrationBackupCoverageTest` and `NarrationRestoreValidator` are unaffected. Confirm
  this at implementation rather than assuming it.

## Frontend

### Components

`NewsSummaryDrawer.tsx` in `components/news/`, following `CodeExampleDrawer`: existing
`drawer-overlay` / `drawer` CSS, Escape to close, click-outside to close, `body`
overflow lock while open.

Drawer contents, in order: source name and date; title linking to the original; an
**"AI-generated summary"** disclosure label; the prose; the audio panel; a "Read the
original" link; the heart.

The disclosure label is a requirement, not decoration. This is machine-written prose
about someone else's article, published on Simon's site under his name. A reader must
be able to tell at a glance that neither Simon nor the original author wrote it.

Closing the drawer unmounts the audio element, which stops playback — no extra handling
needed.

### Card trigger

A button beside `FavouriteButton` on each news card, `Sparkles` icon, two states driven
by the `/api/news/summaries/ids` set:

- summary exists → **"Read summary"**, opens the drawer immediately for everyone
- no summary → **"Summarise"**, runs the login popup first, then generates

The button is news-only. Events are not summarised.

### Two extractions in existing code

**`ensureAuthenticated` out of `useFavourites`.** The login-popup-then-confirm-with-a-token
sequence — including the comment explaining that `auth0-react` resolves even when the
popup is cancelled, so a session must be confirmed by actually obtaining a token — is
precisely what the Summarise button needs, and is currently private to that hook.
Extract to `useEnsureAuthenticated`. `useFavourites` consumes it and continues to
re-export it, so its existing callers are unchanged.

**The narration state machine out of `BlogNarration.tsx`.** That file is roughly 300
lines doing three separable jobs: long-poll orchestration with abort handling, a
seven-state render machine, and the audio player with its playback-speed control and
pause-other-tracks behaviour. All three are needed verbatim in the drawer. Split into a
`useNarration(endpointBase)` hook plus a `NarrationPanel` presentational component;
`BlogNarration` becomes a thin wrapper over both.

`BlogNarration.test.tsx` is the regression net for that refactor and must pass
untouched.

### Markdown rendering

The summary is model output, so it renders through the allowlisted link and image
renderers in `chat/linkPolicy.ts` — the same policy applied to chat answers — not
`react-markdown` defaults, and without `rehype-raw`.

## Configuration

```yaml
aggregation:
  summary:
    model: ${ARTICLE_SUMMARY_MODEL:gpt-5.6-luna}
    generation-timeout: 3m
    max-source-chars: 12000

rate-limit:
  summary:
    requests-per-minute: ${SUMMARY_RATE_LIMIT_REQUESTS_PER_MINUTE:5}
```

The rate limiter registers in `WebConfig` alongside the existing narration limiter.

## Testing

**Backend**

- Insert-first dedup: concurrent `POST` produces exactly one model call; the loser
  receives `GENERATING`.
- Existing `READY` document short-circuits with no model call.
- Source text under the hard floor → `FAILED` / `INSUFFICIENT_SOURCE_TEXT`,
  non-retryable, no model call.
- Stale `GENERATING` past `generation-timeout` is reclaimed; a fresh one is not.
- `POST` unauthenticated → 401; `GET` unauthenticated → 200.
- Long-poll returns promptly on a version change and on reaching a terminal state.
- `ArticleSourceTextProvider` extraction leaves `ArticleSectionWriter` behaviour
  unchanged (existing tests must pass).
- `NarrationService` drives both `BLOG` and `ARTICLE_SUMMARY` sources.
- Mongock change unit migrates `blogId` → `contentType` + `contentId` and is idempotent.
  This change unit performs no external I/O, so the standard change-unit test pattern
  applies rather than the isolated-boot pattern needed for units that do live I/O.

**Frontend**

- `useNarration` polling, abort-on-unmount, and terminal-state handling.
- `NewsSummaryDrawer` renders prose, disclosure label, error and loading states.
- Card button renders "Read summary" versus "Summarise" from the ids set.
- Logged-out "Summarise" triggers the login popup and does not call `POST` when the
  popup is dismissed.
- `BlogNarration.test.tsx` passes unchanged.

## Deployment note

`NARRATION_ENABLED` defaults to `false` in `application.yml`. If it is not already set
in production, summaries will work while summary audio reports "temporarily
unavailable". Confirm the flag and the `GOOGLE_CLOUD_TTS_*` values are set in the
production environment before shipping, rather than debugging it afterwards.

## Deferred scope

**Full-article audio** — narrating the article body rather than the summary — was
scoped out during brainstorming. Two reasons:

1. *Text quality.* `ArticleSectionWriter`'s own comments record that `fullContent`
   depth "varies — full page text for HTML and sitemap sources, often a bare feed
   snippet for RSS ones". Narrating a 300-character RSS snippet under a "Play full
   article" label is a promise the feature cannot keep, and the listener cannot tell
   until they have listened.
2. *Reproduction.* Generating and hosting a complete audio rendition of a third party's
   article on simonrowe.dev is a reproduction rather than a transformation, and a
   different posture from what the site does today, which is link out and show a blurb.
   Narrating our own generated summary avoids the question entirely.

The design leaves the seam open: `contentType` is an enum with room for an
`ARTICLE_FULL` value, and `NarrationSource` is the interface it would plug into. Adding
it later is additive. The text-quality floor and the reproduction question would both
need settling at that point.

**Event summaries** are out of scope. The button appears on news cards only.
