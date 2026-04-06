# Implementation Plan: Landing Page AI Redesign

**Branch**: `015-landing-ai-redesign` | **Date**: 2026-04-06 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/015-landing-ai-redesign/spec.md`

## Summary

Redesign the homepage hero section from a two-column grid layout (content left, chat teaser right) to a single-column centered layout with the AI chat input as the primary focal point. Add a global "Ask AI" pill button to the top navigation bar, accessible from all public pages. This requires lifting chat state from HomePage into a shared React context so that the ChatPanel and RecaptchaGate can be triggered from both the hero section and the TopNav on any page.

## Technical Context

**Language/Version**: TypeScript (frontend), React (latest stable)
**Primary Dependencies**: React, React Router, Lucide React, @stomp/stompjs, react-markdown
**Storage**: N/A (frontend-only changes; chat uses existing WebSocket service)
**Testing**: Vitest (frontend unit/component tests)
**Target Platform**: Web browsers (desktop + mobile, 320px–2560px viewports)
**Project Type**: Web application (frontend-only changes for this feature)
**Performance Goals**: Hero renders above the fold within 2s on standard viewports
**Constraints**: Plain CSS with BEM naming in single styles.css; Lucide React icons only; no CSS framework
**Scale/Scope**: 4 components modified, 1 new context provider, 1 new component, CSS updates

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Frontend-only changes, no container impact |
| II. Modern Java & React Stack | PASS | Uses React, Lucide React, plain CSS + BEM, @stomp/stompjs |
| III. Quality Gates | PASS | Frontend tests will be updated to cover new structure |
| IV. Observability | N/A | No backend changes |
| V. Simplicity & Incremental Delivery | PASS | Minimal new abstractions (one context provider to lift existing state) |
| VI. Admin CMS UX Standards | N/A | Public pages only |
| VII. Backup & Restore | N/A | No data changes |
| VIII. Shell Scripting Standards | N/A | No scripts |

No violations. No Complexity Tracking entries needed.

## Project Structure

### Documentation (this feature)

```text
specs/015-landing-ai-redesign/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (minimal — no new entities)
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── components/
│   │   ├── chat/
│   │   │   ├── ChatPanel.tsx          # Existing — no changes needed
│   │   │   ├── RecaptchaGate.tsx      # Existing — no changes needed
│   │   │   └── ...
│   │   ├── home/
│   │   │   └── HeroSection.tsx        # MODIFY — centered single-column layout
│   │   └── layout/
│   │       └── TopNav.tsx             # MODIFY — add "Ask AI" button
│   ├── contexts/
│   │   └── ChatContext.tsx            # NEW — shared chat state provider
│   ├── pages/
│   │   └── HomePage.tsx               # MODIFY — consume ChatContext, remove local chat state
│   ├── App.tsx                        # MODIFY — wrap PublicLayout with ChatProvider
│   └── styles.css                     # MODIFY — hero centered layout, Ask AI button styles
└── tests/
    ├── components/
    │   └── home/
    │       └── HeroSection.test.tsx   # UPDATE — test new centered layout
    └── ...
```

**Structure Decision**: Web application structure. All changes are frontend-only within the existing `frontend/` directory. No backend modifications needed. One new file (`ChatContext.tsx`) provides shared chat state; all other changes modify existing files.
