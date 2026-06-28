# Tasks: Landing / Profile Cleanup

**Input**: Design documents from `/specs/025-landing-profile-cleanup/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included — the constitution requires frontend tests for critical user
journeys, and the spec's acceptance scenarios are verified via Vitest.

**Organization**: Tasks are grouped by user story (US1–US4) for independent
implementation and testing. All work is in `frontend/`; no backend code changes.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1, US2, US3, US4

## Path Conventions

- Web app: frontend code in `frontend/src/`, tests in `frontend/src/**` and
  `frontend/tests/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish a clean baseline before editing.

- [X] T001 Capture baseline `git diff origin/main --stat` for the affected frontend files and confirm the local stack builds (`cd frontend && npm run build`).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Identify the `main`-branch reference implementations reused by the stories.

- [X] T002 [P] Extract `main`'s clean `frontend/src/components/home/AboutSection.tsx` and `frontend/src/components/home/AboutSection.test.tsx` for reference (`git show origin/main:...`), and note the `.about-section` CSS blocks in `main`'s `frontend/src/styles.css` to restore.

**Checkpoint**: References identified — user stories can proceed.

---

## Phase 3: User Story 1 - Focused mobile landing (Priority: P1) 🎯 MVP

**Goal**: On phones, the landing hero leads with the chat — badge, tagline, and
prompt chips hidden; name, role, chat intro, input, and background photo remain.
Desktop unchanged.

**Independent Test**: At a 390-wide viewport the hero shows name/role/intro/input
with no badge/tagline/chips and no horizontal overflow; at desktop width the full
hero renders.

- [X] T003 [US1] In `frontend/src/components/home/HeroSection.tsx`, use `useMediaQuery('(max-width: 768px)')` to conditionally omit the badge, tagline, and prompt chips on phones while keeping name, role, chat intro, and input (see research.md Decision 1 — JS conditional chosen over CSS for testability).
- [X] T004 [US1] Update `frontend/src/components/home/HeroSection.test.tsx` (and/or `frontend/tests/pages/HomePage.test.tsx`) to assert the badge/tagline/prompt-chips are hidden at mobile width and present at desktop width.

**Checkpoint**: Mobile landing is trimmed; desktop hero intact.

---

## Phase 4: User Story 2 - Genuine profile and contact page (Priority: P1)

**Goal**: `/profile` shows real About content (photo + bio) plus CV download,
social links, and contact form — no fabricated "Architect of Precision Systems"
copy or fake stats.

**Independent Test**: `/profile` renders real bio/photo, CV link, social links,
and contact form; the fabricated headline and stats are absent.

- [X] T005 [US2] Restore the clean `AboutSection` from `main` into `frontend/src/components/home/AboutSection.tsx` (photo + "About {firstName}" + `description` markdown + "Get In Touch" button).
- [X] T006 [US2] Rework `frontend/src/pages/ProfilePage.tsx` to render `AboutSection` (wrapped in `.tour-profile`, with `onContact` scrolling to `#contact`) followed by the Connect layout (Download CV button + `SocialLinks` + `ContactSection`); remove the `BioSection` import/usage.
- [X] T007 [US2] Delete `frontend/src/components/profile/BioSection.tsx` (and any `BioSection` test) now that it is unused.
- [X] T008 [US2] In `frontend/src/styles.css`, restore the `.about-section` / `.profile-page` CSS blocks needed for the reused component and remove the now-dead `.bio-section` styles, leaving all hero/landing CSS untouched.
- [X] T009 [US2] Restore/align `frontend/src/components/home/AboutSection.test.tsx` to the restored component shape.
- [X] T010 [US2] Update `frontend/tests/pages/ProfilePage.test.tsx` (create if needed) to assert: real About content renders, the "Architect of Precision Systems" text and fake stats are absent, and CV download + social links + contact form are present.

**Checkpoint**: Profile shows genuine content with CV, socials, and contact form.

---

## Phase 5: User Story 3 - Tour still resolves profile and contact steps (Priority: P2)

**Goal**: Guided tour profile/contact steps still highlight the correct areas.

**Independent Test**: Tour reaches profile and contact steps and spotlights them;
backend tour-seed test passes unchanged.

- [X] T011 [US3] Confirm `frontend/src/pages/ProfilePage.tsx` keeps `.tour-profile` around the About block and that the contact section carries `.tour-contact` with `id="contact"` (add the wrapper if the rework dropped it).
- [X] T012 [US3] Run `./gradlew :backend:test --tests '*TourSeedDefaults*'` to confirm the seeded `.tour-profile` / `.tour-contact` selectors are unchanged (no backend edits expected).

**Checkpoint**: Tour selectors intact; tour unaffected.

---

## Phase 6: User Story 4 - No footer anywhere (Priority: P3)

**Goal**: No footer renders on any public page.

**Independent Test**: Home, profile, experience, blogs, news show no footer.

- [X] T013 [US4] Remove the `<Footer>` render and `showFooter` logic from `frontend/src/App.tsx` for all routes, and remove the now-unused `Footer` import.
- [X] T014 [US4] Delete `frontend/src/components/layout/Footer.tsx` (and any Footer test) once nothing references it.
- [X] T015 [US4] Update `frontend/tests/App.test.tsx` (and any home/profile test) to assert the footer is absent on every page instead of present on non-home pages.

**Checkpoint**: Footer gone everywhere.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T016 Run `cd frontend && npm test` (full suite) and fix any fallout from the component/test changes.
- [X] T017 Run `cd frontend && npm run build` and confirm it passes.
- [X] T018 Browser-verify per `quickstart.md`: mobile landing (no badge/tagline/chips, no overflow), desktop landing (full hero), `/profile` (real content + CV + socials + form, no fabricated copy), no footer on any page, and tour profile/contact steps.

---

## Dependencies & Execution Order

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** before story work.
- **US1, US2, US4 are independent** of each other (different files, except both
  US1 and US2/US4 touch `styles.css`/tests — sequence the `styles.css` edits to
  avoid conflicts).
- **US3** depends on US2 (it validates selectors on the reworked ProfilePage).
- **Phase 7 (Polish)** runs after all story phases.

## Parallel Opportunities

- T002 can run alongside T001.
- Within US2, T005 and T009 (component + its test) can be paired; T010 can be
  written in parallel once T006 lands the new ProfilePage structure.
- US1 (T003–T004) and US4 (T013–T015) can be implemented in parallel by
  different workers, coordinating the shared `styles.css` and test files.

## Implementation Strategy

- **MVP** = US1 + US2 (both P1): the visible landing and profile fixes.
- Deliver US3 (tour safety) and US4 (footer) next, then Polish.

## Format Validation

All tasks use `- [ ] [TaskID] [P?] [Story?]` with explicit file paths; setup,
foundational, and polish tasks carry no story label; US tasks carry US1–US4.
