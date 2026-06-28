# Implementation Plan: Landing Profile Split

**Branch**: `feat/frontend/landing-chat-widgets` | **Date**: 2026-06-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/024-landing-profile-split/spec.md`

## Summary

Redesign the public homepage into a centered chat-first hero that keeps the current background photo, uses a full-width top banner, and ends after the chat experience before the footer. Move biography, CV/social links, and contact to a public `/profile` route, then retarget frontend tour actions and seeded tour data so the guided tour follows the new page structure.

## Technical Context

**Language/Version**: Java 21, TypeScript 5.7, React 19

**Primary Dependencies**: Spring Boot 3.5.x, Spring Data MongoDB, React Router, Vite 6, lucide-react, React Hook Form, Zod, Testing Library, Vitest, JUnit 5

**Storage**: MongoDB for profile content and `tourSteps`

**Testing**: Vitest for frontend unit/route tests; Gradle/JUnit for backend tour seed tests

**Target Platform**: Public web app served by React frontend with Spring Boot backend APIs

**Project Type**: Web application with separate frontend and backend modules

**Performance Goals**: No additional network calls on homepage beyond existing profile/chat dependencies; route render behavior remains client-side and responsive.

**Constraints**: Use plain CSS in `frontend/src/styles.css`; use existing components and BEM class naming; do not change chat transport, admin CMS shape, backup/restore, or authentication.

**Scale/Scope**: Public homepage, Profile page, navigation/footer links, tour frontend actions, and deterministic tour seed data.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Monorepo with Separate Containers**: Pass. Work stays within existing frontend/backend modules and does not alter container boundaries.
- **Modern Java & React Stack**: Pass. Uses existing Java 21, React 19, MongoDB, React Router, lucide-react, plain CSS, and BEM conventions.
- **Quality Gates**: Pass. Frontend route/page tests and backend tour seed tests are planned.
- **Observability & Operability**: Pass. No new runtime service or logging requirement.
- **Simplicity & Incremental Delivery**: Pass. Uses existing components and route structure; no new framework or persistence model.
- **Admin CMS UX Standards**: Pass. Tour admin APIs and CMS screens are unchanged.
- **Interactive Site Tour**: Pass. Tour selectors, routes, and seeded data are explicitly retargeted.
- **Backup & Restore**: Pass. Backup/restore mechanics are unchanged.
- **Shell Scripting**: Pass. No new shell scripts required.

## Project Structure

### Documentation (this feature)

```text
specs/024-landing-profile-split/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── ui-tour-contract.md
└── tasks.md
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/migration/DataMigrationService.java
├── src/main/java/com/simonrowe/admin/TourStep.java
└── src/test/java/com/simonrowe/tour/

frontend/
├── src/App.tsx
├── src/components/home/HeroSection.tsx
├── src/components/layout/Footer.tsx
├── src/components/layout/MobileMenu.tsx
├── src/components/layout/TopNav.tsx
├── src/components/tour/tourActions.ts
├── src/pages/HomePage.tsx
├── src/pages/ProfilePage.tsx
├── src/styles.css
└── tests/
```

**Structure Decision**: Keep the existing two-module web app structure. The homepage/profile split is implemented with existing React pages and components; tour seed updates live in the backend migration/seed service that already owns data migration concerns.

## Complexity Tracking

No constitution violations.

## Phase 0 Research

Resolved in [research.md](./research.md).

## Phase 1 Design & Contracts

- Data model: [data-model.md](./data-model.md)
- UI/tour contract: [contracts/ui-tour-contract.md](./contracts/ui-tour-contract.md)
- Manual validation: [quickstart.md](./quickstart.md)

## Post-Design Constitution Check

Pass. The design uses current app structure and existing storage, preserves the chat/contact implementations, and adds tests around the changed public routes and tour data.
