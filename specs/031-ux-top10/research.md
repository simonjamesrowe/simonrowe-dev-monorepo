# Phase 0 Research: UX Top-10 Improvements

Current-state findings from reading the code, plus the decisions they force.
Every claim below is anchored to a file and line so implementation does not have
to re-derive it.

The source design doc (`docs/superpowers/specs/2026-07-30-ux-top10-design.md`)
was written from a browser review, not from the code. Where the code disagrees
with the design, this document records the correction and the design doc's
intent is preserved.

---

## A. Corrections to the design document

These matter because following the design doc literally would produce wrong code.

| # | Design doc says | Reality | Consequence |
|---|---|---|---|
| A1 | "`styles.css` `.button--primary` (lines ~248–257)" | Correct — `frontend/src/styles.css:248-253` + `:255-257` hover | No change needed; also note there is **no `:focus-visible`** style on `.button--primary`, worth adding while there |
| A2 | "the orphaned `.footer` CSS already in `styles.css` (~line 645)" | Correct — `styles.css:645-764`, a complete BEM contract (`.footer__inner`, `__brand`, `__brand-link`, `__brand-mark`, `__summary`, `__group`, `__heading`, `__bar`, `__copyright`, `__link`) plus a `max-width:768px` block at `:754-764` | The footer markup should be written **to fit the existing CSS**, not the reverse. `.app-layout` is already `flex-direction:column; min-height:100vh` with `.app-layout__main{flex:1}` (`styles.css:882-891`), so `.footer{margin-top:auto}` works with no layout change |
| A3 | "`ContactForm.tsx:132`" for the submit label | Correct file; verify the line at edit time | — |
| A4 | Revive or delete `StatsGrid.tsx`, `CTASection.tsx`, `ConnectStrip.tsx` | There are **five** orphans in `components/home/`, not three: also `AIChatModule.tsx` (169 lines, zero importers). And `ConnectStrip.tsx` **is** imported — by its test only (`tests/components/home/ConnectStrip.test.tsx`) | Decision D9 below |
| A5 | `Blog` document gains `contentType` | There are **two** `@Document(collection="blogs")` records: `blog/Blog.java:13` (read model) and `admin/Blog.java:16` (write model, **different component order**, extra `legacyId`). Both are Java `record`s | Every `new Blog(...)` call site must be updated. `WeeklyDigestAgent.java:108-113` constructs the **public** record |
| A6 | "the weekly digest generator" | It is `agents/WeeklyDigestAgent.java` — an Embabel `@Agent`, and it is **excluded from JaCoCo** (`backend/build.gradle.kts:46`) | Its change is a one-line constructor arg; coverage is not affected but a test is still worth writing at the metadata level |
| A7 | Mongock change unit backfills digests by tag name "case-insensitive" | `blog/TagRepository.java:8` `findByName` is **exact, case-sensitive**. The admin repo has `findByNameIgnoreCase` (`AdminTagRepository.java:8`) | The change unit must work at the raw `Document` level and compare trimmed/lower-cased tag names itself — see D3 |
| A8 | `skill_groups.image` is "backend media" | True, but `image` is a `common.Image` **record** (`url,name,width,height,mime,formats`), not a string — `skills/SkillGroup.java:13`. Jobs use `companyImage` of the same type (`employment/Job.java:14`) | The change unit writes a nested sub-document, not a scalar |
| A9 | "News uses a Load more button on top of the existing backend pagination" | Correct: `GET /api/news` already takes `page`/`size`/`source` (`NewsController.java:27-31`) and returns a Spring `Page` serialized directly (has `last`, `totalPages`, `number`) | Frontend-only paging change plus the new `/sources` endpoint |
| A10 | Blog listing default tab Engineering, "client-side filter on the fetched list" | `GET /api/blogs` returns **all** published posts as a plain `List` (`BlogController.java:24-25`) — there is no server paging to fight with | Client-side filtering is correct and cheap at ~43 posts |
| A11 | "`/api/blogs/latest` gains optional `contentType` param" | Signature is `getLatestBlogs(@RequestParam(defaultValue="3") @Min(1) @Max(10) int limit)` — param is `limit`, returns a plain `List`, and `BlogService.getLatest` limits **in memory** (`BlogService.java:42-50`) | Add `contentType` as an optional param; keep in-memory limiting (43 posts) |

---

## B. Decisions

### D1 — `usePageTitle` hook shape

**Decision**: `usePageTitle(pageTitle?: string)` in `frontend/src/hooks/usePageTitle.ts`.
`undefined` → `Simon Rowe | Software Engineering Leader`; a string → `${pageTitle} · Simon Rowe`.
`frontend/index.html:6` default becomes the same site string.

**Rationale**: replaces seven imperative `useEffect(() => { document.title = ... })`
blocks (`HomePage.tsx:18`, `ProfilePage.tsx:22`, `ExperiencePage.tsx:22`,
`BlogListingPage.tsx:18`, `BlogDetailPage.tsx:32`, `NewsEventsPage.tsx:47`,
`McpPage.tsx:24`), three of which currently omit the site name entirely
(`'Blog'`, `'Experience & Skills'`, `'News & Events'`). One hook makes the
convention enforceable and gives the 404 page a title, which no unknown route
currently sets (the previous page's title persists).

**Alternatives rejected**: a `<Helmet>`-style library (new dependency, forbidden
by Principle II's no-new-deps posture for a cosmetic fix); setting titles in the
route table (loses per-page dynamic titles like the blog post name).

**Note**: the existing dynamic titles interpolate fetched data
(`${profile.name}`, `${data.title}`) — the hook must accept `undefined` on first
render and re-run when the value arrives, so it takes the value as a dependency
rather than running once on mount.

---

### D2 — `fetchWithRetry` contract

**Decision**: `frontend/src/services/fetchWithRetry.ts` exporting

```ts
fetchWithRetry<T>(url: string, options?: RequestInit & { fallbackMessage?: string }): Promise<T>
```

- One retry, after ~300 ms, on a thrown network error or a `5xx` response.
- No retry on `4xx`.
- On final failure, throws `Error` whose `message` is the server's parsed
  `ErrorResponse.message` if present, else the caller's `fallbackMessage`, else a
  generic string. The raw `TypeError: Failed to fetch` text never becomes the
  message.
- Honours an `AbortSignal` passed in `options`, and does not retry after abort.

**Rationale**: the codebase has three copies of a byte-identical
`handleResponse<T>` differing only in a fallback string (`blogApi.ts:7-21`,
`newsApi.ts:6-20`, `eventsApi.ts:6-20`), two copies of `parseErrorMessage`
(`jobsApi.ts:4-15`, `skillsApi.ts:4-15`), an inlined third in
`profileApi.ts:6-25`, and bare `throw new Error('...')` with no body parse in
`searchApi.ts:37,51` and `codeExampleApi.ts:14-18`. Consolidating is a net
deletion, which is what Principle V asks for.

**Scope boundary**: `contactApi.ts` keeps its custom `ContactApiError` (it
carries field-level `errors` used by the form) and is **not** migrated — a POST
with side effects must not be silently retried either. `adminApi.ts`,
`dataOperationsApi.ts` and `favouritesApi.ts` use bearer-token `authFetch`
wrappers and are out of scope for this feature; migrating them is a separate
refactor. `chatService.ts` is STOMP, not fetch.

**Alternatives rejected**: React Query / SWR (new dependency, and the app's
data-fetching is trivially simple); retry inside each service (keeps the
duplication).

---

### D3 — Blog `contentType` migration strategy

**Decision**: `V015BackfillBlogContentType`, order `015`, operates on raw
`org.bson.Document` via `MongoTemplate`:

1. Build a set of digest tag `_id`s: scan the `tags` collection for any document
   whose `name`, trimmed and lower-cased, equals `weekly digest`.
2. For each `blogs` document **missing** `contentType`, set it to `DIGEST` if its
   `tags` array contains any digest tag id (`@DBRef` stores these as `DBRef`
   sub-documents with `$id`), else `ENGINEERING`.
3. Documents that already have `contentType` are left alone — this is what makes
   a re-run a no-op.

**Rationale**: `TagRepository.findByName` is exact and case-sensitive (A7), so
the repository cannot express the requirement. Working at the `Document` level
also avoids the two-`Blog`-record ambiguity and avoids rewriting unrelated fields
through a record round-trip. `V014MakeFavouritesGlobal` establishes exactly this
raw-`Document` house pattern.

**Next free order is `015`**: `V010BackfillArticlePublishedDates` and
`V010PruneBackupsToRetentionLimit` both claim order `010`; the highest is `014`.

**No index.** Spring Data auto-index-creation is disabled project-wide, so an
index would need its own change-unit step — unjustified for ~43 documents read
as a whole list anyway.

**Enum storage**: store the enum **name** (`"ENGINEERING"` / `"DIGEST"`), which
is how Spring Data serializes a Java enum by default, so the change unit's
strings and the record's enum agree.

---

### D4 — Where `contentType` defaults are applied

Three write paths, three defaults:

| Path | File | Behaviour |
|---|---|---|
| Admin create/update | `admin/AdminBlogController.java` (raw `Map` body, `toDto` at `:160-177`) | Parse `contentType` from the body; absent/blank/unrecognised → `ENGINEERING`. Add to `toDto` so the editor round-trips. Validation (`:199-240`) rejects an unrecognised explicit value |
| Weekly digest | `agents/WeeklyDigestAgent.java:108-113` | Pass `BlogContentType.DIGEST` in the constructor |
| Legacy documents | `V015` | Tag-derived (D3) |

**Reading a document with no `contentType`** (e.g. written by an older container
after the code deploys but before the migration runs — impossible in practice
since Mongock runs at boot, but cheap to defend): the response DTOs coerce
`null` → `ENGINEERING`. That keeps the frontend's `contentType` field
non-optional, which in turn keeps the tab filter simple.

---

### D5 — Blog tabs and featured-post selection

**Decision**: a small `BlogContentTabs` component using `role="tablist"` /
`role="tab"` with `aria-selected`, styled with the existing `.chip` /
`.chip--active` classes (`styles.css:312`). Default active tab: `Engineering`.
`BlogListingPage` filters the already-fetched list; the featured card becomes
`the first post whose contentType is ENGINEERING` rather than
`blogs[0]` (`BlogListingPage.tsx:28`).

**Rationale**: `CategoryFilters.tsx` looks like a candidate for reuse but is a
chip row **plus a search input** for tag filtering, with no importers at all; the
blog listing has no search box today and adding one is out of scope. A purpose-
built 3-tab control with correct tab semantics is smaller than adapting it.

**Featured-card edge case**: when the active tab yields no posts, render nothing
rather than an empty featured frame — `BlogListingPage` currently has **no empty
state at all** (renders an empty `div` when `blogs.length === 0`), so an empty
state is added as part of this work.

---

### D6 — News paging and source chips

**Decision**:

- New `GET /api/news/sources` → `List<String>`, the distinct `sourceName` of
  **visible** articles, sorted alphabetically. Implemented with
  `MongoTemplate.findDistinct` rather than a new derived repository method,
  because Spring Data has no `findDistinctSourceNameBy...` projection for a
  scalar.
- `NewsEventsPage` state changes from `articles: Article[]` (one `size=100`
  fetch, `NewsEventsPage.tsx:50-63`) to `{ articles, page, last, loadingMore }`.
  Initial fetch `fetchNews(0, 24, activeSource)`. "Load more" fetches
  `page + 1` and appends. Button hidden when `last`.
- Selecting a source chip resets to page 0 and re-queries with `source=`.
- `sources` comes from the new endpoint instead of
  `[...new Set(articles.map(a => a.sourceName))]` (`NewsEventsPage.tsx:92`).

**In-flight request safety** (spec edge case): a monotonically increasing request
id in a ref; a response whose id is not the latest is discarded. This is needed
because switching source while a "Load more" is in flight would otherwise append
the wrong source's articles.

**The `events` pseudo-source stays client-side.** `sourceFilter` currently has
three kinds of value: `'all'`, `'events'`, and a real source name
(`NewsEventsPage.tsx:100-106`). Only the third becomes a backend query
parameter; `'all'` and `'events'` keep their existing local meaning, and the
favourites view keeps filtering in memory (`:65-81`) — FR-040 requires
favourites behave exactly as today.

**Also fixed in passing**: the `allEvents.length === 0` empty state at
`NewsEventsPage.tsx:307-311` is currently unreachable (nested inside
`showEvents && allEvents.length > 0`). Left alone unless it falls out of the
refactor — noted so a reviewer does not think it was introduced here.

---

### D7 — Mobile hero

**Decision**: in `HeroSection.tsx`, render the badge (`:47`) and tagline (`:50`)
unconditionally, and render `SUGGESTED_PROMPTS.slice(0, isMobile ? 2 : 4)` in the
chips block (`:76-89`), dropping the `!isMobile &&` guards. Reduce the mobile
`.hero` vertical rhythm in the existing `@media (max-width: 768px)` block.

**Rationale**: the tagline's 1-line clamp already exists at `styles.css:1177-1183`
and is currently **dead CSS** because JS removes the element; rendering the
element activates work that is already written. Three of the four mobile
`.hero__tagline` rules (`:1165`, `:1177`, `:1832`) are similarly dead — this is a
consolidation opportunity, not new styling.

**Also**: the `<textarea rows={6}>` at `HeroSection.tsx:58-74` is the main
consumer of vertical space on mobile; reducing rows at mobile widths is the
cheapest way to satisfy FR-025 (chat input inside the first screen of 390×844).

**Test impact**: `src/components/home/HeroSection.test.tsx:51-71` currently
asserts that mobile **hides** badge, tagline and chips. That test inverts.

---

### D8 — Skill level words

**Decision**: extend `SkillRatingBar.tsx` (25 lines) with a pure
`skillLevel(rating: number): 'Expert' | 'Advanced' | 'Proficient' | 'Familiar'`
mapping 9–10 / 7–8 / 5–6 / <5, rendered as a `<span class="skill-rating-bar__level">`
beside the bar. The `aria-label` becomes
`${skillName} proficiency: ${level} (${rating} out of 10)`.

**Rationale**: FR-028 requires the announced value to match the visible word. The
current label says only "N out of 10" (`SkillRatingBar.tsx:10`).

**Two markup notes**: the component's root currently carries
`role="progressbar"` and the `aria-*` attributes; adding a sibling text node
inside a `progressbar` role is invalid, so the bar track keeps
`role="progressbar"` and the level word becomes a sibling **outside** it, with the
whole thing wrapped in a plain `div`. Also, `:21` sets
`backgroundColor: 'var(--primary)'` inline even though
`.skill-rating-bar__fill` exists in CSS (`styles.css:2514-2518`) — move it to
CSS. The track colour is a hardcoded `#e5e7eb` (`styles.css:2507`) that is not
theme-aware; tokenise it while there.

---

### D9 — Orphaned home components: revive vs delete

| Component | Lines | Decision | Why |
|---|---|---|---|
| `CTASection.tsx` | 23 | **Revive** as the §3.4 contact CTA band | It already carries `className="cta-section tour-contact"`, and the site tour has a step targeting `.tour-contact` that currently has **no target on the home page**. Reviving it fixes that latent tour bug. Copy changes: `Get In Touch` → `Get in touch`; add the `Download CV` link (FR-008) |
| `ConnectStrip.tsx` | 57 | **Absorb into `Footer`** | It already renders exactly the footer's right-hand column: a `Download CV` link to `${API_BASE_URL}/api/resume` and a deduped social list. Its CSS (`styles.css:8745-8799`) is `display:none` except under 768px, i.e. it was built as a mobile-only strip. The footer needs the same content at all widths, so the markup moves into `Footer` and both `ConnectStrip.tsx` and its mobile-only CSS block are deleted |
| `StatsGrid.tsx` | 95 | **Delete** (+ CSS `styles.css:1366-1510`) | "Years Experience / Roles Held / Skill Domains" counters plus two hardcoded Lucide icons chosen by list position. It also uses inline `style={{}}` for colour, against the BEM/custom-property rule. The Currently strip covers the same ground with real prose |
| `AIChatModule.tsx` | 169 | **Delete** (+ CSS `styles.css:1186-1365`) | Zero importers, zero tests. The hero already owns the chat entry point |
| `AboutSection.tsx` | — | **Leave** | Not an orphan — used by `ProfilePage.tsx:4,41` |

Deleting `ConnectStrip.tsx` removes the only importer of
`tests/components/home/ConnectStrip.test.tsx`, so that test is deleted too and its
coverage moves to a new `Footer` test.

**Out of scope but noted**: the same dead-code problem exists outside
`components/home/` — `pages/NewsPage.tsx` and `pages/EventsPage.tsx` are unrouted,
`blog/BlogGrid.tsx`, `blog/HomepageBlogPreview.tsx`, `blog/BlogSearch.tsx` are
test-only, `blog/CategoryFilters.tsx` has zero importers, and
`components/layout/ScrollToTop.tsx` is shadowed by a local `ScrollToTop` in
`App.tsx:55-61`. FR-009 scopes this feature to home components. A follow-up
cleanup is recorded in tasks.md as explicitly optional so the PR stays reviewable.

---

### D10 — Footer placement and the tests it breaks

**Decision**: `frontend/src/components/layout/Footer.tsx`, rendered inside the
inline `PublicLayout` in `App.tsx:111-132`, after `<main>`. Root element is
`<footer className="footer">` (which gives it `role="contentinfo"`).

**Blocking detail**: `tests/App.test.tsx:63` and `:72` currently **assert that
`contentinfo` is absent**. Both assertions must be inverted in the same commit or
the suite goes red. This is the single most likely thing to be missed.

**Data**: the footer needs profile data (name, headline, email, social links) and
is rendered in the layout, above any page. `useProfile()` is already a hook with
its own fetch, and `HomePage`/`ProfilePage` each call it independently — the
footer calling it too means one more request per page load. Accepted: it is a
`GET /api/profile` on an already-cached endpoint, and the alternative (lifting
profile into a context) is a larger refactor than this feature warrants. The
footer renders its brand/nav columns immediately and fills the profile-dependent
column when data arrives; it never shows a loading or error state (FR-010's data
is decorative, and a failed profile fetch must not put an error frame on every
page).

---

### D11 — Home-page section data sources

All four sections derive from existing endpoints. No new backend work.

| Section | Data | Source |
|---|---|---|
| Currently strip | current role, company, team size, focus | `fetchJobs()` → the job with no `endDate` (jobs come back `findAllByOrderByStartDateDesc`), plus `profile.headline` and the job's `shortDescription` |
| Employer logo strip | company name + logo | `fetchJobs()` → filter `isEducation !== true`, dedupe by `company`, take `companyImage` |
| Featured writing | 3 latest engineering posts | `fetchLatestBlogs(3, 'ENGINEERING')` |
| Contact CTA band | — | static copy + `/profile#contact` + `${API_BASE_URL}/api/resume` |

**FR-005 compliance**: the strip renders whatever prose the jobs/profile data
carries. The "30+ engineers / three product pillars" phrasing in the design doc
is an example of what today's data says, not a string to hardcode.

**Anchors verified**: `#contact` exists on both
`components/contact/ContactSection.tsx:5` and
`components/profile/ContactDetails.tsx:27`, so `/profile#contact` resolves
(`useScrollToHash` handles the scroll).

**CV asset verified**: `backend/.../resume/ResumeController.java:11`
`@RequestMapping("/api/resume")` generates the CV; `ConnectStrip.tsx:30-37`
already links `${API_BASE_URL}/api/resume`. Reuse that, not `profile.cvUrl`.

**Degradation (FR-005 / SC-001 edge case)**: each section takes its data as a
prop and returns `null` when the data is missing, so a failed `fetchJobs()`
silently drops two sections rather than erroring the page. The home page must
**not** gate the whole page on the jobs fetch — today it gates on profile only
(`HomePage.tsx:23,27`), and that stays.

---

### D12 — Asset install change unit (`V016`)

**Decision**: bundle SVGs at `backend/src/main/resources/media/icons/`, read them
as `ClassPathResource`, and write each one through the same on-disk shape the rest
of the system expects:

- disk: `{uploads.path}/{assetId}/original.svg`
- `media_assets` row: `originalPath = "/uploads/{assetId}/original.svg"`,
  `mimeType = "image/svg+xml"`, `variants = {}` (empty)
- then set `skill_groups.image` / `jobs.companyImage` to a `common.Image`
  sub-document whose `url` is that `originalPath`

**Idempotency key**: a deterministic `legacyId` per bundled asset (e.g.
`icon:java`, `logo:global`). `MediaAsset.legacyId` is already
`@Indexed(unique=true, sparse=true)` (`media/MediaAsset.java:19`) and
`MediaSyncService` already uses `legacyId` for exactly this "was this file
already registered?" purpose. The change unit looks up by `legacyId` first and
reuses the existing asset id, so a re-run neither duplicates rows nor rewrites
files.

**Why not `MediaService.uploadMedia`**: it takes a `MultipartFile`. **Why not
`ExternalImageDownloader.finalizeAsset`**: it is private and download-oriented.
A small private helper inside the change unit is the honest option; it is ~20
lines and `migration/**` is JaCoCo-excluded, so it does not distort coverage.

**SVG + variants**: `ImageVariantGenerator` returns `Map.of()` for SVG
(`media/ImageVariantGenerator.java:36-38`), so no variants exist — and the
resolvers already fall back to the original when a preferred variant is missing
(`MediaVariantResolver`, `MediaImageHydrator`). The frontend's
`group.image?.formats?.thumbnail?.url ?? group.image?.url`
(`SkillGroupCard.tsx:10`) therefore lands on `.url`. **Verified as safe.**

**Relative URLs are fine**: `SkillGroupCard.tsx:18-30` and the blog cards do not
prefix `API_BASE_URL` on image `src`. That is correct here, not a bug — nginx
proxies `/uploads/` to the backend in production and `vite.config.ts:9-35` proxies
it in dev.

**GraalVM**: bundled resources under `src/main/resources` must be reachable in the
native image. Spring Boot's AOT processing registers `src/main/resources`
patterns, but this must be **verified on the native build**, not assumed — it is
called out as a task.

**Backup**: files land in the uploads volume that `scripts/backup.sh` already
captures, so no backup change is needed.

---

### D13 — Admin nav gating

**Decision**: `TopNav.tsx:39-41` (the `UserCircle` `NavLink`) and the
`{ label: 'Admin', to: '/admin' }` entry in `MobileMenu.tsx:14` render only when
`useAdminRole()` is true. `useAdminRole` (`src/auth/useAdminRole.ts`) reads the
`https://simonrowe.dev/roles` claim from `useAuth0().user` and needs no new
plumbing — `AuthProvider` already wraps every public page (`App.tsx:138-139`).

Sign-in is unchanged: `AdminLayout.tsx:46-58` still redirects an unauthenticated
visitor to Auth0 on `/admin`. Also add an `aria-label` to that link, which it
currently lacks.

---

### D14 — Social link naming (`V017`)

**Decision**: `SocialLinks.tsx:34,41` currently prefers the type-derived label
(`platformLabels[link.type] ?? link.name`); invert to
`link.name ?? platformLabels[link.type]`. `V017NameGithubSocialLinks` then sets
`name` on the two `social_medias` documents of type `github`, distinguishing them
by their `link` URL.

**Note on the entity/DTO field name mismatch**: the document field is `link`
(`profile/SocialMediaLink.java:11`) but the API exposes it as `url`
(`SocialMediaLinkResponse.java:3,11`). The change unit matches on `link`; the
frontend reads `url`.

**Idempotency**: match on `type == "github"` **and** the specific `link` value,
and only write when `name` differs from the target. Both target URLs must be read
from the live data at implementation time rather than guessed — recorded as a
task precondition.

---

### D15 — Testing approach per layer

**Frontend (Vitest)**: follow the established pattern — module-level
`vi.mock('../../src/services/xApi')`, `vi.mocked(fn)`, render inside
`<MemoryRouter>`, assert via `screen`/`waitFor` (canonical example:
`tests/pages/BlogListingPage.test.tsx`). Media queries are stubbed with the
`setMatchMedia` helper pattern from `src/components/home/HeroSection.test.tsx:12-26`.

**Two existing tests must be inverted, not just extended** — this is the main
regression risk:

- `tests/App.test.tsx:63,72` — asserts no `contentinfo`; the footer makes it present.
- `src/components/home/HeroSection.test.tsx:51-71` — asserts mobile hides
  badge/tagline/chips; they must now be present, with exactly 2 chips.

**Backend**: controller tests extend `AbstractIntegrationTest` (shared context,
`SharedMongoContainer`, `mongock.enabled=false`) and drive real `MockMvc`.
Change-unit tests use the **real-Mongo direct-drive** style of
`V014MakeFavouritesGlobalTest` — `extends AbstractIntegrationTest`,
`@Autowired MongoTemplate`, `new V015...()`, call `execution(mongoTemplate)`,
clean up in `@AfterEach`. The `@TestPropertySource(properties="mongock.enabled=true")`
isolated-boot style (`V011SeedAndBackfillDanVegaBlogIntegrationTest`) is only
needed to prove boot-time wiring; V015–V017 inject only `MongoTemplate`, so
direct-drive is sufficient and much cheaper (no extra Spring context).

**Critical**: any change-unit test that writes to the shared Mongo **must** clean
up in `@AfterEach`, or it pollutes every other integration test in the suite.

**Coverage gate**: JaCoCo minimum is `0.78` overall
(`backend/build.gradle.kts:62-71`), and `migration/**` plus `WeeklyDigestAgent*`
are excluded (`:38,46`). New code in `blog/` and `aggregation/` **is** counted, so
the new controller/service paths need tests to avoid dropping the ratio.

**Checkstyle**: `maxWarnings = 0` with Google style
(`config/checkstyle/google_checks.xml`) — `final` parameters, javadoc on public
types, 100-col limit. Run `../gradlew :backend:checkstyleMain` before claiming done.

**Playwright**: add `frontend/e2e/routing.local.spec.ts` to the `local` project
(`testMatch: /\.local\.spec\.ts$/`). There is **no `webServer` block** in
`playwright.config.ts`, so the local stack must already be running — e2e is a
manual verification step, not a CI gate.

---

## C. Open item requiring human input

**The icon/logo set (FR-032).** The design document makes approval of the
proposed marks a gate before `V016` is finalised. Sourcing research is complete
(see `contracts/asset-manifest.md`), but the implementer must present a rendered
preview grid — in both light and dark themes — and get explicit approval before
the change unit is written. **This is the one point in the plan where
implementation stops and waits.**

Everything else in the plan is unblocked.

---

## D. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Adding a component to the two `Blog` records breaks unrelated call sites | High | Compile-driven: `../gradlew :backend:compileJava` enumerates every site. `record` gives no silent default |
| `tests/App.test.tsx` / `HeroSection.test.tsx` inverted assertions missed | High | Called out in D10, D15 and as explicit tasks |
| Change-unit test pollutes the shared Testcontainer | Medium | `@AfterEach` cleanup, per the `V014` house pattern; memory note `mongock-runs-in-integration-tests` records this having bitten before |
| Bundled SVG resources unreachable in the GraalVM native image | Medium | Explicit native-build verification task |
| JaCoCo 0.78 gate drops from new untested backend code | Medium | Tests written alongside, not after |
| Employer logo licensing / dark-theme legibility | Medium | Human approval gate (FR-032); monochrome treatment is the fallback |
| `styles.css` is 9,141 lines with duplicated sections (`.blog-listing-page` at both `:3184` and `:6864`, `.hero__tagline` declared 4×) — edits land in the wrong block | Medium | Locate by surrounding selector, not by remembered line number; deleting the dead blocks listed in D9 reduces the hazard |
| Retry logic doubles load on a genuinely failing backend | Low | Single retry only, 4xx excluded, POSTs excluded |

---

## E. Implementation findings (recorded after the fact)

Things discovered during implementation that the planning above got wrong or did
not anticipate. Kept because they are the parts a reviewer most needs to know.

### E1 — V015 had a silent data-corrupting bug, caught only by its test

The first draft of `V015BackfillBlogContentType` matched digest tags with
`tag instanceof Document ref && digestTagIds.contains(ref.get("$id"))`.

**`@DBRef` array elements decode to `com.mongodb.DBRef`, not `org.bson.Document`.**
The `instanceof Document` guard was therefore always false, so the change unit
would have stamped **all 43 posts `ENGINEERING`** — including all 15 weekly
digests — with no error and no warning. The entire blog split would have silently
done nothing.

Found by probing the real shape: write a `Blog` through `BlogRepository` (the real
`@DBRef` write path), then read it back through
`mongoTemplate.getCollection("blogs")` (the change unit's read path). Fixed with a
`referencedId(Object)` helper that reads `DBRef.getId()` and still tolerates the
raw `{$ref,$id}` document shape.

Two lessons worth carrying forward: the raw-`Document` approach in D3 is still
right, but **the driver's decoded types are not the on-disk types**, and a
migration whose failure mode is "does nothing quietly" needs a test that asserts
the positive case against real Mongo, not just idempotency.

Also learned in the same probe: **tag `_id`s are `String`, not `ObjectId`.**

### E2 — dead CSS was larger than expected

Deleting `StatsGrid`, `AIChatModule` and `ConnectStrip` freed **411 lines** of
`styles.css` (`.chat-module*` 180, `.stats-grid*` 145, `.connect-strip*` 81, plus
`.skill-group-grid__error`). Net effect across the feature: `styles.css` went from
9,141 to ~9,005 lines *despite* adding six new component blocks.

### E3 — `RoleTimeline` was a second instance of the SkillGroupGrid defect

`RoleTimeline.tsx` rendered a bare `<p className="role-timeline__error">` with no
title and no retry, exactly like `SkillGroupGrid`. D6/D11 missed it because the
audit in the frontend survey only covered components that used `ErrorMessage`.
Fixed alongside; `/experience` would otherwise have been half-done.

### E4 — the two GitHub links already had names

Live data shows both GitHub entries already carry distinct `name` values
("Personal Github Account", "Public org for all repos that make up
www.simonjamesrowe.com"). The duplicate "GitHub" label came *entirely* from
`SocialLinks.tsx` preferring the type label. So **the frontend one-liner fixes the
user-visible defect on its own**, and `V017` only shortens the names to something
that fits a label. D14 framed V017 as corrective; it is cosmetic.

### E5 — skill ratings are decimals

Live ratings include `9.5, 8.6, 8.3, 7.6, 7.3, 7.2, 6.9`. The design doc's
"9–10 / 7–8 / 5–6" integer bands would have left `8.6` and `6.9` unclassified.
Bands are implemented as continuous lower bounds (`>=9`, `>=7`, `>=5`, else).
