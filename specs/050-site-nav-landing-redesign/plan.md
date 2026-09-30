# Implementation Plan: Site Navigation, Landing Hero and Portfolio

**Branch**: `simonrowe/nukualofa` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/050-site-nav-landing-redesign/spec.md`

## Summary

Replace `TopNav` + `MobileMenu` with one `SiteHeader`: a floating capsule with four disclosure menus, the existing
`SiteSearch` field kept visible in compact form, and a full-screen accordion menu on phones. Replace the home
`HeroSection` with a photographic hero whose copy comes from a new `home_page` singleton, plus an Ask pill that
docks to a corner once scrolled past. Add a `portfolio_projects` collection with public and admin APIs, an admin
list and editor, a `/portfolio` page, a `/portfolio/:slug` detail page, and a numbered home carousel. The work is
delivered as four independently mergeable pull requests (see Delivery). The throwaway prototype is deleted in
the first one.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 4.1.1 (web, security OAuth2 resource server, data-mongodb, validation),
Mongock 5.5.1, React Router, Lucide React, the existing MDXEditor markdown editor and Media Library. **No new
dependencies.**

**Storage**: MongoDB. Two new collections (`home_page`, `portfolio_projects`). Portfolio indexes and seed data
come from the Mongock change unit `V048CreatePortfolioProjects`. The hero needs none, because its defaults are
served in code.

**Testing**: JUnit 6 + MockMvc + Testcontainers Mongo via `AbstractIntegrationTest`; Vitest + Testing Library;
the existing Playwright e2e project for the header journey.

**Target Platform**: The existing frontend and backend containers. No compose, nginx or deploy change.

**Project Type**: Monorepo web application (separate Spring API and React SPA containers)

**Performance Goals**: The home page makes one extra request (`/api/home-page`) and one for the portfolio. Both
are small and parallel with the profile fetch. No request on the hero's critical path blocks the image.

**Constraints**:
- Public reads are unauthenticated. Writes go under `/api/admin/**` (ADMIN role, already enforced in
  `SecurityConfig`).
- `auto-index-creation` is off, so indexes must come from Mongock.
- Destinations are validated by parsed URL.
- Tour selectors are preserved.
- Nothing under `frontend/src/prototype/` ships.

**Scale/Scope**: ~4 portfolio rows and 1 hero row; 6 new endpoints; ~8 new frontend components.

## Constitution Check

- **I. Separate containers**: PASS. No topology change.
- **II. Modern Java & React stack**: PASS. Records and DTOs, plain BEM CSS in `styles.css`, Lucide icons.
- **III. Quality gates**: PASS. Testcontainers integration tests for both controllers and the change unit, a
  frontend test for every journey, and checkstyle.
- **IV. Observability**: PASS. No new infrastructure.
- **V. Simplicity**: PASS. A singleton document rather than a generic content store. The menu structure is
  static code; only the portfolio is dynamic.
- **VI. Admin CMS UX**: PASS. The Portfolio list uses Lucide status and action icons, the editor uses the Media
  Library drawer, and the markdown area uses the existing editor at 250px min-height.
- **VII. Interactive tour**: PASS with a note. The floating button is removed; "Take a tour" buttons in the hero
  and in the About menu start the same tour. Selectors are preserved (research R7).
- **VIII. Backup & restore**: PASS. Both collections are added to the backup/restore lists, and restore
  re-creates the indexes.

## Project Structure

### Documentation (this feature)

```text
specs/050-site-nav-landing-redesign/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md, tasks.md
├── contracts/openapi.yaml
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/
├── homepage/          HomePage (document), HomePageRepository, HomePageService,
│                      HomePageController (public), HomePageAdminController, HomePageDto, LinkTargets
├── portfolio/         PortfolioProject, ProjectStatus, PortfolioProjectRepository, PortfolioService,
│                      PortfolioController (public), PortfolioAdminController, DTOs
├── migration/changeunits/V048CreatePortfolioProjects.java
└── dataops/           BackupService / RestoreService list entries + index re-creation

frontend/src/
├── components/layout/ SiteHeader.tsx, HeaderMenu.tsx, MobileNavSheet.tsx,
│                      FloatingAskPill.tsx, navModel.ts   (TopNav.tsx, MobileMenu.tsx deleted)
├── components/home/   LandingHero.tsx (HeroSection.tsx deleted), PortfolioCarousel.tsx
├── components/portfolio/ ProjectSilhouette.tsx, ProjectCard.tsx
├── pages/             PortfolioPage.tsx, PortfolioProjectPage.tsx
├── pages/admin/       HomePageAdmin.tsx, PortfolioAdmin.tsx, PortfolioProjectEditor.tsx
├── services/          homePageApi.ts, portfolioApi.ts
├── hooks/             usePortfolio.ts, useHomePage.ts
└── types/             homePage.ts, portfolio.ts
```

**Conventions followed**:
- Admin controllers sit under `/api/admin/**` (the `DEV_PORTAL_ADMIN` role is enforced by the existing matcher).
- Reorder uses `PATCH …/reorder` with `ReorderRequest(orderedIds)`, like tour steps and skills.
- Images are stored as `common.Image` with a URL only and hydrated on read by `MediaImageHydrator`.
- Field errors go through `ValidationErrorResponse`.
- Admin sidebar entries go in `AdminLayout.navItems`; routes use lazy `named(...)` imports in `App.tsx`.
- Unlike the `Map`-bodied controllers, the new endpoints take typed request records, so the round-trip test can
  assert every field.

**Structure Decision**: Two small backend packages mirror the existing per-feature layout (`code`, `profile`).
The header splits into small components so each can be tested alone. The nav model is a plain data file shared
by the desktop and mobile menus, so they cannot drift.

## Delivery (four pull requests, each independently shippable)

1. **Header** (Stories 1–2): `SiteHeader` with the About, Insights and Under the hood menus, the compact
   search field, the phone sheet and the floating Ask pill. The Portfolio menu is hidden until PR 4 gives it destinations.
   Deletes the prototype. The hero is untouched.
2. **Hero content backend** (Story 4, backend): the `homepage` package and backup/restore entries. No change
   unit.
3. **Landing hero + Home page editor** (Stories 3–4, frontend): `LandingHero`, removal of `HeroSection` and
   `TourButton`, and `HomePageAdmin`.
4. **Portfolio** (Story 5): backend package, the `V048` indexes and seed, admin list and editor, public
   pages, carousel, and menu items.

## Complexity Tracking

No constitution violations require justification.
