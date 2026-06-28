# Implementation Plan: Landing / Profile Cleanup

**Branch**: `025-landing-profile-cleanup` | **Date**: 2026-06-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/025-landing-profile-cleanup/spec.md`

## Summary

A frontend-only corrective round on top of the landing/profile split. Trim the
mobile landing hero to lead with the chat (hide badge, tagline, prompt chips on
phones; desktop unchanged), replace the fabricated profile content with the real
`main`-branch About + contact composition (keeping CV download and social links),
and remove the footer from every page — all while preserving the guided-tour
profile/contact selectors. Implementation is CSS + React component edits with
updated Vitest coverage; no backend or data changes.

## Technical Context

**Language/Version**: TypeScript 5.x, React (latest stable), Vite

**Primary Dependencies**: React Router, react-markdown, Lucide React; Vitest +
Testing Library for tests. No new dependencies.

**Storage**: N/A (reuses existing profile data via existing API; no schema/seed
changes)

**Testing**: Vitest (`cd frontend && npm test`), plus `npm run build`; backend
tour-seed test untouched but run to prove no regression
(`./gradlew :backend:test --tests '*TourSeedDefaults*'`)

**Target Platform**: Web (responsive: desktop + mobile, breakpoint max-width 768px)

**Project Type**: Web application (frontend slice only)

**Performance Goals**: No change; static hero render, no new network calls

**Constraints**: No horizontal overflow on mobile; desktop hero unchanged; tour
selectors `.tour-profile` / `.tour-contact` preserved

**Scale/Scope**: ~5 frontend files edited + 1-2 deleted, CSS section restore,
test updates. Single-session change.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle II (Modern React Stack)**: PASS. Changes use plain CSS with BEM
  naming and CSS custom properties (a responsive media query plus restored
  `.about-section` / profile blocks), Lucide React icons, and existing routing
  conventions. No CSS-in-JS introduced.
- **Principle III (Quality Gates)**: PASS. Frontend tests for the affected
  critical journeys (mobile landing, profile content, footer absence) are added
  or updated; `npm run build` and `npm test` must pass. No backend code touched,
  so Java style / JaCoCo / Testcontainers gates are unaffected.
- **Principle V (Simplicity & Incremental Delivery)**: PASS. Reuses existing
  `main` components rather than adding new abstractions; deletes dead code
  (`BioSection`).
- **Principle VII (Interactive Site Tour)**: PASS. No tour step data, selectors,
  or backend seed changes; `.tour-profile` and `.tour-contact` remain present on
  the profile page so cross-page tour steps still resolve.

No violations. Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/025-landing-profile-cleanup/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── README.md        # No new contracts (documents reuse)
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── App.tsx                              # remove Footer from layout (all routes)
│   ├── components/
│   │   ├── home/
│   │   │   ├── HeroSection.tsx              # markup unchanged; covered by CSS
│   │   │   └── AboutSection.tsx             # restore main's clean component
│   │   ├── profile/
│   │   │   ├── BioSection.tsx               # DELETE (fabricated copy source)
│   │   │   └── SocialLinks.tsx              # reused
│   │   ├── contact/
│   │   │   └── ContactSection.tsx           # reused (contact form)
│   │   └── layout/
│   │       └── Footer.tsx                    # unused after removal (delete)
│   ├── pages/
│   │   └── ProfilePage.tsx                  # compose About + Connect (CV/socials/form)
│   └── styles.css                           # mobile hero media query; restore
│                                            # .about-section/profile CSS; drop bio-section
└── tests/                                   # update HomePage / ProfilePage / App / AboutSection
```

**Structure Decision**: Web application, frontend slice only. All edits live in
`frontend/src` and `frontend/tests`; no backend module is modified.

## Complexity Tracking

No constitution violations — section intentionally empty.
