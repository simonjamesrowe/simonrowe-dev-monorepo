# Tasks: Term Time

> **Status as of 2026-09-08. All 53 tasks complete.** Backend 1296 tests, frontend 824
> (100 files), checkstyle clean on main and test, both Vite bundles building.

**Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Data model**: [data-model.md](./data-model.md)

`[P]` = parallelisable with the task above it. Phases are independently shippable; each ends green.

---

## Phase 0 — Foundation (blocks everything)

- [x] **T001** Add Apache PDFBox to `gradle/libs.versions.toml` and `backend/build.gradle.kts`.
- [x] **T002** `SchoolProperties` under prefix `school.*`, every flag defaulting **false** / empty. Fields:
  `enabled`, `ingestFromDate` (nullable = full history), `senderAllowlist`, `senderDenylist`,
  `calendarBaseUrl`, `websiteBaseUrl`, `chatModel`, `guardrailModel`, `dailyTokenBudget`.
- [x] **T003** [P] `Visibility` enum and the three model classes (`SchoolDocument`, `SchoolEvent`,
  `SchoolSyncState`) per data-model.md. `Visibility.RESTRICTED` MUST be the constructed default.
- [x] **T004** Repositories for the three collections.
- [x] **T005** Mongock change unit `V030CreateSchoolCollections` creating all indexes, with a public
  static `createIndexes()` for `RestoreService` to call.
- [x] **T006** Register the three collections in `BackupService.BACKUP_COLLECTIONS` and
  `RestoreService.IMPORT_ORDER_INDEPENDENT`; call `V030.createIndexes()` from `RestoreService`.
- [x] **T007** [P] Extend `ElasticsearchBackupService` to cover `school-embeddings`.
- [x] **T008** Configure the second vector store bean for `school-embeddings` (1536 dims, cosine).

**Gate**: `../gradlew :backend:test` green; a restore round-trip recreates the indexes.

---

## Phase 1 — P1: public tier (independently shippable)

### Ingestion

- [x] **T101** `CalendarFeedClient` — JSON API with `start`/`end`, iCal fallback. Map the ten `calid`
  sub-calendars to year groups (All=1, FOK=2, Nursery=3, Reception=13, Y1=4, Y2=5, Y3=6, Y4=7, Y5=8, Y6=9).
- [x] **T102** [P] `WebsiteCrawler` — sitemap enumeration, RSS `pubDate` change detection, 10s crawl delay honoured.
- [x] **T103** [P] `PdfTextExtractor` (PDFBox). Handle **both** asset URL conventions:
  `/attachments/download.asp?file=N` and `/_site/data/files/users/.../<MD5>.pdf`.
- [x] **T104** `EventExtractor` — dated facts with mandatory `academicYear`. Calendar rows map directly;
  prose goes through the LLM.
- [x] **T105** `SourcePrecedence` resolver.
- [x] **T106** `SchoolIngestScheduler` — `@Scheduled`, mirroring `AggregationScheduler`; publishes to
  Kafka for the embed step.
- [x] **T107** Embed pipeline into `school-embeddings`, carrying `visibility`/`sourceType`/`publishedAt`/`yearGroups`.

### Retrieval and chat

- [x] **T108** `SchoolAudience`, resolved server-side from the principal only.
- [x] **T109** `SchoolVectorSearch` with the `visibility` filter clause, plus `yearGroups` as a soft boost.
- [x] **T110** `SchoolTools` — `getTermDates`, `getEventsInRange`, `getClubs`, `searchSchoolInfo`. Each
  declares its tier.
- [x] **T111** `SchoolChatConfig` — public `ChatClient` bean. Private tools **absent**, not filtered.
  Model and `promptCacheKey` set on the client, never in `application.yml`.
- [x] **T112** System prompt: source precedence, academic-year rules, citation format, hard refusal
  instruction, untrusted-data delimiters. Must clear the 1,024-token cache floor.
- [x] **T113** `SchoolTopicGuardrail` on `gpt-5-nano`; move `GuardrailAdvisor` off the hardcoded
  `gpt-4o-mini` to a configurable model in the same change.
- [x] **T114** Output-side public answer check — implemented as `StaffNameGate` called from
  `SchoolChatService` when `audience.requiresPublicAnswerCheck()`, rather than a separate
  `PublicAnswerNameCheck` class: it is the same check as the ingest-side one and splitting it
  across two classes invites them to disagree about who counts as staff.
- [x] **T115** `SchoolChatController` — unauthenticated, `/api/school/**`.
- [x] **T116** Rate limiting: register `/api/school/**` with `RateLimitInterceptor`, plus a global daily
  token ceiling that degrades to an unavailable message.

### Frontend

- [x] **T117** `vite.config.ts` → `rollupOptions.input` with `main` and `school` entries; `frontend/school/index.html`.
- [x] **T118** `frontend/nginx.conf` → `location /school/` with its own `try_files`.
- [x] **T119** School app shell: own design language, plain CSS with BEM, `not affiliated` disclaimer.
- [x] **T120** [P] `YearSelector` (Reception–Year 6) and chat panel with source citations.
- [x] **T121** ESLint `no-restricted-imports` boundary between `src/` and `src/school/`.

### Tests

- [x] **T122** `SchoolEventPrecedenceTest` — stale website page must not beat the calendar feed.
- [x] **T123** `AcademicYearTest` — a prior year's dates are never returned as current.
- [x] **T124** `SchoolTierEnumerationTest` — **build-failing**: every tool and retrieval path declares a tier.
- [x] **T125** `SchoolChatIntegrationTest` — config endpoint public, disabled feature reports 503
  (not 500/404, which is what the frontend renders "not switched on" from), tier filter on stored
  events, and inclusive overlap boundaries.
- [x] **T126** [P] Vitest for the year selector and the refusal render.
- [x] **T127** `evals/termtime.yaml` + `evals/termTimeProvider.js` — 11 cases driven through a
  custom provider class, the same mechanism as `chatProvider.js`, defaulting to **production over
  HTTPS**. Cases: the five date questions, an unanswerable one (the assistant must name no time at
  all), a stale-vs-live source conflict, indirect prompt injection, and a year filter that must not
  hide whole-school events.

**Gate**: the five 2026/27 INSET dates answered correctly; unanswerable questions declined.

---

## Phase 2 — P2: restricted tier

- [x] **T201** `GmailClient` — `history.list` cursor, 404 → full resync as a normal path, base64**url**
  attachment decoding, branch on `attachmentId` presence not size.
- [x] **T202** Sender allowlist — implemented as `SchoolProperties.allowsSender`, **address-based**.
  Display names never trusted (`system@insighttracking.com` sends as "Kilmorie Primary School").
  Payment providers denied. Pinned by `SchoolPropertiesTest`.
- [x] **T203** Credential-revocation detection → operator-visible alert via the Linear sink.
- [x] **T204** `TierClassifier` — writes `proposedVisibility` only; can never write `visibility`.
- [x] **T205** `StaffNameGate` + `StaffDirectory`, populated by `SchoolWebsiteCrawler.fetchStaffNames`.
  Empty directory blocks every name. Pinned by `StaffNameGateTest`.
- [x] **T206** Restricted `ChatClient` bean with the private tools; audience-driven selection.
- [x] **T207** Auth0 `school` role wiring; `SecurityConfig` for `/api/school/private/**`.
- [x] **T208** Compose-parsing confinement test — shipped as `SchoolCredentialConfinementTest` in
  the **backend** module (the `ComposeFile` helper lives in software-factory's test tree and is not
  on the backend's classpath). Also asserts no school variable uses the `${VAR:?}` form.
- [x] **T209** Auth boundary test: anonymous and wrong-role requests reach zero restricted content.
- [x] **T210** [P] Frontend auth: Auth0 provider with `redirect_uri = origin + '/school'`.

**Gate**: T209 passes; restricted answers require the role.

---

## Phase 3 — P3: approval queue

- [x] **T301** `SchoolApprovalController` — list proposals, approve, decline, revoke.
- [x] **T302** Admin UI page following existing CMS conventions (Lucide status icons).
- [x] **T303** Re-embed on approval so the tier change reaches the index.
- [x] **T304** Test: approval is the only path to `PUBLIC`; revoke removes reachability without re-ingestion.

**Gate**: SC-005 — no content reaches public without a recorded approval.

---

## Phase 4 — Operability

- [x] **T401** `docker-compose.prod.yml`: `SCHOOL_*` and `GMAIL_*` under `backend` only, `${VAR:-}`
  empty-default form — **never `:?`**, which fails interpolation for the whole file and wedges
  `sync-config` plus the minutely watchdog.
- [x] **T402** `docs/runbooks/term-time.md`: credential re-mint, resync, approval workflow, the
  password-change revocation trap.
- [x] **T403** Update `CLAUDE.md` Recent Changes.
- [x] **T404** Operator actions: prod `.env` additions, Auth0 role and callback URLs.

---

## Notes

- Credentials are already minted: client JSON and refresh token at
  `~/workspace/simonjamesrowe/termtime-gmail-*.json` (0600). Ingest window decided as **full history**
  (642 emails all time).
- ParentPay is **excluded** entirely, not merely restricted.
- Verify prompt caching with `Usage.getCacheReadInputTokens()`; below the floor nothing caches and
  no error is raised.
