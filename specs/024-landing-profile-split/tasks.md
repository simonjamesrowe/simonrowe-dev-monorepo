# Tasks: Landing Profile Split

**Input**: Design documents from `/specs/024-landing-profile-split/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Included because the feature specification requires automated coverage for homepage, Profile route, tour selectors/actions, and tour seed data.

**Organization**: Tasks are grouped by user story to enable independently testable increments.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm implementation context and preserve existing worktree changes.

- [X] T001 Review affected frontend files in `frontend/src/pages/HomePage.tsx`, `frontend/src/components/home/HeroSection.tsx`, `frontend/src/pages/ProfilePage.tsx`, `frontend/src/App.tsx`, `frontend/src/components/layout/TopNav.tsx`, `frontend/src/components/layout/MobileMenu.tsx`, `frontend/src/components/layout/Footer.tsx`, and `frontend/src/components/tour/tourActions.ts`
- [X] T002 Review affected backend tour files in `backend/src/main/java/com/simonrowe/migration/DataMigrationService.java`, `backend/src/main/java/com/simonrowe/admin/TourStep.java`, and `backend/src/main/java/com/simonrowe/admin/AdminTourStepRepository.java`
- [X] T003 Review existing tests in `frontend/tests/pages/HomePage.test.tsx`, `frontend/tests/App.test.tsx`, `frontend/tests/components/tour/`, and `backend/src/test/java/com/simonrowe/tour/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish stable route and selector contracts needed by all stories.

- [X] T004 Add public `/profile` route in `frontend/src/App.tsx`
- [X] T005 Replace public About navigation with Profile links in `frontend/src/components/layout/TopNav.tsx` and `frontend/src/components/layout/MobileMenu.tsx`
- [X] T006 Replace footer About/Contact routing with Profile/Profile contact links in `frontend/src/components/layout/Footer.tsx`

**Checkpoint**: Navigation and route structure supports the split.

---

## Phase 3: User Story 1 - Chat-first landing page (Priority: P1) MVP

**Goal**: Homepage renders a centered chat-first hero over the current background photo and ends before the footer.

**Independent Test**: Open `/` and confirm centered hero/chat content, full-width banner, prompt behavior, and no About/CTA/contact drawer content.

### Tests for User Story 1

- [X] T007 [P] [US1] Update homepage expectations in `frontend/tests/pages/HomePage.test.tsx`
- [X] T008 [P] [US1] Update or add hero prompt behavior expectations in `frontend/src/components/home/HeroSection.test.tsx`

### Implementation for User Story 1

- [X] T009 [US1] Remove homepage About/CTA/contact drawer composition from `frontend/src/pages/HomePage.tsx`
- [X] T010 [US1] Remove homepage CV/social actions and add `.tour-home-chat` in `frontend/src/components/home/HeroSection.tsx`
- [X] T011 [US1] Update centered hero and full-width top banner styles in `frontend/src/styles.css`

**Checkpoint**: User Story 1 is functional and testable independently.

---

## Phase 4: User Story 2 - Profile and contact page (Priority: P2)

**Goal**: `/profile` owns profile overview, biography, CV/social actions, contact details, and the contact form.

**Independent Test**: Navigate to `/profile` and `/profile#contact`; verify profile and contact content appear on one page.

### Tests for User Story 2

- [X] T012 [P] [US2] Add Profile route coverage in `frontend/tests/App.test.tsx`
- [X] T013 [P] [US2] Add Profile page contact/selector coverage in `frontend/tests/pages/ProfilePage.test.tsx`

### Implementation for User Story 2

- [X] T014 [US2] Add `.tour-profile`, `id="contact"`, `.tour-contact`, CV, and social affordances to `frontend/src/pages/ProfilePage.tsx` and `frontend/src/components/contact/ContactSection.tsx`
- [X] T015 [US2] Update Profile page styles in `frontend/src/styles.css`

**Checkpoint**: User Story 2 is functional and testable independently.

---

## Phase 5: User Story 3 - Updated guided tour (Priority: P3)

**Goal**: Public tour selectors and seeded data match the redesigned route structure.

**Independent Test**: Seed or inspect tour data and step through the tour without missing targets or old contact drawer actions.

### Tests for User Story 3

- [X] T016 [P] [US3] Update tour action expectations in `frontend/tests/components/tour/`
- [X] T017 [P] [US3] Add backend tour seed coverage in `backend/src/test/java/com/simonrowe/tour/`

### Implementation for User Story 3

- [X] T018 [US3] Remove `.tour-contact` drawer click and cleanup behavior from `frontend/src/components/tour/tourActions.ts`
- [X] T019 [US3] Add deterministic default tour seed data in `backend/src/main/java/com/simonrowe/migration/DataMigrationService.java`
- [X] T020 [US3] Add repository lookup support for deterministic tour seeding in `backend/src/main/java/com/simonrowe/admin/AdminTourStepRepository.java`

**Checkpoint**: User Story 3 is functional and testable independently.

---

## Final Phase: Polish & Cross-Cutting Concerns

**Purpose**: Validate implementation against the spec and local app behavior.

- [X] T021 Run frontend targeted tests with `cd frontend && npm test -- HomePage App ProfilePage Tour`
- [X] T022 Run backend targeted tour tests with `./gradlew :backend:test --tests '*Tour*'`
- [X] T023 Run browser verification for `/` and `/profile` on the local frontend
- [X] T024 Update task checklist statuses in `specs/024-landing-profile-split/tasks.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup completion and blocks user stories.
- **User Story 1 (P1)**: Depends on Foundational.
- **User Story 2 (P2)**: Depends on Foundational.
- **User Story 3 (P3)**: Depends on Foundational and the final selector locations from US1/US2.
- **Polish**: Depends on all selected user stories.

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational.
- **User Story 2 (P2)**: Can start after Foundational.
- **User Story 3 (P3)**: Starts after selector-bearing elements exist in US1 and US2.

### Parallel Opportunities

- T007 and T008 can run in parallel.
- T012 and T013 can run in parallel.
- T016 and T017 can run in parallel.

## Implementation Strategy

1. Complete setup and foundational navigation/route changes.
2. Deliver the homepage MVP.
3. Deliver the Profile/contact page.
4. Retarget tour actions and backend seed data.
5. Run targeted frontend/backend tests and browser verification.
