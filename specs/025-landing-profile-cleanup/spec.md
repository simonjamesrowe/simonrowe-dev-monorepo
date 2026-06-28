# Feature Specification: Landing / Profile Cleanup

**Feature Branch**: `025-landing-profile-cleanup`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Landing/profile cleanup: lighten mobile landing hero, restore real About content and contact form on /profile, remove footer everywhere, keep tour working. Source design: docs/superpowers/specs/2026-06-28-landing-profile-cleanup-design.md"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Focused mobile landing (Priority: P1)

A visitor opens the site on a phone. The landing page should immediately present
Simon's identity and invite them into the AI chat, without a wall of supporting
copy crowding the small screen.

**Why this priority**: The landing page exists to drive people into the AI chat.
On mobile the current copy (badge, tagline, four prompt chips) buries the chat
and overwhelms the viewport. This is the most visible problem and the primary
reason for the change.

**Independent Test**: Load the home page at a phone-sized viewport and confirm
the screen leads with name, role, a short chat-intro line, and the chat input —
with the badge, tagline, and prompt chips absent — over the existing background
photo, and with no horizontal overflow.

**Acceptance Scenarios**:

1. **Given** a phone-sized viewport, **When** the home page loads, **Then** the
   visitor sees the name, role, a short chat-intro line, and the chat input, and
   does not see the badge, tagline, or suggested-prompt chips.
2. **Given** a desktop-sized viewport, **When** the home page loads, **Then** the
   full hero (badge, name, role, tagline, chat intro, input, and prompt chips)
   is shown unchanged.
3. **Given** any viewport, **When** the home page loads, **Then** the background
   photo is present and the page does not scroll horizontally.

---

### User Story 2 - Genuine profile and contact page (Priority: P1)

A visitor navigates to the profile page to learn about Simon and get in touch.
They should see Simon's real biography and photo, be able to download his CV,
reach his social profiles, and send a message — with no invented marketing copy.

**Why this priority**: The profile page currently shows fabricated copy ("The
Architect of Precision Systems") and fake statistics that misrepresent Simon.
Replacing this with real content is essential for credibility and is the second
core goal of the change.

**Independent Test**: Open the profile page and confirm it shows the real bio
text and photo, a working CV download, social links, and a contact form — and
that the fabricated headline and statistics are gone.

**Acceptance Scenarios**:

1. **Given** the profile page, **When** it loads, **Then** the visitor sees
   Simon's real biography text and photo.
2. **Given** the profile page, **When** it loads, **Then** the fabricated
   headline "The Architect of Precision Systems" and the invented statistics are
   not present anywhere.
3. **Given** the profile page, **When** the visitor wants Simon's CV, **Then** a
   CV download action is available.
4. **Given** the profile page, **When** the visitor wants to connect, **Then**
   social links and a contact form are available, and submitting the form sends
   the message.

---

### User Story 3 - Tour still resolves profile and contact steps (Priority: P2)

A visitor takes the guided site tour. The steps that point at the profile and
contact areas must still highlight the correct on-page elements.

**Why this priority**: The tour is an existing feature that must not regress, but
it is secondary to fixing the visible landing and profile content.

**Independent Test**: Start the tour and step through to the profile and contact
steps, confirming each highlights the intended area without errors.

**Acceptance Scenarios**:

1. **Given** the guided tour, **When** it reaches the profile step, **Then** the
   profile area is highlighted.
2. **Given** the guided tour, **When** it reaches the contact step, **Then** the
   contact area is highlighted.

---

### User Story 4 - No footer anywhere (Priority: P3)

A visitor browsing any page should not see a site footer.

**Why this priority**: A small, explicit cleanup request; lowest risk and impact.

**Independent Test**: Visit each public page and confirm no footer is rendered.

**Acceptance Scenarios**:

1. **Given** any public page (home, profile, experience, blogs, news), **When**
   it loads, **Then** no footer is shown.

---

### Edge Cases

- A profile with no CV configured still presents a usable CV action (falls back
  to the default resume endpoint) rather than a broken link.
- A profile with duplicate social link types shows each network only once.
- At the mobile/desktop breakpoint boundary, the hero switches cleanly between
  the trimmed and full layouts without leaving orphaned elements.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: On phone-sized viewports, the landing hero MUST omit the badge,
  tagline, and suggested-prompt chips while retaining the name, role, chat-intro
  line, and chat input.
- **FR-002**: On desktop-sized viewports, the landing hero MUST remain unchanged
  (badge, name, role, tagline, chat intro, chat input, and prompt chips all
  present).
- **FR-003**: The landing page MUST display the existing background photo on all
  viewports and MUST NOT introduce horizontal scrolling.
- **FR-004**: The profile page MUST display Simon's real biography text and photo.
- **FR-005**: The profile page MUST NOT contain the fabricated headline "The
  Architect of Precision Systems" or the invented statistics.
- **FR-006**: The profile page MUST provide a CV download action, a set of social
  links, and a contact form that sends a message.
- **FR-007**: The guided tour MUST continue to highlight the profile and contact
  areas on the profile page.
- **FR-008**: No footer MUST be rendered on any public page.

### Key Entities *(include if feature involves data)*

- **Profile**: The site owner's public information — name, role, biography text,
  photo, CV reference, and social links — sourced from existing profile data.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a phone-sized viewport, the landing page shows the chat input
  without the badge, tagline, or prompt chips, and produces zero horizontal
  overflow.
- **SC-002**: The profile page contains zero occurrences of the fabricated copy
  ("Architect of Precision Systems" and the invented statistics).
- **SC-003**: From the profile page a visitor can reach the CV, social profiles,
  and submit the contact form — all four actions succeed.
- **SC-004**: The guided tour completes its profile and contact steps with the
  correct areas highlighted and no errors.
- **SC-005**: No footer appears on any public page.

## Assumptions

- "Exactly as main" for profile/contact refers to the real, data-driven About
  and contact components from the `main` branch, not the unused, fabricated
  profile component; the visitor wants real content presented in that style.
- CV download and social links are retained on the profile page per explicit
  request, even though `main`'s unused profile component lacked the CV action.
- The desktop landing hero is intentionally out of scope and left unchanged.
- Existing profile data and the existing contact-form submission path are reused.
- The mobile breakpoint follows the site's existing convention (max-width 768px).
