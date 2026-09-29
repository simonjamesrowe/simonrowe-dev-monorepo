# Tasks: Site Navigation, Landing Hero and Portfolio

`[P]` = can run in parallel with other `[P]` tasks in the same phase. Each phase is one pull request.

## Phase 1 — Header (PR 1, Stories 1–2)

- [x] T001 Delete `frontend/src/prototype/`, and revert its hooks in `App.tsx`, `HomePage.tsx` and
  `vite.config.ts`. Keep the `VITE_PROXY_TARGET` escape hatch only if it is documented in the README.
- [x] T002 [P] `frontend/src/components/layout/navModel.ts`: the four groups (labels, destinations,
  descriptions, icons) and `groupIsActive(group, pathname)`. Portfolio is empty, and therefore hidden, until Phase 4.
- [x] T003 [P] Add the `--ai-accent*` tokens (dark and light) to `styles.css`.
- [x] T004 `HeaderMenu.tsx`: a disclosure trigger plus panel. It opens on click and on pointer hover, closes on
  Escape, an outside click or a route change, exposes `aria-expanded`/`aria-controls`, and has the "Take a
  tour" footer on About.
- [x] T005 `SiteHeader.tsx`: the floating capsule.
  - Brand, four `HeaderMenu`s, the compact `SiteSearch` with a `/` hint, the theme toggle, and the Ask button
    (keeps `top-nav__ask-ai` and `data-testid="open-chat"`).
  - The admin link, gated as today.
  - A global `/` and Ctrl/⌘+K shortcut that focuses the search input.
  - A shadow that strengthens on scroll.
- [x] T006 `MobileNavSheet.tsx`: the full-screen sheet.
  - Search first, then accordion groups, the theme row, the admin link and the pinned Ask button.
  - Scroll lock, focus trap and focus restore; closes on navigation.
- [x] T007 `FloatingAskPill.tsx`: a corner pill (a sparkle button on phones) shown once
  `[data-ask-anchor]` has scrolled out of view, or always when the page has no anchor. It sits above the
  narration bar when that is visible, and respects `prefers-reduced-motion`.
- [x] T008 Wire it up in `App.tsx` `PublicLayout`: `SiteHeader` + `FloatingAskPill`, delete
  `TopNav.tsx`/`MobileMenu.tsx`, and add the CSS (BEM `site-header__*`, `header-menu__*`, `nav-sheet__*`,
  `ask-pill__*`).
- [x] T009 [P] Tests:
  - `tests/components/layout/SiteHeader.test.tsx`: menus open and close, keyboard, `aria-expanded`, the
    current group, the search shortcut, and the admin link.
  - `MobileNavSheet.test.tsx`: accordion, scroll lock, closes on navigation.
  - `FloatingAskPill.test.tsx`.
  - Port `AdminNavGating.test.tsx` and the nav assertions in `HomePage.test.tsx`.
- [x] T010 Verify the tour in a real browser: the `.tour-search` and `.top-nav__ask-ai` steps still spotlight
  and act, at 1440, 1024 and 390px.

## Phase 2 — Hero content backend (PR 2, Story 4 backend)

- [ ] T011 `com.simonrowe.homepage`:
  - `HomePage` record (`@Document("home_page")`, id `"home"`), plus nested `Cta` and `AskPill`.
  - `HomePageRepository`.
  - `HomePageService`: `get()` returns the stored row or `HomePageDefaults`; `save(request)` validates.
- [ ] T012 `LinkTargets.isAllowed(href)`: a site path (`/…`, not `//`, no backslash) or a parsed `URI` with
  scheme `https` and a host. Unit test it with `//evil`, `javascript:`, `http:`, `https://x`,
  `/about#roles`, `/\evil` and a 100k-character input.
- [ ] T013 `HomePageController` `GET /api/home-page`, and `HomePageAdminController` `GET`/`PUT
  /api/admin/home-page`, with per-field `ValidationErrorResponse` 400s.
- [ ] T014 `V048CreateHomePageAndPortfolio` (hero part): seed `_id: "home"` if absent. Integration test: it
  runs at boot, is idempotent, and does not overwrite an edited row.
- [ ] T015 Backup/restore: add `home_page` to `BACKUP_COLLECTIONS` and `IMPORT_ORDER_INDEPENDENT`, with a
  comment explaining why.
- [ ] T016 Tests:
  - `HomePageAdminControllerTest` (Testcontainers): a round-trip of every field, a 400 naming the field for
    each rule, and 401/403 for non-admins.
  - `HomePageControllerTest`: defaults when empty, stored row when present.
  - `SecurityConfigTest`: the public GET stays public.

## Phase 3 — Landing hero and Home page editor (PR 3, Stories 3–4 frontend)

- [ ] T017 [P] `types/homePage.ts`, `services/homePageApi.ts` (public fetch plus admin get/put), and
  `hooks/useHomePage.ts`, which falls back to built-in defaults if the fetch fails.
- [ ] T018 `components/home/LandingHero.tsx`:
  - Content: the `<picture>` (mobile image at 768px and below), the shade, the eyebrow from the profile, the
    two-line headline, the lede (hidden on phones), both CTAs (`Link` for site paths, `<a>` for `https`), and
    the tour button.
  - The Ask pill carries `tour-home-chat` and `data-ask-anchor`.
- [ ] T019 Replace `HeroSection` in `HomePage.tsx`, then delete `HeroSection.tsx` and its test.
  (`TourButton` was already removed in Phase 1, because the Ask pill took its corner.)
- [ ] T020 `pages/admin/HomePageAdmin.tsx`:
  - Grouped fields with character counters and limits.
  - Inline field errors from the 400 response.
  - A read-only strip showing the profile's two background images, with a link to `/admin/profile`.
  - `useUnsavedChanges`.
  - Plus the sidebar entry "Home page" (Lucide `House`) and the route.
- [ ] T021 Tests:
  - `LandingHero.test.tsx`: copy rendered from props, CTA link types, pill opens chat, tour button starts the
    tour, mobile image source.
  - `HomePage.test.tsx`: section order with `.landing-hero`.
  - `HomePageAdmin.test.tsx`: loads, edits, saves every field, shows server errors.

## Phase 4 — Portfolio (PR 4, Story 5)

- [ ] T022 `com.simonrowe.portfolio`: the `PortfolioProject` record (`@Document("portfolio_projects")`), the
  `ProjectStatus` enum, the repository (`findByPublishedTrueOrderByDisplayOrderAscNameAsc`,
  `findBySlugAndPublishedTrue`) and the service.
- [ ] T023 `PortfolioController`: `GET /api/portfolio` (reduced shape for Coming soon) and `GET
  /api/portfolio/{slug}` (404 for Coming soon, unpublished or unknown). Images are hydrated.
- [ ] T024 `PortfolioAdminController`: list, create (409 on a duplicate slug, caught as
  `DuplicateKeyException` with no read-before-write), get, update, delete, and `PATCH /reorder`.
- [ ] T025 Change unit, `V048` if PR 2 has not shipped, otherwise `V049SeedPortfolio`:
  - A static `createIndexes` creating `idx_portfolio_slug` (unique) and `idx_portfolio_published_order`.
  - Seed the four projects only for slugs that are absent.
  - Integration test: boot run, idempotence, duplicate-slug rejection, multi-row insert still fine.
- [ ] T026 Backup/restore: add the collection to both lists and a `postImportIndexHooks` entry calling
  `createIndexes`. Test that a restore re-creates the unique index.
- [ ] T027 [P] Frontend `types/portfolio.ts`, `services/portfolioApi.ts` and `hooks/usePortfolio.ts`, which
  is shared by the header and the home page so both make one request.
- [ ] T028 [P] `components/portfolio/ProjectSilhouette.tsx` (hue-tinted, `aria-hidden`) and
  `ProjectCard.tsx`.
- [ ] T029 `components/home/PortfolioCarousel.tsx`: numbered 01…, arrows and scroll-snap on the same pattern
  as `FeaturedWriting`, placed after `EmployerLogoStrip`, and renders nothing when the list is empty.
- [ ] T030 `pages/PortfolioPage.tsx` (`/portfolio`) and `pages/PortfolioProjectPage.tsx`
  (`/portfolio/:slug`, not found otherwise). Add the routes and `linkPolicy` internal routes.
- [ ] T031 The Portfolio menu lists projects from `usePortfolio` with a "Soon" badge; the phone sheet does the
  same.
- [ ] T032 `pages/admin/PortfolioAdmin.tsx`: the list with `CheckCircle`/`XCircle` published, `Pencil`/`Trash2`
  actions, and drag-and-drop reorder calling `PATCH /reorder`. `PortfolioProjectEditor.tsx`: a two-column top
  (name, slug, tagline | `ImagePicker`), status, published, live URL, hue slider with a live silhouette
  preview, and a markdown description at 250px min-height. Plus the sidebar entry and routes.
- [ ] T033 Tests: backend controller and round-trip tests, plus frontend carousel, pages, menu and admin tests.

## Final

- [ ] T034 Update `CLAUDE.md` "Recent Changes", the `docs/runbooks` note on hero and portfolio content, and
  the README's frontend section.
- [ ] T035 A manual pass against restored prod data at 1440, 1024 and 390px in light and dark mode, covering
  the tour end to end.
