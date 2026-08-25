# Implementation Plan: On-demand article summaries with audio

**Branch**: `simonrowe/article-ai-summary-audio` (feature id `034-article-summary-audio`) | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/034-article-summary-audio/spec.md`
**Authoritative design**: `docs/superpowers/specs/2026-08-24-article-summary-audio-design.md`

## Summary

Add an authenticated, on-demand, globally shared **in-depth summary** for any aggregated
news article, shown in a right-side drawer, with an on-demand **audio narration** of that
summary.

Most of the machinery already exists and is generalised rather than rebuilt:

- `ArticleSectionWriter`'s private source-text cascade (re-scrape → `fullContent` → stored
  blurb, with paywall/consent-wall length floors) is extracted to a shared
  `ArticleSourceTextProvider`.
- The `narration` package — a complete async TTS pipeline with Kafka queueing, lease-based
  claim, Google long-running operations, checksum-validated MP3 storage, a monthly
  character budget, a long-poll status API and a recovery scheduler — is generalised from
  `blogId` to `contentType` + `contentId` behind a `NarrationSource` strategy.
- Favourites supply the "globally shared artefact, public reads, authenticated writes"
  pattern, including the `ensureAuthenticated` login-popup sequence.
- `CodeExampleDrawer` supplies the drawer shell; `chat/linkPolicy.ts` supplies the
  allowlisted markdown renderers for model output.

Summary generation is **synchronous** with an insert-first dedup guard. Narration stays
async because Google's long-form TTS forces it; an LLM call has no operation handle to
poll, so reproducing lease/claim/recovery there would be infrastructure tracking a remote
operation that does not exist. The one property that machinery gives for free — never
paying twice — comes instead from a unique `_id` and the insert-first guard.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5.16 (web, security OAuth2 resource server,
data-mongodb, kafka, validation), Embabel `Ai` (`com.embabel.agent.api.common.Ai`) for the
inline LLM call, Mongock for indexes and migration, Bucket4j via the existing
`RateLimitInterceptor`, `commonmark` via the existing script builder, Google Cloud
Text-to-Speech via the existing `GoogleTextToSpeechProvider`. Frontend: `react-markdown`,
`lucide-react` (`Sparkles`, `X`, `Headphones`, `Loader2`), `@auth0/auth0-react` via the
existing `useAuth`. **No new dependencies in either module.**

**Storage**: MongoDB — one new collection `article_summaries`; one changed collection
`narrations` (`blogId` → `contentType` + `contentId`). Indexes via Mongock change units
(`auto-index-creation` is off, so `@Indexed`/`@CompoundIndex` alone are decorative). Audio
files stay at `uploads/narrations/{id}/narration.mp3`.

**Testing**: JUnit 5 + AssertJ + Mockito; `@SpringBootTest` integration tests extend
`AbstractIntegrationTest` (shared context, singleton `SharedMongoContainer`). Vitest +
Testing Library for the frontend. Checkstyle (Google Java Style) and JaCoCo (0.78 floor on
`backend`) gate the build.

**Target Platform**: Linux/ARM64 container (GraalVM native image) behind nginx; SPA served
from a separate container.

**Project Type**: Web application — Spring Boot backend + React frontend in one monorepo.

**Performance Goals**: A cached summary read returns in under 2 s (SC-001). A first-time
summary completes in under 45 s wall clock, of which ~15–30 s is the model call, held on a
virtual thread (`spring.threads.virtual.enabled: true`). Long-poll requests hold at most
25 s (`@Min(0) @Max(25)`) with a 500 ms internal poll, matching the narration contract.

**Constraints**:
- Exactly one model call per article per `SUMMARY_FORMAT_VERSION`, including under
  concurrency (SC-002).
- Zero model calls when source text is under the 200-character hard floor (SC-005).
- `NarrationScriptBuilder.FORMAT_VERSION` must stay the literal `blog-narration-v1`; it
  feeds the fingerprint that *is* the narration `_id`, so changing it orphans every stored
  blog MP3.
- `/api/blogs/{blogId}/narration` keeps its path, its public `POST` and its exact response
  contract. `BlogNarration.test.tsx` must pass **untouched** (SC-006).
- The 1,000,000 characters/month TTS budget is shared with blog narration.

**Scale/Scope**: ~2,600 visible aggregated articles; summaries are opt-in per article, so
the collection grows only with reader demand. Backend: 1 new package (~9 files), 2 changed
packages, 2 Mongock change units, 3 data-ops list edits. Frontend: 1 new drawer, 1 new
card button, 2 behaviour-preserving extractions, 1 new API service module.

## Constitution Check

*GATE: evaluated before Phase 0, re-evaluated after Phase 1 design. Constitution v1.11.0.*

| Principle | Gate | Verdict |
|---|---|---|
| I. Monorepo with separate containers | No new container; no runtime shared between backend and frontend; nginx routes unchanged (no new hostname, no new path prefix — `/api/news/**` already proxies). | **PASS** |
| II. Modern Java & React stack | Java 21, Spring Boot 3.5.x, Gradle, MongoDB primary store, Kafka for the async narration leg, Auth0 the sole auth provider, management port unchanged. Frontend: React 19, plain CSS with BEM in the single `styles.css`, no CSS framework or CSS-in-JS, Lucide icons. No new dependency in either module. No form introduced, so React Hook Form / Zod / reCAPTCHA do not apply. | **PASS** |
| III. Quality gates (NON-NEGOTIABLE) | Google Java Style via Checkstyle; JaCoCo 0.78 floor on `backend` — the new `summary` package and the narration refactor both carry unit + integration tests, so coverage moves up not down; SonarQube runs on the PR; CDX BOM unaffected (no new dependency). All `@SpringBootTest` tests extend `AbstractIntegrationTest`. `@MockitoBean` used only for `Ai` and `NarrationProvider` — both have their own dedicated tests. Frontend tests cover the four critical journeys named in the spec. | **PASS** |
| IV. Observability & operability | New Micrometer counters `article.summary.requests{result=generated\|reused\|deduped\|reclaimed\|failed}` and a `article.summary.generation.duration` timer, mirroring the existing `narration.requests` / `narration.generation.duration`. The Embabel `Ai` call traces to Langfuse through the existing instrumentation with no extra wiring. Structured logging on every failure path with `articleId`. No SSH needed to diagnose: `failureCode` is persisted and served on the wire. | **PASS** |
| V. Simplicity & incremental delivery | Synchronous generation is the *simpler* option — it avoids importing the Kafka/lease/recovery machinery whose justification (a pollable remote operation handle) does not exist for an LLM call. The `NarrationSource` abstraction has two implementations on day one, so it is not premature; `ARTICLE_FULL` room in the enum is a zero-cost seam, not speculative code. Delivered as four independently testable increments (see Increments below). | **PASS** |
| VI. Admin CMS UX standards | No admin CMS surface touched. | **N/A** |
| VII. Interactive site tour | No tour step added or changed. `tour-news-events` on the news page container is untouched. | **N/A** |
| VIII. Backup & restore | `scripts/backup.sh` uses `mongodump` on the whole database, so the new collection is captured automatically. The admin Data Ops path enumerates collections by name, so `article_summaries` is added to `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`, with post-restore index recreation. Narration audio backup coverage is unaffected — the audio path derives from the fingerprint, which is unchanged. | **PASS** |
| IX. Shell scripting standards | No script added or changed. | **N/A** |

**Result: PASS, no violations, Complexity Tracking section omitted.** Re-evaluated after
Phase 1 — the design produced no new gate pressure. One correction the gate surfaced is
folded into the plan: the design document omits the data-ops list registration
(Principle VIII), without which a restore silently discards every paid-for summary.

## Project Structure

### Documentation (this feature)

```text
specs/034-article-summary-audio/
├── plan.md                              # This file
├── spec.md                              # Feature specification
├── research.md                          # Phase 0: decisions + code findings
├── data-model.md                        # Phase 1: collections, states, migration
├── quickstart.md                        # Phase 1: how to run and verify
├── contracts/
│   └── article-summary-api.yaml         # Phase 1: OpenAPI 3.1 for the five endpoints
├── checklists/
│   └── requirements.md                  # Spec quality checklist
└── tasks.md                             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/
├── summary/                                   # NEW package
│   ├── ArticleSummary.java                    # @Document, mutable, state transitions
│   ├── SummaryStatus.java                     # GENERATING | READY | FAILED
│   ├── ArticleSummaryRepository.java          # MongoRepository
│   ├── ArticleSummaryResponse.java            # wire record, adds NOT_REQUESTED
│   ├── ArticleSummaryService.java             # dedup + reclaim + Embabel Ai call + long-poll
│   ├── ArticleSummaryController.java          # POST/GET /api/news/{id}/summary, GET /summaries/ids
│   └── SummaryNarrationController.java        # POST/GET /api/news/{id}/summary/narration
├── aggregation/
│   └── ArticleSourceTextProvider.java         # NEW: extracted from ArticleSectionWriter
├── agents/
│   └── ArticleSectionWriter.java              # CHANGED: consumes the provider
├── narration/
│   ├── NarrationContentType.java              # NEW: BLOG | ARTICLE_SUMMARY
│   ├── NarrationSource.java                   # NEW: scriptFor(contentId), isCurrent(narration)
│   ├── BlogNarrationSource.java               # NEW
│   ├── ArticleSummaryNarrationSource.java     # NEW
│   ├── NarrationService.java                  # RENAMED from BlogNarrationService, source registry
│   ├── NarrationScriptBuilder.java            # RENAMED from BlogNarrationScriptBuilder
│   ├── Narration.java                         # CHANGED: contentType + contentId
│   ├── NarrationRepository.java               # CHANGED: findByContentTypeAndContentId
│   ├── NarrationRequestConsumer.java          # CHANGED: source-driven, no BlogRepository
│   ├── NarrationContentChangeConsumer.java    # CHANGED: NarrationService
│   ├── NarrationRestoreValidator.java         # CHANGED: content index set
│   └── BlogNarrationController.java           # CHANGED: delegates to NarrationService (contract frozen)
├── migration/changeunits/
│   ├── V020CreateArticleSummaryIndexes.java   # NEW
│   └── V021GeneraliseNarrationContentType.java # NEW
├── dataops/
│   ├── BackupService.java                     # CHANGED: + "article_summaries"
│   └── RestoreService.java                    # CHANGED: + collection + index recreation
├── ratelimit/RateLimitConfig.java             # CHANGED: + summary bucket
├── ratelimit/RateLimitInterceptor.java        # CHANGED: + summary path predicate
├── auth/SecurityConfig.java                   # CHANGED: two POST matchers
└── WebConfig.java                             # CHANGED: + summary interceptor paths

backend/src/main/resources/application.yml     # CHANGED: aggregation.summary.*, rate-limit.summary.*

backend/src/test/java/com/simonrowe/
├── summary/
│   ├── ArticleSummaryServiceTest.java         # dedup, reclaim, floors, no-respend
│   ├── ArticleSummaryConcurrencyTest.java     # exactly one model call under concurrency
│   ├── ArticleSummaryControllerTest.java      # auth matrix, status codes, long-poll
│   └── SummaryNarrationControllerTest.java
├── aggregation/ArticleSourceTextProviderTest.java
├── narration/                                 # existing tests renamed/adjusted
├── migration/V021GeneraliseNarrationContentTypeTest.java
└── dataops/NarrationBackupCoverageTest.java   # CHANGED: constructor call site only

frontend/src/
├── components/news/
│   ├── NewsSummaryDrawer.tsx                  # NEW
│   └── SummaryButton.tsx                      # NEW: Sparkles, two labels
├── components/narration/
│   ├── NarrationPanel.tsx                     # NEW: extracted render machine + player
│   └── useNarration.ts                        # NEW: extracted long-poll + abort
├── components/blog/BlogNarration.tsx          # CHANGED: thin wrapper (test untouched)
├── hooks/
│   ├── useEnsureAuthenticated.ts              # NEW: extracted from useFavourites
│   ├── useFavourites.ts                       # CHANGED: consumes + re-exports it
│   └── useArticleSummaries.ts                 # NEW: ids set + request/poll per article
├── services/articleSummaryApi.ts              # NEW
├── types/articleSummary.ts                    # NEW
├── pages/NewsEventsPage.tsx                   # CHANGED: button + drawer wiring
└── styles.css                                 # CHANGED: news-summary__* BEM block

frontend/tests/
├── components/news/NewsSummaryDrawer.test.tsx # NEW
├── components/narration/useNarration.test.ts  # NEW
├── hooks/useArticleSummaries.test.ts          # NEW
└── pages/NewsEventsPage.test.tsx              # CHANGED: button label cases
```

**Structure Decision**: Existing monorepo layout, unchanged. The new backend code lives in
a new `com.simonrowe.summary` package rather than inside `aggregation` or `narration`,
because it depends on both and belongs to neither — `aggregation` owns article ingest and
`narration` owns audio. `ArticleSourceTextProvider` goes in `aggregation` (it is about
aggregated-article source text, and `agents` already depends on `aggregation`, not the
reverse). The extracted narration frontend pieces go in a new
`frontend/src/components/narration/` directory since they are now shared between the blog
page and the news drawer.

## Phase 2 approach: four independently testable increments

Ordered so each one is shippable and verifiable on its own, and so the riskiest change
(the narration refactor, whose regression net is an existing test suite) lands before
anything depends on it.

**Increment A — Source text extraction (pure refactor, no behaviour change).**
Extract `ArticleSourceTextProvider` from `ArticleSectionWriter`, with the three length
constants and the cascade moved verbatim. Verification: `ArticleSectionWriterTest` passes
unchanged, plus new direct tests on the provider.

**Increment B — Narration generalisation (refactor + migration, no behaviour change).**
`contentType`/`contentId`, `NarrationSource` with `BlogNarrationSource`, the two renames,
`V021`, `NarrationRestoreValidator` index set. `ArticleSummaryNarrationSource` is added in
Increment D. Verification: every existing narration test passes (adjusted only for the
renamed types and the new constructor arity), `BlogNarration.test.tsx` untouched, and the
change unit's own idempotency test.

**Increment C — Summaries, backend + frontend (US1, US3, US4).**
`ArticleSummary`, `SummaryStatus`, repository, response, service, controller, `V020`,
`SecurityConfig`, rate limiter, config, data-ops lists. Then
`useEnsureAuthenticated`/`useArticleSummaries`, `articleSummaryApi`, `SummaryButton`,
`NewsSummaryDrawer` (without the audio panel), `NewsEventsPage` wiring, CSS. This is the
MVP: a complete, valuable feature with no audio.

**Increment D — Summary audio (US2).**
`ArticleSummaryNarrationSource`, `SummaryNarrationController`, the `useNarration` /
`NarrationPanel` extraction with `BlogNarration` reduced to a wrapper, and the panel
mounted in the drawer.

Increment D is where the design's deployment note bites: `NARRATION_ENABLED` defaults to
`false` in `application.yml`. If it is not set in production, summaries work and summary
audio reports "temporarily unavailable". Confirm the flag and the `GOOGLE_CLOUD_TTS_*`
values are set in the production environment **before** shipping D, rather than debugging
it afterwards.

## Risks and how the plan addresses them

| Risk | Mitigation |
|---|---|
| The narration rename breaks existing blog audio | `FORMAT_VERSION` stays `blog-narration-v1`; the fingerprint, and therefore the `_id` and the MP3 path, are untouched. `BlogNarration.test.tsx` and the seven backend narration test classes are the regression net, and Increment B is gated on them passing. |
| `V021` leaves a restored database without the content index | `NarrationRestoreValidator.ensureIndexes()` is updated in the same increment. A restore drops collections, so that method — not Mongock — is what puts indexes back. |
| Summaries are lost on the next restore | `article_summaries` added to `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`, with index recreation. Not in the design doc; surfaced by the Principle VIII gate. |
| Two reclaimers both revive one stale `GENERATING` document | The conditional `findAndModify` is guarded on **both** `status` and `updatedAt`, so the loser's filter no longer matches. Covered by `ArticleSummaryConcurrencyTest`. |
| A 30-second synchronous request exhausts the request pool | Virtual threads are enabled; the rate limiter caps generation at 5/min per IP; and the second caller for the same article gets an immediate `202` rather than a second held thread. |
| Model output smuggles a live link or image into the page | Rendered through `chat/linkPolicy.ts`'s allowlisted `a`/`img` renderers with no `rehype-raw`. The prompt forbids links, so anything the model invents degrades to plain text or is dropped. |
| Readers mistake machine prose for Simon's writing | The "AI-generated summary" disclosure label is a functional requirement (FR-025, SC-003) with its own test, not styling. |
| Prompt edited without bumping `SUMMARY_FORMAT_VERSION` → stale summaries served forever | The constant lives immediately adjacent to the prompt text in `ArticleSummaryService` with a comment stating the coupling, and a test asserts the id derives from it. |

## Phase 1 outputs

- [research.md](./research.md) — 11 carried decisions with rationale, 8 findings from the
  existing code including three gaps the design does not cover
- [data-model.md](./data-model.md) — `article_summaries` fields/states/indexes, the
  `narrations` change, both migrations, data-ops registration
- [contracts/article-summary-api.yaml](./contracts/article-summary-api.yaml) — OpenAPI 3.1
  for all five endpoints, with the auth matrix and status-code semantics
- [quickstart.md](./quickstart.md) — how to run, migrate and verify end to end
- `CLAUDE.md` — agent context updated by `.specify/scripts/bash/update-agent-context.sh claude`
