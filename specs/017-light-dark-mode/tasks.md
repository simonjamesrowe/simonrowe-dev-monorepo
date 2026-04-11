# Tasks: Light & Dark Mode Theme Support

**Input**: Design documents from `/specs/017-light-dark-mode/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not explicitly requested in spec. Visual verification via Playwright screenshots is the primary validation method.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the ThemeContext and blocking script that all user stories depend on

- [x] T001 Create ThemeContext provider with theme state, toggle function, and OS preference detection in `frontend/src/contexts/ThemeContext.tsx`
- [x] T002 Add blocking inline script to `frontend/index.html` `<head>` that reads localStorage key `theme-preference` and sets `data-theme` attribute on `<html>` before first paint
- [x] T003 Wrap application in ThemeProvider in `frontend/src/App.tsx`

**Checkpoint**: Theme infrastructure is in place. `data-theme` attribute toggles on `<html>`. No visual changes yet (light CSS not defined).

---

## Phase 2: Foundational (Light Theme CSS Variables)

**Purpose**: Define the light theme color palette as CSS variable overrides. This MUST be complete before any visual user story work.

**⚠️ CRITICAL**: No user story visual work can begin until this phase is complete

- [x] T004 Add `[data-theme="light"]` CSS variable overrides block in `frontend/src/styles.css` with light palette values for all `:root` surface, text, primary, secondary, outline, error, shadow, and glow variables (based on `designs/landing-light.html` palette)
- [x] T005 Add CSS `color-scheme` property to `:root` (dark) and `[data-theme="light"]` (light) for native browser UI integration (scrollbars, form controls) in `frontend/src/styles.css`
- [x] T006 Add smooth CSS transition on `background-color` and `color` to `body` selector in `frontend/src/styles.css` for theme toggle animation

**Checkpoint**: Toggling `data-theme="light"` on `<html>` now visibly switches all CSS-variable-based colors. Components using hardcoded colors will not yet match.

---

## Phase 3: User Story 1 — System Preference Detection (Priority: P1) 🎯 MVP

**Goal**: First-time visitors see a theme matching their OS preference with no flash of wrong theme.

**Independent Test**: Change OS appearance to light, load site fresh (clear localStorage) — site renders in light theme immediately. Change OS to dark — site renders in dark theme.

### Implementation for User Story 1

- [x] T007 [US1] Ensure ThemeContext reads `prefers-color-scheme` media query on mount and listens for changes via `matchMedia` in `frontend/src/contexts/ThemeContext.tsx`
- [x] T008 [US1] Ensure blocking script in `frontend/index.html` detects `prefers-color-scheme: light` when no localStorage value exists and sets `data-theme="light"` accordingly
- [x] T009 [US1] Verify dark theme is the default when `prefers-color-scheme` is not supported (no-op — existing `:root` is dark)

**Checkpoint**: OS preference detection works. Site matches system theme on first visit with no flash.

---

## Phase 4: User Story 2 — Manual Theme Toggle (Priority: P1)

**Goal**: A visible Sun/Moon toggle in the navigation switches themes instantly on all pages.

**Independent Test**: Click the toggle button in TopNav — entire page switches theme smoothly. Toggle is visible and accessible on Home, Experience, and Blog pages.

### Implementation for User Story 2

- [x] T010 [US2] Add Sun/Moon icon toggle button to `frontend/src/components/layout/TopNav.tsx` that consumes ThemeContext and calls `toggleTheme()`. Show Sun icon when in dark mode (click to go light), Moon icon when in light mode (click to go dark).
- [x] T011 [US2] Style the theme toggle button in `frontend/src/styles.css` — add `.nav__theme-toggle` styles matching existing nav button patterns (size, border-radius, hover states)
- [x] T012 [US2] Add theme toggle to mobile navigation in `frontend/src/components/layout/MobileMenu.tsx` if mobile menu exists

**Checkpoint**: Toggle button works on all pages. Theme switches instantly with smooth transition.

---

## Phase 5: User Story 3 — Theme Preference Persistence (Priority: P2)

**Goal**: Manual theme choice persists across navigations and browser restarts via localStorage.

**Independent Test**: Select light mode, navigate to Experience page — stays light. Close browser, reopen site — loads in light mode. Clear localStorage — reverts to OS preference.

### Implementation for User Story 3

- [x] T013 [US3] Ensure ThemeContext writes to `localStorage.setItem('theme-preference', theme)` on every manual toggle in `frontend/src/contexts/ThemeContext.tsx`
- [x] T014 [US3] Ensure ThemeContext reads `localStorage.getItem('theme-preference')` on mount and prioritises it over OS preference in `frontend/src/contexts/ThemeContext.tsx`
- [x] T015 [US3] Ensure blocking script in `frontend/index.html` reads localStorage first, falls back to `prefers-color-scheme`, then defaults to dark
- [x] T016 [US3] Handle corrupted/invalid localStorage values — if value is not `"light"` or `"dark"`, remove it and fall back to OS preference in `frontend/src/contexts/ThemeContext.tsx`

**Checkpoint**: Preference persists across navigations and browser restarts. Stored preference takes priority over OS setting.

---

## Phase 6: User Story 4 — Consistent Theming Across All Content (Priority: P2)

**Goal**: All page sections, drawers, modals, and the hero background image render correctly in both themes.

**Independent Test**: Visit every page (Home, Experience, Blog) in both themes. Open job detail drawer, skill drawer, contact drawer, chat panel, and search in both themes. All text is readable, all components match the active theme.

### Implementation for User Story 4

- [x] T017 [P] [US4] Convert hardcoded `color: white` usages (~30 instances) to `var(--on-surface)` in `frontend/src/styles.css`
- [x] T018 [P] [US4] Convert hardcoded `rgba(255, 255, 255, 0.xx)` border/overlay values to CSS variables using `--border-subtle`, `--border-faint` pattern in `frontend/src/styles.css`
- [x] T019 [P] [US4] Convert hardcoded `rgba(15, 19, 28, ...)` / `rgba(0, 0, 0, ...)` surface-related rgba values to CSS variable-based equivalents in `frontend/src/styles.css`
- [x] T020 [US4] Add theme-aware hero overlay gradients — create `--hero-overlay-start`, `--hero-overlay-mid`, `--hero-overlay-end` CSS variables with dark and light values, and use them in `.hero__bg::after` in `frontend/src/styles.css`
- [x] T021 [US4] Add theme-aware light overrides for navigation glass panel (`.nav`, `.top-nav`) background and backdrop in `frontend/src/styles.css`
- [x] T022 [US4] Add theme-aware light overrides for drawer/modal backgrounds (`.drawer`, `.drawer-overlay`) in `frontend/src/styles.css`
- [x] T023 [US4] Add theme-aware light overrides for chat panel backgrounds and message bubbles in `frontend/src/styles.css`
- [x] T024 [US4] Add theme-aware light overrides for search overlay and results in `frontend/src/styles.css`
- [x] T025 [US4] Add theme-aware light overrides for skeleton loader colors (`#e5e7eb` → variable) in `frontend/src/styles.css`
- [x] T026 [US4] Add theme-aware light overrides for footer background and text in `frontend/src/styles.css`
- [x] T027 [US4] Add theme-aware light overrides for blog content styling (code blocks, blockquotes) in `frontend/src/styles.css`
- [x] T028 [US4] Remove any inline `style={{ color: 'white' }}` from component files — replace with CSS classes using `var(--on-surface)` in affected `.tsx` files under `frontend/src/`
- [x] T029 [US4] Verify contact form error/success state colors work in both themes — add light overrides if needed in `frontend/src/styles.css`

**Checkpoint**: All pages and interactive components render correctly and readably in both light and dark themes. Hero background image is visible and appealing in both modes.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final quality pass and edge case handling

- [x] T0030 Add `transition: background-color var(--transition-normal), color var(--transition-normal), border-color var(--transition-normal)` to key container elements for smooth theme switching in `frontend/src/styles.css`
- [x] T0031 Verify WCAG AA contrast ratios (4.5:1 normal text, 3:1 large text) for both themes using browser devtools or Lighthouse audit
- [x] T0032 Visual review of all pages in light mode using Playwright screenshots: Home, Experience, Blog listing, Blog detail
- [x] T0033 Visual review of all drawers/modals in light mode: Job detail, Skill group, Contact, Chat panel, Search
- [x] T0034 Test edge case: corrupted localStorage value — verify graceful fallback
- [x] T0035 Test edge case: OS preference changes while site is open — verify ThemeContext responds (when no stored preference)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phases 3-6)**: All depend on Foundational phase completion
  - US1 (Phase 3) and US2 (Phase 4) can proceed in parallel
  - US3 (Phase 5) depends on US1 + US2 (needs toggle to generate stored preference)
  - US4 (Phase 6) can start after Foundational but is most effective after US1+US2
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: After Foundational — no dependency on other stories
- **User Story 2 (P1)**: After Foundational — no dependency on other stories (can parallel with US1)
- **User Story 3 (P2)**: After US1 + US2 — needs both detection and toggle to work
- **User Story 4 (P2)**: After Foundational — can start independently, but full validation requires US1+US2

### Within Each User Story

- Core logic before UI integration
- CSS variable definitions before hardcoded color conversions
- Story complete before moving to next priority

### Parallel Opportunities

- T001 and T002 can run in parallel (different files)
- T010 and T011 can run in parallel (different files: .tsx and .css)
- T017, T018, T019 can all run in parallel (different search-and-replace patterns in same file, non-overlapping)
- T021–T027 can all run in parallel (different CSS sections, non-overlapping selectors)
- US1 and US2 can be worked on in parallel after Foundational phase

---

## Parallel Example: User Story 4

```bash
# Launch hardcoded color conversions together (different patterns):
Task: "T017 Convert color: white to var(--on-surface)"
Task: "T018 Convert rgba(255,255,255,...) borders to variables"
Task: "T019 Convert rgba(15,19,28,...) surfaces to variables"

# Launch component-specific overrides together (different CSS sections):
Task: "T021 Nav glass panel light overrides"
Task: "T022 Drawer/modal light overrides"
Task: "T023 Chat panel light overrides"
Task: "T024 Search overlay light overrides"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 1: Setup (ThemeContext + blocking script)
2. Complete Phase 2: Foundational (light CSS variables)
3. Complete Phase 3: US1 — OS preference detection
4. Complete Phase 4: US2 — Toggle button
5. **STOP and VALIDATE**: Toggle works, OS detection works, most elements theme correctly via CSS variables
6. This is a usable MVP — some hardcoded colors won't match in light mode but core theme switching works

### Full Delivery

1. Complete MVP above
2. Add Phase 5: US3 — Persistence (quick, mostly in ThemeContext)
3. Add Phase 6: US4 — Full visual sweep (largest phase, ~13 tasks)
4. Add Phase 7: Polish (contrast audit, edge cases)

---

## Notes

- All CSS changes are in the single `frontend/src/styles.css` file per constitution
- Admin panel styles are explicitly out of scope — do not convert admin CSS sections
- The `designs/landing-light.html` prototype is the reference for light palette values
- Hardcoded color conversion (US4) is the largest phase but tasks are highly parallelizable
- No backend changes required for this feature
