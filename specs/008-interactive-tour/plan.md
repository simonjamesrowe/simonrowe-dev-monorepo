# Implementation Plan: Interactive Tour

**Branch**: `008-interactive-tour` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/008-interactive-tour/spec.md`

## Summary

Interactive guided tour for first-time visitors that overlays the homepage with step-by-step highlighted elements, tooltips with navigation controls, and an automated search simulation. The backend provides a REST endpoint serving ordered tour step configuration from MongoDB. The frontend renders an overlay with spotlight/cutout effect on the target element, a positioned tooltip with markdown-rendered descriptions and optional title images, and a character-by-character search input simulation. The tour is desktop-only (hidden below 768px) and can be exited at any point. A custom React implementation replaces the legacy intro.js dependency with a lighter, more controllable solution using CSS clip-path for the spotlight effect and React context for tour state management.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4, Spring Data MongoDB (backend); React latest stable, react-markdown (frontend)
**Storage**: MongoDB (tour step documents)
**Testing**: JUnit 5 + Testcontainers (backend integration), Vitest + React Testing Library (frontend)
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Tour step API response under 200ms; tooltip transitions under 300ms; search simulation completes 3 queries in under 10 seconds
**Constraints**: Desktop-only (768px+ viewport); tour exit removes all overlays within 1 second; no additional runtime dependencies beyond react-markdown (already in use)
**Scale/Scope**: Single REST endpoint, 5-10 tour steps, 6 React components, 1 custom hook, 1 API service module

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | Tour backend code lives in `backend/` as part of the existing Spring Boot service. Tour frontend components live in `frontend/`. No new containers required -- both deploy within existing backend and frontend containers. |
| II | Modern Java & React Stack | PASS | Java 25 with Spring Boot 4 and Spring Data MongoDB for the tour step API. React latest stable for frontend tour components. MongoDB stores tour step configuration. No new technology dependencies introduced. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Backend: Google Java Style enforced, JaCoCo coverage for TourController/TourService, Testcontainers integration test against real MongoDB. Frontend: Vitest unit tests for TourProvider state management, TourOverlay rendering, SearchSimulation timing, and TourButton visibility. |
| IV | Observability & Operability | PASS | Tour step API inherits existing Spring Boot Actuator metrics on the management port. API request latency tracked via existing OpenTelemetry instrumentation. Structured logging for tour step retrieval errors. |
| V | Simplicity & Incremental Delivery | PASS | Custom overlay implementation uses standard CSS (clip-path, fixed positioning) rather than a third-party tour library. No intro.js dependency -- reduces bundle size and gives full control over spotlight behavior and search simulation timing. Two user stories map to two increments: P1 core tour, P2 search simulation. |

## Project Structure

### Documentation (this feature)

```text
specs/008-interactive-tour/
├── plan.md              # This file
├── research.md          # Phase 0: Tour library evaluation, CSS overlay techniques
├── data-model.md        # Phase 1: TourStep MongoDB document model
├── quickstart.md        # Phase 1: Developer guide for running and testing the tour
├── contracts/
│   └── tour-api.yaml    # OpenAPI spec for GET /api/tour/steps
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/java/com/simonrowe/tour/
│   │   ├── TourController.java          # REST controller: GET /api/tour/steps
│   │   ├── TourService.java             # Business logic: fetch and order tour steps
│   │   ├── TourStep.java                # MongoDB document entity
│   │   └── TourStepRepository.java      # Spring Data MongoDB repository
│   └── test/java/com/simonrowe/tour/
│       ├── TourControllerTest.java       # WebMvcTest for controller layer
│       └── TourStepRepositoryTest.java   # Integration test with Testcontainers MongoDB

frontend/
├── src/
│   ├── components/tour/
│   │   ├── TourButton.tsx               # "Take a Tour" button, hidden on mobile (<768px)
│   │   ├── TourOverlay.tsx              # Full-screen overlay with spotlight cutout
│   │   ├── TourTooltip.tsx              # Positioned tooltip with title, image, markdown, nav
│   │   ├── TourProvider.tsx             # React context provider managing tour state
│   │   └── SearchSimulation.tsx         # Character-by-character search input simulator
│   ├── hooks/
│   │   └── useTour.ts                   # Custom hook consuming TourProvider context
│   └── services/
│       └── tourApi.ts                   # API client: fetch tour steps from backend
└── tests/
    ├── components/tour/
    │   ├── TourButton.test.tsx          # Visibility tests for desktop/mobile viewports
    │   ├── TourOverlay.test.tsx         # Overlay render and spotlight position tests
    │   ├── TourTooltip.test.tsx         # Tooltip content, navigation, and position tests
    │   ├── TourProvider.test.tsx        # State transitions: start, next, prev, exit
    │   └── SearchSimulation.test.tsx    # Typing simulation timing and cleanup tests
    └── hooks/
        └── useTour.test.ts             # Hook integration with provider context
```

**Structure Decision**: Option 2 (Web application) selected. Tour backend code is a new package (`com.simonrowe.tour`) within the existing `backend/` Gradle subproject. Tour frontend code is a new component directory (`components/tour/`) within the existing `frontend/` project. No new Gradle subprojects or npm packages are introduced. This follows the established pattern from the infrastructure spec (001) and keeps the tour feature as a natural extension of the existing backend service and frontend application.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
