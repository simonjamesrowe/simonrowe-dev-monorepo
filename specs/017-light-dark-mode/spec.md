# Feature Specification: Light & Dark Mode Theme Support

**Feature Branch**: `017-light-dark-mode`
**Created**: 2026-04-10
**Status**: Draft
**Input**: User description: "I'd like both a light mode and a dark mode for this website"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - System Preference Detection (Priority: P1)

A visitor arrives at the website for the first time. The site automatically detects their operating system's color scheme preference (light or dark) and renders accordingly. Users on macOS/Windows/iOS/Android with dark mode enabled see the dark theme; users with light mode enabled see the light theme.

**Why this priority**: First-time visitors should immediately see a theme that matches their system, creating a polished and accessible first impression without requiring any action.

**Independent Test**: Can be tested by changing OS appearance settings and loading the site fresh (no stored preference) — the site should match the OS theme.

**Acceptance Scenarios**:

1. **Given** a first-time visitor with OS set to dark mode, **When** they load any page, **Then** the site renders in dark theme with no flash of incorrect theme.
2. **Given** a first-time visitor with OS set to light mode, **When** they load any page, **Then** the site renders in light theme with no flash of incorrect theme.
3. **Given** a visitor with no detectable system preference, **When** they load the site, **Then** the site defaults to dark theme.

---

### User Story 2 - Manual Theme Toggle (Priority: P1)

A visitor wants to override their system preference and switch between light and dark mode manually. A toggle control is visible in the site navigation. Clicking it immediately switches the entire page to the opposite theme.

**Why this priority**: Equal priority to system detection — users must be able to override automatic detection. This is the core interaction for the feature.

**Independent Test**: Can be tested by clicking the theme toggle and verifying all page sections update to the selected theme.

**Acceptance Scenarios**:

1. **Given** the site is in dark mode, **When** the user clicks the theme toggle, **Then** the entire page transitions to light mode smoothly.
2. **Given** the site is in light mode, **When** the user clicks the theme toggle, **Then** the entire page transitions to dark mode smoothly.
3. **Given** the user is on any page (Home, Experience, Blog), **When** they toggle the theme, **Then** the toggle is accessible from the same location on every page.

---

### User Story 3 - Theme Preference Persistence (Priority: P2)

A visitor manually selects a theme, navigates to other pages, and later returns to the site. Their chosen theme is remembered and applied automatically on all subsequent visits, overriding the system preference.

**Why this priority**: Persistence ensures the manual toggle is useful beyond a single page view, but the feature works without it (just reverts to system default each visit).

**Independent Test**: Can be tested by selecting a theme, closing the browser, reopening the site, and verifying the previously selected theme is applied.

**Acceptance Scenarios**:

1. **Given** the user has manually selected light mode, **When** they navigate to a different page on the site, **Then** light mode persists across the navigation.
2. **Given** the user has manually selected dark mode, **When** they close and reopen the site, **Then** dark mode is applied on load without flash.
3. **Given** the user has a stored preference, **When** they change their OS preference, **Then** the stored manual preference takes priority until they clear it or toggle again.

---

### User Story 4 - Consistent Theming Across All Content (Priority: P2)

All sections of the site — hero with background image, about section, career timeline, blog posts, skills grid, navigation, footer, drawers, and modals — render correctly and readably in both themes.

**Why this priority**: Ensures visual quality and readability across all content. The background image in the hero section must work in both modes (with appropriate overlay adjustments).

**Independent Test**: Can be tested by visiting every page and section in both themes and checking for readability, contrast, and visual consistency.

**Acceptance Scenarios**:

1. **Given** the site is in light mode, **When** viewing the hero section with background image, **Then** the text is readable with an appropriate light overlay on the image.
2. **Given** the site is in dark mode, **When** viewing the hero section with background image, **Then** the text is readable with an appropriate dark overlay on the image.
3. **Given** any theme, **When** viewing the career timeline, blog content, or skill cards, **Then** all text meets minimum contrast ratios for readability.
4. **Given** any theme, **When** opening a drawer or modal (job detail, skill detail, contact), **Then** the drawer/modal matches the active theme.

---

### Edge Cases

- What happens when a user's browser does not support the `prefers-color-scheme` media query? Default to dark theme.
- What happens during theme transition — is there a noticeable layout shift? Transitions should be smooth with no layout shift.
- What happens if the user has stored a preference but the stored value is corrupted or unrecognised? Fall back to system preference, then dark default.
- What happens on the admin pages? Admin pages are out of scope for this feature; they remain as-is.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Site MUST detect the user's operating system color scheme preference on first visit and render the matching theme.
- **FR-002**: Site MUST provide a visible theme toggle control in the main navigation bar, accessible on all pages.
- **FR-003**: Clicking the theme toggle MUST immediately switch all visible content between light and dark themes.
- **FR-004**: Site MUST persist the user's manual theme choice in the browser so it survives page navigations and return visits.
- **FR-005**: Stored manual preference MUST take priority over OS-level color scheme detection.
- **FR-006**: Both themes MUST maintain readable text contrast on all page sections, including over the hero background image.
- **FR-007**: Theme transitions MUST be smooth (no abrupt flash of unstyled or wrong-themed content on page load or toggle).
- **FR-008**: The theme toggle MUST clearly indicate the current active theme (e.g., a sun icon for light mode, moon icon for dark mode).
- **FR-009**: All interactive components (drawers, modals, chat panel, search) MUST render correctly in both themes.
- **FR-010**: The hero section background image MUST be preserved in both themes, with overlay adjustments appropriate for each theme.

### Key Entities

- **Theme Preference**: The user's selected theme (light, dark, or system-default). Stored per-browser. Contains: selected theme value, timestamp of selection.
- **Theme Configuration**: The set of visual properties (colors, shadows, overlays) that define each theme. Two configurations exist: light and dark.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All pages render in the correct theme within 100ms of page load with no visible flash of the wrong theme.
- **SC-002**: Theme toggle switches the visible theme in under 300ms with a smooth transition.
- **SC-003**: All text content in both themes meets WCAG AA contrast ratio (4.5:1 for normal text, 3:1 for large text).
- **SC-004**: Theme preference persists correctly across page navigations and browser restarts.
- **SC-005**: Both themes render correctly across all site pages: Home, Experience, Blog listing, Blog detail, and all drawer/modal overlays.
- **SC-006**: The hero background image remains visible and visually appealing in both light and dark modes.

## Assumptions

- The existing dark theme color palette and design language are the baseline and should be preserved as-is for dark mode.
- Light mode will use a complementary color palette (as explored in the `designs/landing-light.html` prototype).
- Admin/CMS pages are out of scope — they will remain dark-only.
- Browser local storage is an acceptable persistence mechanism (no server-side preference storage needed).
- The feature applies to the public-facing frontend only.
