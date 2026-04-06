# Feature Specification: Landing Page AI Redesign

**Feature Branch**: `015-landing-ai-redesign`
**Created**: 2026-04-06
**Status**: Draft
**Input**: Redesign the landing page hero section to make AI chat more prominent (single-column centered layout replacing two-column), and add a prominent "Ask AI" button to the top navigation bar accessible from any page. Must work on mobile devices.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Centered Hero with Prominent AI Chat (Priority: P1)

A visitor arrives at the landing page and immediately sees a bold, centered hero section with a large headline, subtitle text, and a prominent AI chat input bar below the headline. The layout is single-column and vertically stacked rather than the current two-column side-by-side arrangement. Suggested prompt chips appear below the chat input to encourage interaction. The visitor types a question or clicks a suggested prompt to begin chatting with the AI assistant.

**Why this priority**: The landing page is the first impression. Making the AI chat the focal point of the hero drives engagement with the site's key differentiator and aligns with the redesign goal of making AI more prominent.

**Independent Test**: Can be fully tested by loading the homepage and verifying the new single-column centered layout renders correctly with the chat input bar and suggested prompts functional.

**Acceptance Scenarios**:

1. **Given** a visitor loads the homepage, **When** the page renders, **Then** the hero section displays a centered headline, subtitle, and a prominent chat input bar below, all vertically stacked in a single column.
2. **Given** a visitor sees the hero section, **When** they click a suggested prompt chip, **Then** the reCAPTCHA verification flow initiates and, upon success, the chat panel opens with the selected prompt pre-filled.
3. **Given** a visitor sees the hero chat input, **When** they type a custom question and submit, **Then** the reCAPTCHA verification flow initiates and, upon success, the chat panel opens with their query pre-filled.
4. **Given** a visitor views the hero on a mobile device, **When** the page renders, **Then** the layout remains single-column centered, the chat input is full-width, and suggested prompts stack or wrap gracefully.

---

### User Story 2 - "Ask AI" Button in Top Navigation (Priority: P1)

A visitor browsing any page on the site (Experience, Blog, Blog Detail, or Homepage) sees a clearly visible "Ask AI" button in the top navigation bar alongside the existing search. The button is visually distinct (pill-shaped outline with a chat icon) and positioned prominently. Clicking it opens the AI chat panel from any page, after reCAPTCHA verification if not already verified.

**Why this priority**: Enabling AI chat access from any page removes friction and makes the AI assistant a global feature rather than homepage-only. This is a core part of the redesign goal.

**Independent Test**: Can be fully tested by navigating to any page, clicking the "Ask AI" button in the top nav, verifying the reCAPTCHA gate appears (if not yet verified), and confirming the chat panel opens.

**Acceptance Scenarios**:

1. **Given** a visitor is on any page, **When** they look at the top navigation bar, **Then** they see an "Ask AI" button with a chat icon, visually distinct from other nav elements.
2. **Given** a visitor clicks the "Ask AI" button, **When** they have not yet completed reCAPTCHA verification, **Then** the reCAPTCHA gate modal appears.
3. **Given** a visitor clicks the "Ask AI" button, **When** they have already completed reCAPTCHA verification in this session, **Then** the chat panel opens immediately without re-verification.
4. **Given** a visitor is on a mobile device, **When** they view the top navigation, **Then** the "Ask AI" button remains visible and accessible (not hidden behind a hamburger menu).
5. **Given** a visitor is on a mobile device with a narrow viewport, **When** they view the top navigation, **Then** the "Ask AI" button adapts appropriately (e.g., icon-only on very small screens, or compact pill).

---

### User Story 3 - Preserved Existing Content Below Hero (Priority: P2)

The existing About section and Call-to-Action section below the hero continue to display correctly with the same content and functionality. The contact form drawer, social links, and CV download remain accessible from the homepage.

**Why this priority**: Ensures no existing functionality is lost during the redesign. The hero is changing layout but the rest of the page content must remain intact.

**Independent Test**: Can be fully tested by scrolling below the hero section and verifying About, CTA sections render with correct content, and interactive elements (contact form, CV download, social links) function as before.

**Acceptance Scenarios**:

1. **Given** a visitor scrolls past the hero, **When** the About section is visible, **Then** it displays the profile image, description, and "Get In Touch" button as before.
2. **Given** a visitor scrolls further, **When** the CTA section is visible, **Then** it displays the call-to-action heading with "Get In Touch" and "Explore Work" buttons.
3. **Given** a visitor clicks the CV download link, **When** the hero includes social/CV links, **Then** the CV downloads and social links navigate correctly.

---

### Edge Cases

- What happens when the AI chat WebSocket connection fails while a user initiates chat from the top nav on a non-homepage page? The chat panel should display an appropriate connection error message.
- What happens on extremely narrow viewports (< 320px)? The hero text and chat input should remain readable and not overflow.
- What happens when a visitor uses keyboard navigation to reach the "Ask AI" button? It should be focusable and activatable via Enter/Space.
- What happens if reCAPTCHA script fails to load? The existing RecaptchaGate error handling should apply consistently whether initiated from hero or top nav.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The homepage hero section MUST use a single-column, centered layout with the headline, subtitle, and AI chat input stacked vertically.
- **FR-002**: The hero section MUST display a large, bold headline (using existing profile name/title data) centered on the page.
- **FR-003**: The hero section MUST display a subtitle/tagline below the headline, also centered.
- **FR-004**: The hero section MUST include a prominent chat input bar below the subtitle, allowing visitors to type a question.
- **FR-005**: The hero section MUST display suggested prompt chips below the chat input (using existing suggested prompts).
- **FR-006**: The top navigation bar MUST include an "Ask AI" button with a chat/message icon, positioned prominently (before the search bar).
- **FR-007**: The "Ask AI" button MUST be visible on all public pages (Homepage, Experience, Blog listing, Blog detail).
- **FR-008**: Clicking the "Ask AI" button or submitting a hero chat query MUST trigger the existing reCAPTCHA verification flow before opening the chat panel.
- **FR-009**: The chat panel opened from the top nav MUST provide the same full chat experience as when opened from the homepage (streaming, message history, message limit).
- **FR-010**: The hero section MUST retain access to CV download and social media links (repositioned within the centered layout).
- **FR-011**: The redesigned hero and "Ask AI" button MUST be fully responsive and functional on mobile devices (viewport widths from 320px to 768px).
- **FR-012**: The "Ask AI" button MUST remain visible on mobile without being hidden behind a hamburger/overflow menu.

### Key Entities

- **Hero Section**: Centered single-column layout containing headline, subtitle, chat input, suggested prompts, and social/CV links.
- **Ask AI Button**: Persistent navigation element with icon and label, triggering chat panel with reCAPTCHA gating.
- **Chat Panel**: Existing modal chat interface, now accessible from any page via the top nav button.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors see the AI chat input as the primary call-to-action within 2 seconds of landing on the homepage (above the fold on standard viewports).
- **SC-002**: Visitors can initiate an AI chat conversation from any page on the site within 2 clicks (one click on "Ask AI", one reCAPTCHA verification).
- **SC-003**: The landing page hero renders correctly on viewports from 320px to 2560px wide without horizontal overflow or broken layout.
- **SC-004**: All existing homepage functionality (About section, CTA section, contact form, CV download, social links) remains fully functional after the redesign.
- **SC-005**: The "Ask AI" button is visible without scrolling on all pages, on both desktop and mobile devices.
- **SC-006**: 100% of existing frontend tests continue to pass after the redesign (with test updates to reflect new structure).

## Assumptions

- The existing reCAPTCHA verification flow, chat panel, and WebSocket chat service are reused without modification. Only the entry points and hero layout change.
- The "Ask AI" button label and icon style follow the reference image guideline (pill-shaped outline button with a chat icon and "ASK AI" text).
- Social media links and CV download are relocated within the centered hero (e.g., below suggested prompts or in a secondary row) rather than removed.
- The existing background image/gradient treatment in the hero is preserved or adapted to the new centered layout.
- Session-level reCAPTCHA state is shared between the hero chat and the top nav "Ask AI" button (verifying once unlocks both).
