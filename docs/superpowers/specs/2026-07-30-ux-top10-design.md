# simonrowe.dev UX Top-10 — Design

Date: 2026-07-30
Branch: `simonrowe/ux-review-simonrowe-dev` (based on main @ `e142bff`)
Delivery: **one PR** containing frontend code, backend code, and Mongock change
units for all data changes. No manual admin-CMS edits required.

## Background

A UX review of the live site (desktop 1440px + mobile 390px, light/dark)
identified ten priority fixes. One original item — JS bundle
compression/splitting — was found to be already shipped (gzip live on prod,
main bundle 205 KB wire, routes lazy-loaded via `React.lazy` in
`frontend/src/App.tsx`), so it is replaced by smaller trust fixes (page
titles, copy consistency, social-link labels) folded into the items below.

Decisions made during brainstorming:

- Blog digest split uses an **explicit `contentType` field** (not tag
  sniffing), backfilled by Mongock.
- Admin nav link is **auth-gated** via the existing `useAdminRole()`;
  login remains "visit `/admin`" (AdminLayout already auto-redirects
  unauthenticated visitors to Auth0).
- Skill bars keep the bar and **add a level word** mapped from the 0–10
  rating.
- News uses a **"Load more" button** on top of the existing backend
  pagination.
- Icon/logo assets are sourced by the implementer (Devicon + matching
  generic marks; official employer brand assets), with a **visual preview
  for approval before wiring**.

## 1. Routing & 404

- Add redirects in `frontend/src/App.tsx`: `/blog` → `/blogs` and
  `/blog/:id` → `/blogs/:id` (`<Navigate replace>`).
- Add a catch-all `path="*"` route rendering a lazy-loaded
  `NotFoundPage` inside `PublicLayout`: "Page not found" heading, short
  friendly line, links to `/` and `/blogs`. Sets a 404-appropriate page
  title.

## 2. Identity & page titles

- `frontend/index.html:6` default title → `Simon Rowe | Software
  Engineering Leader`.
- New `usePageTitle(title?: string)` hook: subpages call
  `usePageTitle('Blog')` → `Blog · Simon Rowe`; home keeps
  `Simon Rowe | Software Engineering Leader`. Replaces the imperative
  `document.title` `useEffect`s in HomePage, ProfilePage, ExperiencePage,
  BlogListingPage, BlogDetailPage, NewsEventsPage, McpPage.

## 3. Home page below the hero

`HomePage.tsx` currently renders only `HeroSection`. Add, in order:

1. **Currently strip** — 2–3 lines summarising the current role (Head of
   Engineering, Commercial Trading at Global; 30+ engineers; three product
   pillars). Sourced from existing profile/jobs API data — no hardcoded
   facts.
2. **Employer logo strip** — one quiet row of employer logos (Global,
   Y-Tree, Pivotal, Universal Music, Macquarie, SAS, Civica…), derived
   from jobs data (deduped by company), linking to `/experience`.
   Normalised height; works in light and dark themes.
3. **Featured writing** — the 3 latest `ENGINEERING` posts (see §5),
   reusing the blog card style, plus a "Read the blog" link.
4. **Contact CTA band** — "Get in touch" + "Download CV" linking to
   `/profile#contact` and the CV asset.

Unused components `components/home/StatsGrid.tsx`, `CTASection.tsx`,
`ConnectStrip.tsx` are revived where they fit or deleted — no dead code
left behind. The hero remains the full-viewport opening moment; the page
becomes scrollable with the footer (§9) at the bottom.

## 4. API error handling

- New shared `fetchWithRetry` helper in `frontend/src/services/`: one
  automatic retry with short backoff on network errors and 5xx; services
  (`blogApi`, `newsApi`, `skillsApi`, `eventsApi`, profile fetch…) route
  through it, replacing the duplicated per-service `handleResponse`
  helpers.
- `components/common/ErrorMessage.tsx` gains a `title` prop (default
  "Something went wrong"), removing the hardcoded "Unable to load
  homepage" shown on every page.
- Every public page passes `onRetry` (BlogListingPage, NewsEventsPage,
  SkillGroupGrid currently don't). No raw `err.message` like
  "Failed to fetch" rendered without a designed frame + retry action.

## 5. Blog content type (Engineering vs Digest)

Backend:

- `Blog` document + DTOs gain `contentType` enum: `ENGINEERING` |
  `DIGEST`. Default `ENGINEERING` for new/authored posts; the weekly
  digest generator sets `DIGEST` at creation.
- Mongock change unit backfills existing posts: has tag named
  "Weekly Digest" (case-insensitive) → `DIGEST` (15 posts today), else
  `ENGINEERING` (28). Idempotent.
- `GET /api/blogs` responses include `contentType`. `GET
  /api/blogs/latest` gains optional `contentType` param (used by the home
  Featured writing section).
- Admin blog editor exposes the field (simple select, default
  `ENGINEERING`).

Frontend (`BlogListingPage.tsx`):

- Tabs: **All · Engineering · Weekly Digest**; default **Engineering**.
  Client-side filter on the fetched list using `contentType`.
- Featured hero card = latest `ENGINEERING` post (replaces positional
  `blogs[0]`).
- `ArticleCard`/`FeaturedArticle` CTA copy → "Read post".

## 6. Mobile hero content

`HeroSection.tsx` hides badge, tagline, and all prompt chips behind
`!isMobile` (JS `useMediaQuery('(max-width: 768px)')`, not CSS). Change:

- Render the eyebrow badge and tagline on mobile (tagline keeps its
  existing 1-line clamp from `styles.css`).
- Render the first two `SUGGESTED_PROMPTS` chips on mobile.
- Tighten vertical spacing so the hero doesn't strand the chat input in
  dead space on a 390×844 viewport.

## 7. Skills: meaningful ratings + consistent icons

- `SkillRatingBar.tsx`: keep the bar; add a level word beside it mapped
  from the 0–10 rating: 9–10 **Expert**, 7–8 **Advanced**, 5–6
  **Proficient**, <5 **Familiar**. Aria attributes updated to match.
- Icons are backend media (`skill_groups.image`), so this is a data fix
  shipped in-PR:
  - Source a consistent set: Devicon SVGs for brand marks (Java/Kotlin,
    Spring, Kubernetes, Jenkins, React, Kafka, MongoDB, Elasticsearch…)
    and visually matching generic SVGs for Artificial Intelligence,
    Testing, Identity & Security.
  - Bundle SVGs as backend classpath resources; a Mongock change unit
    copies them into the uploads volume, creates `MediaAsset` documents,
    and repoints `skill_groups.image`. Idempotent (skips if the asset
    already exists).
  - Same mechanism upgrades employer/company logos on jobs (fixes the
    cropped low-res Global logo on the Experience timeline and feeds the
    home logo strip).
- **Gate:** implementer presents a preview grid of all proposed
  icons/logos for approval before the change unit is finalised.

## 8. Buttons, admin link, copy consistency

- `styles.css` `.button--primary` (lines ~248–257): replace the
  fade-to-white `linear-gradient(135deg, var(--primary),
  var(--primary-container))` with a solid `var(--primary)` fill and
  accessible on-primary text; keep the hover lift. Affects "Get In
  Touch", "Download CV", and the contact submit.
- Contact form submit label `Initiate Connection →`
  (`ContactForm.tsx:132`) → **"Send message"**; success/failure copy uses
  the same verb. "Get in touch" (sentence case) is the single name for
  the contact action wherever it appears.
- Admin link: `TopNav.tsx` UserCircle icon link and `MobileMenu.tsx`
  "Admin" item render only when `useAdminRole()` is true. Login flow
  unchanged (visit `/admin` → Auth0 redirect via `AdminLayout`).
- `SocialLinks.tsx`: label prefers `link.name` when present (falls back
  to type label). Mongock change unit sets the two GitHub entries'
  names to "GitHub — personal" and "GitHub — this site".

## 9. Site-wide footer

New `Footer` component rendered in `PublicLayout` (all public pages):

- Left: name + one-line positioning statement.
- Middle: nav links (Home, Profile, Experience, Blog, News & Events,
  MCP).
- Right: social links + email from profile data, Download CV link.
- Refresh and use the orphaned `.footer` CSS already in `styles.css`
  (~line 645) rather than adding a parallel block.

## 10. News & Events pagination

Backend:

- New `GET /api/news/sources` returning distinct source names (for filter
  chips). Existing `GET /api/news?page&size&source` is unchanged.

Frontend (`NewsEventsPage.tsx`):

- Initial load: first page of 24 articles (replaces the single
  `size=100` fetch). **"Load more"** button appends the next page;
  hidden when `Page.last`.
- Source chips come from `/api/news/sources`; selecting a chip re-queries
  the backend with `source=` (resets paging) instead of filtering in
  memory.
- Events fetches and the favourites toggle behave as today; the
  favourites view is unaffected by news paging.

## Testing

- **Vitest**: NotFoundPage + redirects, usePageTitle, home sections
  (currently strip, logo strip, featured writing, CTA), fetchWithRetry
  (retry-once semantics), ErrorMessage title/retry, blog tabs +
  featured-selection logic, SkillRatingBar level words, mobile hero
  rendering (badge/tagline/2 chips at mobile width), footer, news load
  more + source chip re-query.
- **Backend (Gradle/Testcontainers)**: contentType on DTOs + latest
  filter, sources endpoint, digest generator sets DIGEST, Mongock change
  units tested with the repo's isolated-boot pattern (change units
  disabled in shared ITs).
- **Playwright e2e** (existing `frontend/e2e/`): smoke for `/blog`
  redirect, unknown URL → 404 page, blog tabs default to Engineering.
- CI green before PR; verify against the local stack (backend + frontend
  + restored prod data) before merge.

## Out of scope

Real photo vs cartoon avatar, demoting News & Events in the nav, hero
background art change, brotli compression, WebP/AVIF image work, blog
server-side tag filtering.
