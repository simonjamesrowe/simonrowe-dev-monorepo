# Tasks: Interactive Tour

**Feature**: 008-interactive-tour
**Date**: 2026-02-21
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

---

## Phase 2: Foundational

Backend tour step data model, repository, service, and REST endpoint. Frontend API service and TypeScript types.

- [x] T001 Create TourStep MongoDB document record with `id`, `order`, `targetSelector`, `title`, `titleImage`, `description`, `position` fields, `@Document(collection = "tourSteps")` annotation, and `@Indexed(unique = true)` on `order` in `backend/src/main/java/com/simonrowe/tour/TourStep.java`
- [x] T002 Create TourStepRepository extending `MongoRepository<TourStep, String>` with `findAllByOrderByOrderAsc()` query method in `backend/src/main/java/com/simonrowe/tour/TourStepRepository.java`
- [x] T003 Create TourService with method to fetch all tour steps ordered by `order` ascending via TourStepRepository in `backend/src/main/java/com/simonrowe/tour/TourService.java`
- [x] T004 Create TourController with `GET /api/tour/steps` endpoint returning `List<TourStep>` ordered by `order` ascending, per OpenAPI contract in `specs/008-interactive-tour/contracts/tour-api.yaml`, in `backend/src/main/java/com/simonrowe/tour/TourController.java`
- [x] T005 Create TourControllerTest as WebMvcTest verifying the endpoint returns ordered steps, handles empty collections, and returns proper error responses in `backend/src/test/java/com/simonrowe/tour/TourControllerTest.java`
- [x] T006 Create TourStepRepositoryTest as Testcontainers integration test verifying MongoDB CRUD operations and order-based sorting in `backend/src/test/java/com/simonrowe/tour/TourStepRepositoryTest.java`
- [x] T007 Create TypeScript `TourStep` interface with `id`, `order`, `targetSelector`, `title`, `titleImage` (nullable), `description`, and `position` (union type) in `frontend/src/types/tour.ts`
- [x] T008 Create `fetchTourSteps()` API service function calling `GET /api/tour/steps` and returning `Promise<TourStep[]>` in `frontend/src/services/tourApi.ts`

---

## Phase 3: US1 - First-Time Visitor Guided Journey (P1)

Implements User Story 1: A first-time visitor clicks "Take a Tour" on the homepage and is guided through key sections with an overlay highlighting elements, tooltips with navigation controls, and a progress indicator. Desktop only (hidden below 768px).

- [x] T009 [US1] Create TourProvider React context provider managing tour state (`isActive`, `currentStepIndex`, `steps`) with `start()`, `next()`, `prev()`, `exit()` actions via `useReducer`, fetching steps from `tourApi.ts` on `start()`, in `frontend/src/components/tour/TourProvider.tsx`
- [x] T010 [US1] Create `useTour` custom hook consuming TourProvider context with guard for usage outside provider, returning tour state and action functions in `frontend/src/hooks/useTour.ts`
- [x] T011 [US1] Create TourButton component rendering a "Take a Tour" button that calls `start()` from `useTour`, hidden on viewports below 768px via CSS media query `@media (max-width: 767px) { display: none }` in `frontend/src/components/tour/TourButton.tsx`
- [x] T012 [US1] Create TourOverlay component rendering a fixed full-screen transparent click-capture div (z-index 9998) that calls `exit()` on click, and applying CSS `box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.6)` spotlight class to the target element resolved via `document.querySelector(targetSelector)` in `frontend/src/components/tour/TourOverlay.tsx`
- [x] T013 [US1] Create TourTooltip component positioned relative to the highlighted element based on the step's `position` field (top/bottom/left/right/center with 12px gap), rendering title, optional `titleImage` as `<img>`, markdown description via `react-markdown`, progress indicator ("Step X of Y"), and Previous/Next/Exit navigation buttons with viewport boundary clamping (16px minimum edge padding) in `frontend/src/components/tour/TourTooltip.tsx`
- [x] T014 [US1] Implement tour step navigation logic: "Next" advances `currentStepIndex`, disables Previous on first step, replaces "Next" with "Finish" on last step which calls `exit()`, and scrolls target element into view via `scrollIntoView({ behavior: 'smooth', block: 'center' })` on each step change in `frontend/src/components/tour/TourProvider.tsx`
- [x] T015 [US1] Add `matchMedia('(min-width: 768px)')` listener in TourProvider that automatically calls `exit()` when viewport drops below 768px during an active tour, cleaning up all overlays and resetting state in `frontend/src/components/tour/TourProvider.tsx`
- [x] T016 [US1] Integrate TourProvider as a wrapper in the application component tree and add TourButton, TourOverlay, and TourTooltip rendering (conditionally based on `isActive` state) into the homepage layout in `frontend/src/components/tour/TourProvider.tsx` and the homepage component
- [x] T017 [US1] Create TourButton.test.tsx verifying button renders on desktop viewport, is hidden on mobile viewport (<768px), and calls `start()` on click in `frontend/tests/components/tour/TourButton.test.tsx`
- [x] T018 [US1] Create TourOverlay.test.tsx verifying overlay renders when tour is active, spotlight class is applied to target element, and `exit()` is called on overlay background click in `frontend/tests/components/tour/TourOverlay.test.tsx`
- [x] T019 [US1] Create TourTooltip.test.tsx verifying tooltip renders title, optional title image, markdown description, progress indicator, navigation buttons, and position calculation for all five positions in `frontend/tests/components/tour/TourTooltip.test.tsx`
- [x] T020 [US1] Create TourProvider.test.tsx verifying state transitions: start fetches steps and activates tour, next/prev update currentStepIndex, exit resets state, and matchMedia listener triggers exit on mobile resize in `frontend/tests/components/tour/TourProvider.test.tsx`
- [x] T021 [US1] Create useTour.test.ts verifying the hook returns context values when inside TourProvider and throws an error when used outside the provider in `frontend/tests/hooks/useTour.test.ts`

---

## Phase 4: US2 - Automated Search Demonstration (P2)

Implements User Story 2: During the search step, the search input automatically receives typed text character-by-character, demonstrating 3 progressively longer queries with AbortController cancellation on step exit or tour exit.

- [x] T022 [US2] Create SearchSimulation component that activates when the current tour step targets `.tour-search`, using AbortController to manage a character-by-character typing animation that sets the search input value via React controlled state in `frontend/src/components/tour/SearchSimulation.tsx`
- [x] T023 [US2] Implement progressive typing animation: type "spring boot" at 50ms per character, pause 1500ms, type "spring boot kubernetes" at 50ms per character, pause 1500ms, type "spring boot kubernetes jenkins" at 50ms per character (total ~6.2s, within 10s budget per SC-004) in `frontend/src/components/tour/SearchSimulation.tsx`
- [x] T024 [US2] Implement AbortController cancellation: abort the simulation immediately when the visitor clicks Next to leave the search step or clicks Exit to close the tour, clearing all pending timeouts and resetting the search input to its default empty state in `frontend/src/components/tour/SearchSimulation.tsx`
- [x] T025 [US2] Wire SearchSimulation into TourProvider/TourOverlay so it activates only on the step where `targetSelector` is `.tour-search` and deactivates on step change or tour exit in `frontend/src/components/tour/TourProvider.tsx`
- [x] T026 [US2] Create SearchSimulation.test.tsx verifying character-by-character typing of 3 queries with correct timing (50ms/char, 1500ms pause), AbortController cancellation stops simulation immediately, and search input resets to empty on cleanup in `frontend/tests/components/tour/SearchSimulation.test.tsx`

---

## Phase 5: Polish

Validation, edge case handling, and end-to-end verification.

- [x] T027 Verify tour step seed data matches the data model documents in `specs/008-interactive-tour/data-model.md` and insert sample tour steps into local MongoDB per quickstart instructions in `specs/008-interactive-tour/quickstart.md`
- [x] T028 Verify `GET /api/tour/steps` returns all 6 seeded tour steps in correct ascending order with all fields matching the OpenAPI contract in `specs/008-interactive-tour/contracts/tour-api.yaml`
- [x] T029 Verify complete tour walkthrough end-to-end on desktop: click "Take a Tour", navigate all 6 steps with Next/Previous, observe search simulation on step 3, click Finish on last step, confirm all overlays are removed
- [x] T030 Verify mobile responsive hiding: confirm "Take a Tour" button is not visible on viewports below 768px, and resize from desktop to mobile during active tour triggers automatic exit and overlay cleanup
- [x] T031 Verify edge case: clicking "Take a Tour" multiple times during the same session restarts the tour correctly without duplicate overlays or stale state
- [x] T032 Verify edge case: tour step targeting a non-visible element handles gracefully (scrolls into view or skips without error)
- [x] T033 Verify all backend tests pass: `./gradlew :backend:test` runs TourControllerTest and TourStepRepositoryTest successfully
- [x] T034 Verify all frontend tests pass: `npm test` in `frontend/` runs TourButton, TourOverlay, TourTooltip, TourProvider, SearchSimulation, and useTour tests successfully

---

## Dependencies & Execution Order

```
Phase 2: Foundational (sequential backend, parallel frontend types/api)
  T001 -> T002 -> T003 -> T004           (backend: model -> repo -> service -> controller)
  T005 (depends on T004)                  (controller test)
  T006 (depends on T001, T002)            (repository integration test)
  T007 (independent - frontend types)     (TypeScript interface)
  T008 (depends on T007)                  (API service)

Phase 3: US1 (depends on T007, T008 complete)
  T009 (depends on T008)                  (TourProvider - fetches from API)
  T010 (depends on T009)                  (useTour hook - consumes context)
  T011 (depends on T010)                  (TourButton - uses useTour)
  T012 (depends on T010)                  (TourOverlay - uses useTour)
  T013 (depends on T010)                  (TourTooltip - uses useTour)
  T014 (depends on T009)                  (navigation logic in TourProvider)
  T015 (depends on T009)                  (matchMedia listener in TourProvider)
  T016 (depends on T009, T011, T012, T013) (integration into homepage)
  T017 (depends on T011)                  (TourButton test)
  T018 (depends on T012)                  (TourOverlay test)
  T019 (depends on T013)                  (TourTooltip test)
  T020 (depends on T009, T014, T015)      (TourProvider test)
  T021 (depends on T010)                  (useTour test)

Phase 4: US2 (depends on T009, T012 complete)
  T022 (depends on T009)                  (SearchSimulation component)
  T023 (depends on T022)                  (typing animation logic)
  T024 (depends on T022)                  (AbortController cancellation)
  T025 (depends on T022, T009)            (wire into TourProvider)
  T026 (depends on T022, T023, T024)      (SearchSimulation test)

Phase 5: Polish (depends on all prior phases)
  T027 (depends on T004)                  (seed data verification)
  T028 (depends on T004, T027)            (API contract verification)
  T029 (depends on T016, T025)            (end-to-end tour walkthrough)
  T030 (depends on T011, T015)            (mobile responsive verification)
  T031 (depends on T016)                  (multiple starts edge case)
  T032 (depends on T016)                  (non-visible element edge case)
  T033 (depends on T005, T006)            (backend test verification)
  T034 (depends on T017-T021, T026)       (frontend test verification)
```

---

## Parallel Opportunities

Tasks marked with `[P]` can run in parallel with other tasks in the same phase. Beyond those explicitly marked, the following groups can be parallelized:

### Within Phase 2
- **Group A** (Backend): T001 through T006 (sequential chain: model -> repo -> service -> controller -> tests)
- **Group B** (Frontend): T007 -> T008 (parallel with Group A)

### Within Phase 3
- **Group A** (Core): T009 -> T010 (sequential: provider then hook)
- **Group B** (Components): T011, T012, T013 (all depend on T010, parallel with each other)
- **Group C** (Provider enhancements): T014, T015 (both depend on T009, parallel with each other and Group B)
- **Group D** (Tests): T017, T018, T019, T020, T021 (each depends on its respective component, parallel with each other)

### Within Phase 4
- **Group A**: T022 is the foundation; T023, T024 extend it (parallel with each other after T022)
- **Group B**: T025 depends on T022 and T009, parallel with T023/T024
- **Group C**: T026 depends on T023 and T024

### Within Phase 5
- **Group A**: T027, T028 (sequential: seed then verify API)
- **Group B**: T029, T030, T031, T032 (end-to-end verifications, parallel with each other after Phase 3+4 complete)
- **Group C**: T033, T034 (test verifications, parallel with each other)

---

## Implementation Strategy

### Recommended Execution Sequence

1. **Start with backend model and repository** (T001-T002). The TourStep record and repository define the data layer and must exist before the service or controller.

2. **Frontend types in parallel** (T007). The TypeScript interface has no backend dependency and can be created alongside the backend model.

3. **Backend service and controller** (T003-T004). These depend on the repository and expose the REST endpoint.

4. **Backend tests** (T005-T006). Write controller and repository tests while the API shape is fresh. The Testcontainers integration test (T006) validates real MongoDB behavior.

5. **Frontend API service** (T008). Depends on the TypeScript types and consumes the backend endpoint.

6. **TourProvider and useTour hook** (T009-T010). The provider is the central state manager for the entire tour feature. All UI components depend on it.

7. **UI components as a batch** (T011-T013). TourButton, TourOverlay, and TourTooltip can be developed in parallel since they all depend on `useTour` but not on each other.

8. **Provider enhancements** (T014-T015). Navigation logic and responsive matchMedia listener are additions to TourProvider that can be done in parallel with the UI components.

9. **Homepage integration** (T016). Wire all components together in the application tree. This is the "it works" moment for US1.

10. **Frontend tests for US1** (T017-T021). Write tests for all components and the hook after they are implemented.

11. **SearchSimulation** (T022-T025). Build the search simulation component, typing animation, AbortController cancellation, and wire it into the tour lifecycle. This can begin once TourProvider exists (T009).

12. **SearchSimulation test** (T026). Verify timing, cancellation, and cleanup behavior.

13. **Polish and verification last** (T027-T034). Seed data, API contract verification, end-to-end walkthrough, edge cases, and test suite confirmation.

### Key Risk Mitigations

- **Target element not found**: TourOverlay should handle `document.querySelector()` returning `null` gracefully by either skipping the step or showing a centered tooltip without a spotlight.
- **Search simulation timing**: The 50ms/char + 1500ms pause timing totals ~6.2s, well under the 10s SC-004 budget. Use `AbortController` to guarantee immediate cancellation with no dangling timeouts.
- **Responsive resize during tour**: The `matchMedia` listener (T015) is the safety net. CSS media queries handle button visibility, but JavaScript handles active tour cleanup.
- **react-markdown already in project**: No new dependency needed for markdown rendering in tooltips. Verify the import path works in the frontend build.

### Estimated Task Count by Phase

| Phase | Tasks | Estimated Effort |
|-------|-------|-----------------|
| Phase 2: Foundational | 8 tasks (T001-T008) | Backend data layer + frontend types/API |
| Phase 3: US1 | 13 tasks (T009-T021) | Core tour UI and tests |
| Phase 4: US2 | 5 tasks (T022-T026) | Search simulation and test |
| Phase 5: Polish | 8 tasks (T027-T034) | Verification and edge cases |
| **Total** | **34 tasks** | |
