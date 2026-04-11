# Implementation Plan: Light & Dark Mode Theme Support

**Branch**: `017-light-dark-mode` | **Date**: 2026-04-10 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/017-light-dark-mode/spec.md`

## Summary

Add light and dark theme support to the public-facing frontend. The site currently uses a dark-only CSS custom property system. This feature introduces a light theme variant via `[data-theme="light"]` CSS overrides, automatic OS preference detection, a manual Sun/Moon toggle in the navigation, and localStorage persistence. No backend changes required.

## Technical Context

**Language/Version**: TypeScript (latest), CSS3 custom properties
**Primary Dependencies**: React (latest stable), Lucide React (icons), Vite (build)
**Storage**: Browser localStorage (theme preference only)
**Testing**: Vitest (unit), Playwright (visual/e2e)
**Target Platform**: Web browsers (Chrome, Firefox, Safari, Edge — latest 2 versions)
**Project Type**: Web application (frontend-only change)
**Performance Goals**: Theme applied within 100ms of page load (no flash); toggle transition under 300ms
**Constraints**: Single `styles.css` file (constitution); no CSS framework; Lucide React icons only
**Scale/Scope**: ~7,600 lines of CSS; ~340 hardcoded color values to audit; ~50 component sections

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Frontend-only change, no container impact |
| II. Modern Java & React Stack | PASS | Uses plain CSS + BEM + custom properties in single `styles.css`. Lucide React for toggle icon. No CSS framework introduced. |
| III. Quality Gates | PASS | Frontend tests will cover theme toggle. Visual verification for both themes. |
| IV. Observability | N/A | No backend changes |
| V. Simplicity & Incremental Delivery | PASS | Minimal new code: 1 context, 1 CSS override block, 1 toggle button. No premature abstractions. |
| VI. Admin CMS UX | PASS | Admin pages excluded from scope — remain dark-only |
| VII. Backup & Restore | N/A | No data changes |
| VIII. Shell Scripting | N/A | No scripts involved |

**Post-Phase 1 Re-check**: All gates still pass. No new dependencies, no architecture changes, single-file CSS approach preserved.

## Project Structure

### Documentation (this feature)

```text
specs/017-light-dark-mode/
├── plan.md              # This file
├── research.md          # Phase 0: theme mechanism, color palette, flash prevention
├── data-model.md        # Phase 1: localStorage schema, theme state
├── quickstart.md        # Phase 1: implementation guide
├── contracts/           # Phase 1: client-side interface contracts
│   └── README.md
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
frontend/
├── index.html                              # Add blocking theme script in <head>
├── src/
│   ├── App.tsx                             # Wrap in ThemeProvider
│   ├── contexts/
│   │   └── ThemeContext.tsx                 # NEW: Theme state, toggle, persistence
│   ├── components/
│   │   └── layout/
│   │       └── TopNav.tsx                  # Add Sun/Moon toggle button
│   ├── styles.css                          # Add [data-theme="light"] overrides;
│   │                                       # convert hardcoded colors to variables
│   ├── pages/                              # No changes (consume CSS variables)
│   └── hooks/                              # No changes
└── tests/
    └── theme.test.ts                       # NEW: Theme toggle + persistence tests
```

**Structure Decision**: Frontend-only changes within the existing web application structure. One new file (`ThemeContext.tsx`), one new test file, and modifications to 4 existing files (`index.html`, `App.tsx`, `TopNav.tsx`, `styles.css`).

## Phase 0 Findings (Research)

See [research.md](research.md) for full details. Key decisions:

1. **Theme mechanism**: `data-theme` attribute on `<html>`, CSS variable overrides
2. **Flash prevention**: Blocking inline script in `index.html` `<head>`
3. **Hardcoded colors**: ~340 values to audit; prioritise hero overlays, `color: white`, border opacities
4. **Light palette**: Based on approved `designs/landing-light.html` prototype
5. **Toggle UI**: Sun/Moon Lucide icons in TopNav
6. **Persistence**: localStorage key `theme-preference`

## Phase 1 Deliverables

| Artifact | Status | Path |
|----------|--------|------|
| research.md | Complete | [research.md](research.md) |
| data-model.md | Complete | [data-model.md](data-model.md) |
| contracts/ | Complete | [contracts/README.md](contracts/README.md) |
| quickstart.md | Complete | [quickstart.md](quickstart.md) |

## Complexity Tracking

No constitution violations. No complexity justifications needed.
