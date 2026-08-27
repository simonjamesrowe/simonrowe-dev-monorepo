# Phase 1 Data Model: Listen from the listing

**Date**: 2026-08-26 | **Plan**: [plan.md](./plan.md)

## No persistence change

This feature adds **no collection, no document field, no index and therefore no Mongock change
unit**. It is a new read projection over the existing `narrations` collection plus new frontend
state.

For the avoidance of doubt, in the terms this project cares about:

| Thing | Change |
|---|---|
| `narrations` document (`Narration.java`) | none |
| `@CompoundIndex` / `@Indexed` declarations | none — `idx_narration_content_updated` already covers the new query |
| Mongock change units | **none.** (`auto-index-creation` is off here, so a new index *would* have required one. None is added.) |
| `BackupService.BACKUP_COLLECTIONS` | none — no new collection |
| `RestoreService.IMPORT_ORDER_INDEPENDENT` | none |
| `NarrationScriptBuilder.FORMAT_VERSION` | **unchanged** — it stays the literal `blog-narration-v1`. It feeds the fingerprint that *is* the narration `_id`; changing it orphans every stored MP3. |

---

## Backend read projection

### `ReadyNarration`

A record, the aggregation's output type and the endpoint's response element.

```java
public record ReadyNarration(
    String contentId,       // for BLOG: the blog id. for ARTICLE_SUMMARY: the ARTICLE id.
    String audioUrl,        // Narration.audioPath(), e.g. "/uploads/narrations/{id}/narration.mp3"
    Long durationSeconds    // Narration.durationSeconds()
) {}
```

Field notes:

- **`contentId`** — for `ARTICLE_SUMMARY` this is the *aggregated article* id, not the summary id.
  `ArticleSummaryNarrationSource` establishes this ("The `contentId` is the *article* id, not the
  summary id: that is what the URL carries and what a caller knows"), which is what lets the news
  page key straight off ids it already holds with no join (FR-005).
- **`audioUrl`** — a site-relative path under `/uploads/`, served by the backend's
  `ResourceHandlerRegistry`. The frontend prefixes `API_BASE_URL` exactly as `NarrationPanel`'s
  `mediaUrl` helper does. Never null in a `READY` row (`markReady` sets it).
- **`durationSeconds`** — boxed `Long` to match `Narration.durationSeconds()`. Never null in a
  `READY` row, but the client formats defensively.

### Derivation

```text
match:   { contentType: <param>, status: "READY" }
sort:    { updatedAt: -1 }
group:   _id = "$contentId",
         audioUrl        = first("audioPath"),
         durationSeconds = first("durationSeconds")
project: contentId = "$_id", audioUrl, durationSeconds
```

Invariants this enforces:

- **At most one row per `contentId`** (FR-002) — narrations are fingerprint-addressed, so one
  content id accumulates rows over its lifetime; the `group ... first` after a descending `updatedAt`
  sort keeps only the newest.
- **`STALE`, `FAILED`, `UNCERTAIN`, `QUEUED`, `PROCESSING` are never advertised** — the `match`
  stage's `status: READY` predicate. A content id whose newest row is `STALE` and whose older row is
  `READY` still returns the `READY` row; that is correct — the audio file is still there and still
  playable, and the card offering it is better than the card pretending nothing exists.
- **Ordered by `idx_narration_content_updated`** — `{contentType: 1, contentId: 1, updatedAt: -1}`,
  already declared on `Narration`.

### Query parameter

`contentType` — required, one of `BLOG` | `ARTICLE_SUMMARY`, bound to the existing
`NarrationContentType` enum. An unrecognised value is a 400. `ARTICLE_FULL` does not exist yet and is
documented as deliberately deferred on the enum; when it arrives this endpoint accepts it with no
change.

---

## Frontend state

### Ready-audio map

Owned by `NarrationAudioProvider`. One entry per listenable item, keyed by content type and id.

```ts
type ReadyNarration = {
  contentId: string
  audioUrl: string
  durationSeconds: number
}

// keyed `${contentType}:${contentId}` so BLOG and ARTICLE_SUMMARY ids cannot collide
type ReadyMap = Map<string, ReadyNarration>
```

Populated once per provider mount — i.e. once per full page load, not once per route change, because
the provider sits above `<Routes>` — by two calls, one per content type. Written to again by the
chain's completion handler (FR-030). A failed fetch leaves it empty and every card reads "Listen"
(FR-006); it never blocks a list.

### Track

What the bar is currently on.

```ts
type NarrationTrack = {
  contentType: 'BLOG' | 'ARTICLE_SUMMARY'
  contentId: string
  title: string
  href: string                  // '/blogs/{id}' or the article's originalUrl
  audioUrl?: string             // present once known
  durationSeconds?: number      // present once known
}
```

`href` is supplied by the caller because only the card knows it: a blog track links to
`/blogs/{id}` (internal), a news track links to the article's `originalUrl` (external, so
`target="_blank" rel="noopener noreferrer"`).

### Provider state

```ts
type ChainStage = 'idle' | 'summarising' | 'narrating' | 'ready'

type NarrationAudioState = {
  track: NarrationTrack | null
  stage: ChainStage
  playing: boolean
  position: number              // seconds
  rate: number                  // one of PLAYBACK_SPEEDS
  error: { message: string; retryable: boolean } | null
}
```

State transitions driven by `listen(request)`:

| Starting condition | Stages traversed | Network |
|---|---|---|
| audio already in the ready map | `idle` → `ready` | none |
| blog, no audio | `idle` → `narrating` → `ready` | sign in, `POST /api/blogs/{id}/narration`, poll |
| news, summary exists, no audio | `idle` → `narrating` → `ready` | sign in, `POST /api/news/{id}/summary/narration`, poll |
| news, no summary | `idle` → `summarising` → `narrating` → `ready` | sign in, `POST /api/news/{id}/summary` (blocks 15–30s), then as above |
| any of the above fails | → `idle` with `error` set | — |

Rules:

- `listen()` for a different track **aborts** the in-flight one (FR-028) — one `AbortController` per
  chain, replaced on each call, exactly the `replaceController` pattern `useNarration` and
  `useArticleSummaries` both use.
- `position` and `rate` are mirrored from the detached `HTMLAudioElement`, which is the source of
  truth for playback; the provider subscribes to `timeupdate`/`play`/`pause`/`ended`/`error`.
- `error` is cleared on the next `listen()`.
- A separate, non-rendered `dismissedTrackKey` marks a track the reader closed mid-chain: it
  suppresses auto-play on completion but not the write to the ready map (FR-032).

### Derived per-card view

`ListenButton` reads three things through the provider, all keyed on `contentId`:

| View state | Condition | Renders |
|---|---|---|
| ready | ready map has the key | `▶ 12 min` |
| in flight | `track` matches this key **and** `stage` is `summarising`/`narrating` | spinner + stage label |
| cold | otherwise | `Listen`, secondary weight |

No local state in the button at all — that is what makes FR-010 hold across a filter change or a
"Load more".

### Published chain result

For FR-031. The provider exposes the last completed chain outcome:

```ts
lastCompleted: {
  contentType: 'BLOG' | 'ARTICLE_SUMMARY'
  contentId: string
  summaryWasGenerated: boolean
} | null
```

`NewsEventsPage` watches it and calls `summaries.noteSummarised(contentId)` when
`contentType === 'ARTICLE_SUMMARY' && summaryWasGenerated`. The provider does not — and cannot —
write to `useArticleSummaries`, which is mounted below it.

### `useArticleSummaries` addition

```ts
/** Records that an article now has a READY summary, without refetching the ids set. */
noteSummarised: (articleId: string) => void
```

Implemented as the existing local flip already inside `store()`:
`setSummarisedIds(prev => new Set(prev).add(articleId))`. Idempotent.
