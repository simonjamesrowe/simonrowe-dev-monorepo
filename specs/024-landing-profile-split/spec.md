# Feature Specification: Landing Profile Split

**Feature Branch**: `feat/frontend/landing-chat-widgets`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Use the approved landing/profile split design from docs/superpowers/specs/2026-06-28-landing-profile-split-design.md. Balance the current homepage with the supplied Landing Page.html mockup, keep the background photo, center the landing experience, make the top banner full width, stop the homepage after chat, move profile and contact to a Profile page, and update the guided tour and tour seed data."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Chat-first landing page (Priority: P1)

A visitor lands on the site and immediately sees a polished, centered landing hero with Simon's identity, the existing background photo, and the chat entry as the primary action. The page does not continue into profile, about, or contact content before the footer.

**Why this priority**: The homepage is the first impression and the user has explicitly requested the visual balance, centering, full-width banner, and removal of post-chat homepage sections.

**Independent Test**: Open the homepage and verify that the top banner spans the viewport, the hero content is centered over the existing photo, chat prompt controls are usable, and the footer follows without About or contact sections.

**Acceptance Scenarios**:

1. **Given** a visitor opens the homepage, **When** the page finishes loading, **Then** the hero presents Simon's identity, role, short headline, chat composer, and prompt chips centered in the viewport.
2. **Given** a visitor scrolls past the chat hero, **When** they reach the end of the homepage content, **Then** the next visible site section is the footer.
3. **Given** a visitor uses a desktop or mobile viewport, **When** they view the top navigation, **Then** the top banner spans the full available width while keeping nav content readable and aligned.

---

### User Story 2 - Profile and contact page (Priority: P2)

A visitor who wants more detail can open Profile to read Simon's biography, inspect professional summary content, download the CV, open social links, and contact Simon from the same page.

**Why this priority**: Removing profile/contact content from the homepage requires a clear destination where those existing user needs remain supported.

**Independent Test**: Navigate to `/profile` and verify that biography/profile content, CV/social actions, contact details, and the contact form are available on the same page.

**Acceptance Scenarios**:

1. **Given** a visitor clicks Profile in navigation, **When** the Profile page loads, **Then** profile overview and biography content are visible.
2. **Given** a visitor is on the Profile page, **When** they navigate to the contact section, **Then** contact details and the contact form are available without opening a homepage drawer.
3. **Given** a visitor follows a footer Contact link, **When** the Profile page loads, **Then** the page targets the contact area.

---

### User Story 3 - Updated guided tour (Priority: P3)

A visitor or admin can run the public guided tour and every step targets an element that exists in the redesigned site structure, including the new Profile page and contact section.

**Why this priority**: The site tour depends on DOM targets and seeded data that would otherwise point to removed homepage sections.

**Independent Test**: Start from seeded tour data and step through the tour, confirming each step targets an existing element and no step tries to open the old homepage contact drawer.

**Acceptance Scenarios**:

1. **Given** the public tour starts on the homepage, **When** it reaches the landing step, **Then** it targets the chat-first hero area.
2. **Given** the public tour reaches profile-related steps, **When** it navigates to Profile, **Then** it can target both the profile overview and contact section.
3. **Given** tour seed data is loaded, **When** records are inspected, **Then** seeded defaults use the new selectors and no longer reference removed homepage About or contact drawer behavior.

### Edge Cases

- If profile data is unavailable, the homepage and Profile page must continue to show the existing loading or error behavior instead of rendering broken content.
- If the Profile contact hash is opened directly, the Profile page must still render the contact target so browser scrolling and tour targeting can find it.
- If the guided tour runs with previously seeded data, the seeded defaults must be updateable so removed homepage selectors do not remain the active public tour path.
- If the site is viewed on mobile, centered hero text and chat controls must fit their containers without overlapping navigation, footer, or background image content.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The homepage MUST render a centered chat-first hero that includes Simon's identity, role, short headline, chat composer, prompt chips, and the existing background photo.
- **FR-002**: The homepage MUST NOT render the previous About section, CTA section, or homepage contact drawer after the chat hero.
- **FR-003**: The top navigation/banner MUST span the full viewport width while preserving readable, constrained navigation content.
- **FR-004**: Public desktop and mobile navigation MUST include a Profile item that routes to `/profile` instead of an About item that targets the homepage.
- **FR-005**: The Profile page MUST include profile overview, biography, professional summary content, CV/social actions, contact details, and the contact form on the same page.
- **FR-006**: Footer Profile and Contact links MUST route to the Profile page, with Contact targeting the Profile contact section.
- **FR-007**: The guided tour MUST include targets for the homepage chat hero, site search, Ask AI, Profile overview, Profile contact section, Experience, Blog, and News & Events.
- **FR-008**: Seeded tour data MUST use selectors and routes that exist in the redesigned site and MUST remove references to the old homepage About target and contact drawer flow.
- **FR-009**: Existing chat behavior, prompt interactions, profile loading/error handling, contact submission behavior, and public route behavior MUST remain available.

### Key Entities *(include if feature involves data)*

- **Public Homepage**: The visitor-facing entry page containing the centered hero, background photo, and chat interaction.
- **Profile Page**: The visitor-facing page that owns biography, profile details, CV/social actions, and contact.
- **Tour Step**: A guided tour item with label, target selector, page route, order, and optional action behavior.
- **Profile Content**: Existing profile data used to populate hero identity, biography, social links, CV, and background imagery.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A visitor can identify the primary chat action on the homepage without scrolling.
- **SC-002**: The homepage contains zero visible About, CTA, or contact-drawer sections between the chat hero and footer.
- **SC-003**: A visitor can reach profile and contact content from navigation or footer links in no more than one click.
- **SC-004**: The public tour can complete all seeded steps without a missing selector.
- **SC-005**: The redesigned homepage and Profile page pass automated tests covering route rendering, chat prompt behavior, profile/contact placement, and updated tour selectors.

## Assumptions

- Existing profile, contact, chat, navigation, footer, and tour components remain the base implementation.
- The current background photo is sourced from existing profile data.
- The contact form remains the existing public contact form and keeps its current validation and submission behavior.
- The feature targets the current public website and does not change admin CMS design, chat transport, backup/restore, or authentication.
