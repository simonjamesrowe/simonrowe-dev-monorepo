# Feature Specification: Landing Chat Widgets

**Feature Branch**: `feat/frontend/landing-chat-widgets`

**Created**: 2026-05-30

**Status**: Draft

**Input**: User description: "/Users/simonrowe/conductor/workspaces/simonrowe-dev-monorepo/davis/docs/superpowers/specs/2026-05-30-streaming-chat-widgets-design.md design for updated landing page is here /Users/simonrowe/conductor/workspaces/simonrowe-dev-monorepo/davis/designs"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Land on a polished chat-first homepage (Priority: P1)

A public visitor arrives on simonrowe.dev and immediately understands who Simon Rowe is, his current engineering leadership role, his core areas of expertise, and that they can ask the AI assistant about his work.

**Why this priority**: The landing page is the primary first impression and must establish trust before deeper browsing or chat interaction.

**Independent Test**: Can be fully tested by opening the homepage on desktop and mobile, confirming the hero, navigation, theme controls, inline chat entry point, about summary, calls to action, and footer are visible and usable without visiting another page.

**Acceptance Scenarios**:

1. **Given** a first-time visitor opens the homepage, **When** the page loads, **Then** they see Simon's name, current role, engineering leadership positioning, and a visible AI chat entry point in the first viewport.
2. **Given** a visitor is using a mobile viewport, **When** they open the homepage, **Then** the navigation, chat, hero copy, about section, and calls to action remain readable and do not overlap.
3. **Given** a visitor switches between light and dark themes, **When** they review the homepage, **Then** the page preserves brand colors, contrast, readable text, and consistent visual hierarchy.

---

### User Story 2 - Ask the AI and see progress in real time (Priority: P2)

A visitor asks Simon's AI a question from the landing page and sees the answer arrive progressively, with visible activity whenever the assistant looks up profile or content information.

**Why this priority**: The chat is the main interactive differentiator of the new landing page; visible progress prevents the experience from feeling stalled or opaque.

**Independent Test**: Can be fully tested by asking a question that requires Simon's skills, employment history, code examples, or blog posts, then observing that progress appears before the final answer completes.

**Acceptance Scenarios**:

1. **Given** a visitor submits a question, **When** the assistant begins answering, **Then** the visitor sees response text appear progressively rather than only after the full answer is complete.
2. **Given** the assistant looks up Simon's profile or content data, **When** the lookup is running, **Then** the visitor sees a short human-readable activity label such as looking up skills or searching blog posts.
3. **Given** one or more lookups have completed, **When** the assistant response finishes, **Then** the completed activity is summarized in a quiet expandable line rather than staying as prominent loading chrome.

---

### User Story 3 - Review rich inline evidence cards (Priority: P3)

A visitor asks about Simon's skills, employment history, code examples, or writing and receives structured inline cards in the conversation, followed by concise explanatory text.

**Why this priority**: Cards make detailed profile evidence easier to scan and reduce repetitive prose, but the page remains valuable with the landing presentation and streaming text alone.

**Independent Test**: Can be fully tested by asking one question per supported content category and confirming each answer includes the appropriate inline card when data exists.

**Acceptance Scenarios**:

1. **Given** a visitor asks about Simon's technical skills, **When** matching data exists, **Then** the chat shows a grouped skills card with skill names and strength indicators.
2. **Given** a visitor asks about Simon's career history, **When** matching data exists, **Then** the chat shows an employment card with roles, companies, dates, summaries, and relevant skills.
3. **Given** a visitor asks for examples of Simon's work or writing, **When** matching data exists, **Then** the chat shows code example or blog cards inline in the conversation.
4. **Given** a card has already shown detailed data, **When** the assistant adds prose, **Then** the text briefly frames the result without re-listing the same details.

---

### User Story 4 - Continue browsing from the refreshed landing page (Priority: P4)

A visitor uses the updated homepage to move naturally into Simon's experience, blog, news, CV, contact, and social links without losing the visual language established on the landing page.

**Why this priority**: The landing page should drive exploration and contact, while keeping the first release bounded to the public homepage and chat experience.

**Independent Test**: Can be fully tested by following each visible homepage navigation item or call to action and confirming it reaches the intended destination or clearly unavailable fallback.

**Acceptance Scenarios**:

1. **Given** a visitor wants more detail about Simon's background, **When** they use homepage navigation or calls to action, **Then** they can reach experience, blog, news, CV, contact, and social destinations from clear controls.
2. **Given** a destination is not available, **When** a visitor selects the related control, **Then** the page avoids broken or misleading behavior and presents a clear fallback.

### Edge Cases

- If a supported lookup returns no data, the chat omits the inline card and explains the missing result in concise text.
- If multiple supported lookups occur in one answer, all resulting cards appear in conversation order and the completed activity summary reflects the number of lookups used.
- If the assistant receives an unsupported or unexpected card type, the chat skips that card gracefully while still showing the surrounding answer text.
- If the visitor reconnects, refreshes, clears chat, or hits the message limit, the existing conversation controls and limits remain understandable and recoverable.
- If the visitor uses assistive technology or keyboard-only navigation, all chat controls, cards, theme controls, and navigation items remain reachable and labelled.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The homepage MUST present Simon Rowe's name, current role, engineering leadership positioning, and AI-native/cloud-native expertise in the first viewport.
- **FR-002**: The homepage MUST use the provided design handoff as the source of truth for public landing page layout, brand voice, color themes, typography, spacing, navigation, chat placement, about content, calls to action, and footer behavior.
- **FR-003**: The homepage MUST include a prominent inline AI chat experience that can accept a visitor question without requiring navigation away from the page.
- **FR-004**: The chat MUST provide starter prompts aligned to Simon's expertise, career history, technical patterns, AI-native engineering, and recent writing.
- **FR-005**: Assistant responses MUST appear progressively so visitors can see an answer forming before the complete response is available.
- **FR-006**: Whenever the assistant performs a supported profile or content lookup, the chat MUST show a human-readable activity indicator while that lookup is running.
- **FR-007**: Completed lookup activity MUST collapse into a quiet summary that can reveal the lookup labels used during the answer.
- **FR-008**: Supported lookups for skills, employment history, code examples, and blog posts MUST render structured inline cards when matching data exists.
- **FR-009**: Inline cards MUST appear in the conversation where their source information is used, preserving the order of activity, card evidence, and explanatory text.
- **FR-010**: Assistant prose that accompanies an inline card MUST provide brief framing context and MUST NOT duplicate the detailed data already shown in the card.
- **FR-011**: The skills card MUST group skills by meaningful category and show skill names with strength or rating information when available.
- **FR-012**: The employment card MUST show roles with company, title, dates, summary, and related skills when available.
- **FR-013**: The code example card MUST show examples with title, description, language, code content or preview, and related skills when available.
- **FR-014**: The blog card MUST show posts with title, summary, tags, publication date, and destination link when available.
- **FR-015**: The chat MUST handle empty lookup results, unknown card types, lookup errors, stream interruptions, reconnects, and message limits without breaking the visible conversation.
- **FR-016**: The homepage MUST support dark and light themes with accessible contrast and consistent brand treatment in both modes.
- **FR-017**: The homepage MUST be responsive across mobile, tablet, and desktop viewports without overlapping text, controls, cards, or chat content.
- **FR-018**: Homepage navigation, theme controls, chat controls, prompt chips, card links, and calls to action MUST be keyboard accessible and labelled for assistive technology.
- **FR-019**: Existing public routes and destinations linked from the homepage MUST remain reachable or provide a clear fallback when the destination is not yet available.
- **FR-020**: The feature MUST preserve the current public chat boundaries, including visitor-facing limits and no requirement for public visitor authentication.

### Key Entities

- **Landing Page**: The public homepage experience, including hero, navigation, inline chat, about section, calls to action, theme behavior, and footer.
- **Visitor**: An unauthenticated public user evaluating Simon's background, expertise, writing, or contact options.
- **Chat Conversation**: A sequence of visitor and assistant messages shown during the current browsing session.
- **Assistant Message**: A response made of ordered text, activity indicators, and optional inline cards.
- **Lookup Activity**: A visible indication that the assistant is retrieving supported profile or content information for a response.
- **Inline Card**: A structured evidence block in the chat for skills, employment history, code examples, or blog posts.
- **Profile or Content Result**: Source information about Simon's skills, jobs, code examples, or blog posts used to answer visitor questions.
- **Theme Preference**: The visitor's selected light or dark display mode for the public site.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of first-time visitors in usability testing can identify Simon's current role, primary expertise areas, and the AI chat entry point within 10 seconds of landing on the homepage.
- **SC-002**: 95% of supported chat questions show visible response progress or lookup activity within 2 seconds under normal operating conditions.
- **SC-003**: 100% of supported skills, employment, code example, and blog lookups show the appropriate inline card when matching data exists.
- **SC-004**: 100% of empty, unknown, or failed lookup scenarios preserve a readable assistant answer and do not leave permanent loading indicators.
- **SC-005**: The homepage passes responsive visual review at representative mobile, tablet, and desktop widths with no overlapping text, clipped controls, or inaccessible chat actions.
- **SC-006**: The landing page supports complete keyboard navigation through top navigation, theme controls, chat prompts, message composer, inline card links, calls to action, and footer links.
- **SC-007**: In review against the supplied design handoff, all critical homepage elements match the intended content hierarchy, theme treatment, spacing rhythm, and chat-first layout.

## Assumptions

- The supplied `designs/` directory is the authoritative visual and content handoff for the updated public landing page.
- The supplied streaming chat design document is the authoritative behavioral handoff for progressive answers, visible lookup activity, and inline cards.
- The first release covers the public landing page and the landing-page chat experience; broader page redesigns are only affected where linked navigation or shared site chrome requires consistency.
- Public visitors are unauthenticated and can use the chat within the existing visitor-facing conversation limits.
- Skills, employment history, code examples, and blog posts are the only supported inline card categories for this feature.
- Other assistant capabilities can still answer in text, but do not need inline cards in this release.
- Existing profile, blog, code example, and employment data remain the source of truth for chat evidence.
