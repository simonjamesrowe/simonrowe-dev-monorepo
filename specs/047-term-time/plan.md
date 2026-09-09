# Implementation Plan: Term Time

**Branch**: `047-term-time` (scaffolded on `simonrowe/school-agentic-chat` — see Deviations)

**Date**: 2026-09-08

**Spec**: [spec.md](./spec.md)

## Summary

A school-question assistant for Kilmorie Primary School, served at `simonrowe.dev/school`.

Public tier (no auth) answers from the school's own openly published data: the calendar JSON/iCal
feeds, website pages and PDFs. Restricted tier (Auth0 `school` role) additionally answers from the
school mailbox. The tier a request may reach is resolved server-side and enforced at two independent
boundaries.

Dated questions are answered from **structured extracted facts**, not similarity search — the
corpus contains contradictory dates across academic years, and embeddings have no notion of "this
week". Prose questions use retrieval. Both cite source and date, and the assistant refuses rather
than guesses.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 4.1.1, Spring AI 2.0.1 (OpenAI chat + embeddings, Elasticsearch
vector store), Spring Data MongoDB, Spring Kafka, Mongock 5.5.1, Auth0 (OAuth2 resource server),
Vite (multi-entry), Lucide React. **One new dependency: Apache PDFBox** — the school publishes term
dates, the enrichment timetable and lunch menus as PDFs, and the existing `openpdf` is a *writing*
library. PDFBox rather than Tika: a large transitive tree for one file type is not justified.

**Storage**: MongoDB — three new collections (`school_documents`, `school_events`,
`school_sync_state`). Elasticsearch — one new index, `school-embeddings`, separate from
`content-embeddings`. Indexes via Mongock change unit; `auto-index-creation` is off, so
`@CompoundIndex` alone is decorative.

**Testing**: JUnit 6 + Testcontainers 2 via `AbstractIntegrationTest` (shared Mongo container),
Vitest for frontend, a compose-parsing test in the `DeployerLinearCredentialTest` style, and
`evals/` cases for refusal and source-precedence behaviour.

**Target Platform**: Existing `backend` container; existing `frontend` nginx container (second Vite
entry point, **no new container**).

**Project Type**: Web application — existing backend module, existing frontend module.

**Performance Goals**: Not latency-critical. Ingestion runs on a schedule; the school website
requires a 10-second crawl delay, so page re-crawl is change-driven off its RSS `pubDate` feeds
rather than a full sweep.

**Constraints**: Anonymous endpoint on a public domain — per-client rate limiting plus a hard global
daily spend ceiling. Corpus is small (642 school emails all time, 17 calendar events for 2026/27),
so ingestion cost is negligible and answer coverage, not throughput, is the limiting factor.

**Scale/Scope**: One school, one restricted-tier user, seven year groups, four source types.

## Constitution Check

*GATE: evaluated against `.specify/memory/constitution.md`.*

| Principle | Status | Notes |
|---|---|---|
| I. Monorepo, separate containers | PASS | Backend and frontend stay separate. The school UI is a second Vite entry in the **existing frontend** container — two frontends in one container is not the prohibition, which is backend/frontend sharing a runtime. |
| II. Modern Java & React stack | PASS | Mongo primary store, Kafka for the async embed step, Elasticsearch for retrieval, Auth0 sole auth with no self-service registration. Plain CSS with BEM. |
| III. Quality gates (NON-NEGOTIABLE) | PASS | Google Java Style, JaCoCo floor, Sonar, CDX. Integration tests extend `AbstractIntegrationTest`. Frontend tests for the critical journey. |
| IV. Observability & operability | PASS | Langfuse tracing via the existing observation plumbing; ingestion failures and credential revocation are operator-visible. |
| V. Simplicity & incremental delivery | PASS | Three independently testable user stories, P1 shippable alone. **Watch item:** the spec requires retaining raw restricted content, which is persistence beyond a current read path — justified below. |
| VI. Admin CMS UX standards | PASS | Approval queue follows existing admin list conventions (Lucide status icons). |
| VII. Interactive site tour | N/A | Separate app, not part of the tour. |
| VIII. Backup & restore | PASS | All three collections added to `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`; `ElasticsearchBackupService` extended for the new index. |
| IX. Shell scripting standards | PASS | `scripts/termtime-gmail-auth.py` is one-time credential tooling, not runtime. |

**Principle V justification for retaining raw content**: Principle V forbids persisting data that is
never queried. Raw restricted content *is* queried — by reclassification. Without it, changing the
classifier or the staff-name list requires a full Gmail resync, and `historyId` has no guaranteed
retention window, so that resync is not reliably available. The read path is real, just infrequent.

**Deviation from process, recorded**: `.specify/scripts/bash/create-new-feature.sh` performs
`git checkout -b`. This workspace's branch is managed by Conductor, so the feature directory and
`.specify/feature.json` were scaffolded by hand on the existing branch instead. No other step of the
speckit flow is affected.

## Project Structure

### Documentation (this feature)

```text
specs/047-term-time/
├── spec.md
├── plan.md              # This file
├── data-model.md
└── tasks.md
```

### Source Code

```text
backend/src/main/java/com/simonrowe/school/
├── SchoolProperties.java              # school.* config, all flags default off
├── ingest/
│   ├── CalendarFeedClient.java        # calendar JSON API + iCal fallback
│   ├── WebsiteCrawler.java            # sitemap + RSS pubDate change detection
│   ├── PdfTextExtractor.java          # PDFBox
│   ├── GmailClient.java               # history.list cursor, full-resync tolerant
│   ├── SchoolIngestScheduler.java     # @Scheduled, mirrors AggregationScheduler
│   └── SenderAllowlist.java           # address-based; display names never trusted
├── classify/
│   ├── TierClassifier.java            # proposes only; never promotes
│   ├── StaffNameGate.java             # forces RESTRICTED; overrides proposals
│   └── EventExtractor.java            # dated facts + academic year
├── model/                             # SchoolDocument, SchoolEvent, SchoolSyncState, Visibility
├── retrieval/
│   ├── SchoolAudience.java            # server-resolved; never client-supplied
│   ├── SchoolVectorSearch.java        # tier filter on school-embeddings
│   └── SourcePrecedence.java
├── chat/
│   ├── SchoolChatConfig.java          # separate ChatClient; private tools absent from public one
│   ├── SchoolTools.java               # getTermDates, getEventsInRange, getClubs, searchSchoolInfo
│   ├── SchoolTopicGuardrail.java
│   └── PublicAnswerNameCheck.java     # output-side backstop, public tier only
├── admin/SchoolApprovalController.java
└── SchoolChatController.java

backend/src/main/java/com/simonrowe/migration/V030CreateSchoolCollections.java

frontend/
├── index.html                         # existing app (unchanged)
├── school/index.html                  # new entry point
├── src/school/                        # new app: App, ChatPanel, YearSelector, Disclaimer
└── vite.config.ts                     # rollupOptions.input

frontend/nginx.conf                    # + location /school/
```

## Key Design Decisions

1. **Two enforcement boundaries, one source of truth.** `SchoolAudience` is built once per request
   from the authenticated principal. The public `ChatClient` is a *different bean* with the private
   tools physically absent — not the same client with tools filtered. A build-failing test enumerates
   every tool and retrieval path and asserts each declares a tier. Rationale: `FactoryTokenAuthenticator`
   is a plain `@Component` each controller calls for itself, and `FactoryStatusController` documents
   what happens when a new mapping lands on the wrong controller and silently inherits its posture.

2. **A separate Elasticsearch index is defence in depth, not tidiness.** Sharing
   `content-embeddings` would mean every portfolio chat turn retrieves against school mail, with a
   metadata filter as the only separation.

3. **Fail-closed tiering.** `Visibility` defaults to `RESTRICTED` at construction. The classifier
   returns a *proposal* on a separate field. Only an explicit approval writes `PUBLIC`.

4. **Dated facts are extracted, not retrieved.** `SchoolEvent` carries an academic year, and
   `SourcePrecedence` (calendar feed > newsletter > website page > PDF) resolves conflicts. Without
   this the assistant answers "when is half term" from the website page that still shows 2025-26.

5. **Model configuration is per-`ChatClient`, never in `application.yml`.** Chat defaults are merged
   into every per-call `OpenAiChatOptions` — the reason `reasoning-effort` is banned in yml. Same
   applies to `promptCacheKey`, which must differ between the two bots. Chat model defaults to
   `gpt-5.6-luna`; the guardrail moves off the hardcoded `gpt-4o-mini` to `gpt-5-nano`.

6. **The system prompt is deliberately substantial** — source precedence, academic-year rules,
   citation format, refusal instruction, untrusted-data delimiters. It must clear OpenAI's 1,024-token
   cache floor; below it nothing caches and no error is raised. Verify with
   `Usage.getCacheReadInputTokens()`, do not assume.

7. **Credential confinement.** `GMAIL_*`/`SCHOOL_*` variables reach `backend` only. A compose-parsing
   test fails the build if any appear under `software-factory` or `deployer`.

## Phasing

| Phase | Delivers | Independently shippable |
|---|---|---|
| P1 | Website + calendar ingestion, public tier, chat UI, year selector | Yes — no credentials, no approval queue |
| P2 | Gmail ingestion, classification, restricted tier, Auth0 `school` role | Yes |
| P3 | Admin approval queue, promotion to public | Yes |

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| Second Elasticsearch index | Structural tier separation | A metadata filter on the shared index is one forgotten clause away from the portfolio bot serving school mail |
| Structured event extraction | Dated questions and cross-year conflicts | Pure RAG answers "when is half term" from a year-stale page, confidently |
| Human approval queue | Irreversible disclosure risk | An LLM classifier alone is a probabilistic gate on publishing children's names |
| Retaining raw restricted content | Reclassification without resync | `historyId` retention is not guaranteed, so resync may be unavailable |
| New PDFBox dependency | Term dates, clubs and menus are PDF-only | `openpdf` writes PDFs; it does not extract text |
