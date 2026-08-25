# Phase 0 Research: On-demand article summaries with audio

**Feature**: `034-article-summary-audio`
**Date**: 2026-08-24
**Source of truth**: `docs/superpowers/specs/2026-08-24-article-summary-audio-design.md`

The design document already settles the load-bearing decisions. This file records
those decisions with their rationale, plus the findings from reading the existing
code that the design generalises — including three gaps the design does not cover.

## Decisions carried from the design document

### D1 — Summaries are globally shared, one per article per format version

**Decision**: `article_summaries` holds one document per `sha256(SUMMARY_FORMAT_VERSION + articleId)`.
No per-user scoping.

**Rationale**: Matches the favourites precedent (`V014MakeFavouritesGlobal`), and one LLM
call plus one TTS render per article, ever, is the cost model the feature has to fit.

**Alternatives rejected**: Per-user summaries (multiplies cost by readership for no
personalisation benefit).

### D2 — The document id is article-keyed, not content-addressed

**Decision**: `id = sha256(SUMMARY_FORMAT_VERSION + articleId)`.

**Rationale**: `Narration`'s content-addressed fingerprint is right for blogs — editing a
post changes the hash and `markStale` fires automatically. Aggregated articles are
immutable snapshots whose *source text* comes from a fresh re-scrape that varies run to
run. Content-addressing the source text would produce a spurious cache miss (and a
re-spend) on every scrape drift. Article-keying gives one stable summary; bumping
`SUMMARY_FORMAT_VERSION` invalidates all of them at once when the prompt changes.

**Alternatives rejected**: Fingerprinting the resolved source text (re-spends on drift);
using the raw `articleId` as the id (no clean prompt-version invalidation).

### D3 — Generation is synchronous, guarded by an insert-first dedup

**Decision**: `POST` inserts a `GENERATING` document, then calls the model inline on the
request thread and returns `200 READY` with the body. A `DuplicateKeyException` means
someone else is generating: return `202 GENERATING` and let the client long-poll.

**Rationale**: The narration pipeline is async through Kafka with leases, claims and a
recovery scheduler because Google's long-form TTS *forces* it — the provider hands back a
long-running operation name that must be polled and must survive restarts. An LLM call
has no such handle; it is one blocking call of roughly 15–30 seconds. Every other LLM call
in this codebase is inline (`ChatService` per request, `ContentAggregationAgent` and
`WeeklyDigestAgent` on scheduler threads), and `spring.threads.virtual.enabled: true`
makes holding a virtual thread for 30 seconds cheap. Reproducing lease/claim/recovery here
would be infrastructure to track a remote operation that does not exist. The one property
that machinery gives for free — never paying twice — comes instead from a unique `_id` and
the insert-first guard.

**Alternatives rejected**: A Kafka queue mirroring narration (all the cost of the
machinery, none of the justification); optimistic read-then-insert (loses the race and
double-spends).

### D4 — Crash recovery is a timeout-guarded conditional reclaim

**Decision**: A `GENERATING` document whose `updatedAt` is older than
`aggregation.summary.generation-timeout` (3m) is reclaimed by a conditional
`findAndModify` guarded on **both** `status` and `updatedAt`, so two concurrent reclaimers
cannot both win. `version` is bumped so long-pollers wake.

**Rationale**: This is the whole crash-recovery story, and it is ~15 lines rather than a
scheduler. Guarding on `updatedAt` as well as `status` is what makes it safe: guarding on
`status` alone lets two reclaimers both match a `GENERATING` document.

### D5 — Failures are persisted on the document, with a retryable flag

**Decision**: `failureCode` ∈ {`INSUFFICIENT_SOURCE_TEXT` (not retryable),
`MODEL_ERROR` (retryable), `ARTICLE_NOT_FOUND` (not retryable)} plus a `retryable` boolean.

**Rationale**: Stored rather than only returned, so a repeat `POST` on a non-retryable
failure does not silently re-spend. Mirrors `Narration.markFailed(code, canRetry, now)`.

### D6 — Narration generalises to `contentType` + `contentId`, blog behaviour frozen

**Decision**: `Narration.blogId` becomes `contentType` (`BLOG` | `ARTICLE_SUMMARY`) plus
`contentId`. A `NarrationSource` strategy (`scriptFor(contentId)` / `isCurrent(narration)`)
gets `BlogNarrationSource` and `ArticleSummaryNarrationSource` implementations, resolved
from a registry map by `contentType`. `BlogNarrationService` → `NarrationService`;
`BlogNarrationScriptBuilder` → `NarrationScriptBuilder`.

**Rationale**: The pipeline is already generic apart from the blog coupling.
`BlogNarrationScriptBuilder` is pure Markdown-stripping — its name is blog-specific by
accident. Two hard constraints:

- `NarrationScriptBuilder.FORMAT_VERSION` **stays** `blog-narration-v1`. It feeds the
  fingerprint, which is the narration `_id`. Renaming it would change every existing blog
  narration's id, orphaning the stored MP3s.
- `/api/blogs/{blogId}/narration` keeps its path, its public (unauthenticated) `POST`, and
  its exact response contract. No blog-side frontend change; `BlogNarration.test.tsx`
  passes untouched.

**Alternatives rejected**: A second parallel narration collection for summaries (two
recovery schedulers, two budget accountings, two restore validators).

### D7 — Summary narration `POST` is authenticated; the blog one stays public

**Decision**: `POST /api/news/{articleId}/summary` and
`POST /api/news/{articleId}/summary/narration` both require any valid JWT.
`POST /api/blogs/{blogId}/narration` stays public.

**Rationale**: Both new writes cost money. A TTS render is the more expensive of the two
and the monthly budget is 1,000,000 characters — leaving it open lets an anonymous caller
drain it. The blog endpoint is left alone because changing it is a behaviour change the
design explicitly excludes. Reads are public because the artefact is globally shared,
exactly as favourites work.

### D8 — Source text extraction is a shared component, not a copy

**Decision**: Extract `ArticleSectionWriter`'s private `sourceTextFor` cascade
(re-scrape → `fullContent` → stored blurb, with `MIN_USABLE_SOURCE_CHARS` = 500,
`HARD_MIN_SOURCE_CHARS` = 200, `MAX_SOURCE_CHARS` = 12,000) into a shared
`ArticleSourceTextProvider` component. `ArticleSectionWriter` becomes a consumer.

**Rationale**: It is exactly what the summariser needs and it encodes hard-won knowledge
about paywall and consent-wall interstitials. Copying it would fork that knowledge. This
is the only change to existing aggregation code, and `ArticleSectionWriterTest` is the
regression net.

### D9 — Right-side drawer, audio stops on close

**Decision**: `NewsSummaryDrawer` following `CodeExampleDrawer` (existing
`drawer-overlay`/`drawer` CSS, Escape to close, click-outside, `body` overflow lock).

**Rationale**: The news page holds source filters, backend paging and a favourites toggle —
state a navigation would discard. Unmounting the drawer unmounts the `<audio>` element,
which stops playback for free; there is no persistent mini-player to hand playback to, and
audio continuing with no visible controls is a bad surprise.

### D10 — Two frontend extractions, both behaviour-preserving

**Decision**:
- `ensureAuthenticated` out of `useFavourites` into `useEnsureAuthenticated`.
  `useFavourites` consumes it and continues to re-export it, so its callers are unchanged.
- `BlogNarration.tsx` (~300 lines, three separable jobs) splits into a
  `useNarration(endpointBase)` hook (long-poll orchestration + abort handling) and a
  `NarrationPanel` presentational component (seven-state render machine, audio player,
  playback-speed control, pause-other-tracks). `BlogNarration` becomes a thin wrapper.

**Rationale**: All of it is needed verbatim in the drawer. The `ensureAuthenticated`
comment about `auth0-react` resolving even when the popup is cancelled — so a session must
be confirmed by actually obtaining a token — is precisely the trap the Summarise button
would otherwise fall into. `BlogNarration.test.tsx` must pass untouched as the regression
net for the split.

### D11 — Summary prose renders through the chat link/image policy

**Decision**: Render with the allowlisted `a`/`img` renderers from `chat/linkPolicy.ts`;
no `rehype-raw`.

**Rationale**: The prose is model output published on Simon's site. The same policy that
stops a chat answer fabricating a live link applies. The summary prompt forbids links, so
the practical effect is that any link the model invents degrades to plain text and any
image is dropped — which is the desired failure mode.

## Findings from reading the existing code

### F1 — `NarrationRestoreValidator.ensureIndexes()` hardcodes `blogId`

`backend/src/main/java/com/simonrowe/narration/NarrationRestoreValidator.java` recreates
narration indexes after a restore, naming `blogId` and `idx_narration_blog_updated`
explicitly. A restore drops and reinserts collections, so this method — not the Mongock
change unit — is what puts indexes back post-restore. It must be updated in lockstep with
the change unit, or a restored production database silently loses the content index.

### F2 — The new collection is absent from every data-ops list *(design-doc gap)*

`BackupService.BACKUP_COLLECTIONS`, `RestoreService.IMPORT_ORDER_INDEPENDENT` and
`RestoreService`'s post-restore index recreation all enumerate collections by name.
`article_summaries` is in none of them, so as written the design ships a paid-for artefact
that no backup captures and no restore returns.

**Decision**: Add `article_summaries` to `BACKUP_COLLECTIONS` and to
`IMPORT_ORDER_INDEPENDENT` (it holds no `@DBRef`; it points at `aggregated_articles` by
plain id, exactly like `favourites`), and recreate its `articleId` index after restore the
way `ensureFavouriteIndexes()` does. Leave `ClearService.COLLECTIONS` alone —
`aggregated_articles`, the parent content, is not cleared either, so clearing derived
summaries would strand the feature against content that is still there.

### F3 — `NarrationBackupCoverageTest` constructs `Narration` positionally

`backend/src/test/java/com/simonrowe/dataops/NarrationBackupCoverageTest.java:84` calls
`new Narration("narration-1", "blog-1", 100, "voice", "en-GB", "MP3", "narrations/...", now)`.
Changing the constructor to take `contentType` + `contentId` breaks it at compile time —
which is the desired outcome (loud, not silent). The design's instruction to "confirm the
audio path is unaffected rather than assuming it" resolves as: **confirmed unaffected**.
`NarrationStorage.store` derives the path from `narration.id()` alone
(`uploads/narrations/{id}/narration.mp3`), and the id is the fingerprint, which is
unchanged. Only the constructor call site needs editing.

### F4 — The rate limiter matches paths by hand-rolled string surgery

`RateLimitInterceptor.isNarrationPath` does `startsWith("/api/blogs/")` +
`endsWith("/narration")` + a no-slash check on the extracted id, and `WebConfig` registers
`addPathPatterns("/mcp/**", "/api/blogs/*/narration")`. A summary bucket follows the same
shape: register `/api/news/*/summary` and `/api/news/*/summary/narration`, and add a
matching predicate. Note the two summary paths are prefix-overlapping, so the predicate
must test the narration suffix **before** the bare-summary suffix.

### F5 — `NarrationRequestConsumer` is blog-coupled in three places

It injects `BlogRepository` directly, calls `blogRepository.findByIdAndPublishedTrue(narration.blogId())`,
and calls `narrationService.descriptor(blog)`. All three collapse into
`NarrationSource.scriptFor(contentId)` / `isCurrent(narration)`, which is what makes the
consumer content-type-agnostic. `NarrationContentChangeConsumer` keeps its
`ContentChangeEvent.ContentType.BLOG` filter — aggregated articles are immutable, so no
`AGGREGATED_ARTICLE` invalidation path is needed.

### F6 — `NewsController` already documents the literal-vs-template ordering

`NewsController.listSources()`'s javadoc records that Spring matches the literal
`/sources` segment ahead of the `{id}` template regardless of declaration order.
`GET /api/news/summaries/ids` is the identical situation; declare it before `/{id}` for
readability and reference the existing javadoc rather than restating the reasoning.

### F7 — Embabel `Ai` is the established inline-LLM injection point

`ArticleSectionWriter`, `DigestComposer`, `DigestMetadataGenerator` and
`ContentAggregationAgent` all inject `com.embabel.agent.api.common.Ai` and call
`ai.withLlm(model).respond(List.of(new UserMessage(prompt))).getContent()`, with the model
name from a `@Value` property. `ArticleSummaryService` follows that pattern exactly with
`aggregation.summary.model`.

### F8 — `NARRATION_ENABLED` defaults to `false`

`application.yml:168` is `enabled: ${NARRATION_ENABLED:false}`. When the provider is not
configured, `NarrationResponse.unavailable()` is returned and the drawer says "temporarily
unavailable" — summaries still work. This is a deployment checklist item, not a code
concern, but it must be verified in the production environment before shipping rather than
debugged afterwards.

## Resolved unknowns

No `NEEDS CLARIFICATION` markers remain. Everything the spec left open is fixed by the
design document; the three items the design did not cover (F1, F2, F4) are resolved above.
