# Feature Specification: Profile Chat with MCP Tools

**Feature Branch**: `009-profile-chat`
**Created**: 2026-02-26
**Status**: Draft
**Input**: User description: "I want to build a feature on the profile section where the user can start a chat. Would be good if the user could hit enter on the search bar, so the typeahead still exists, but when the user hits enter it starts a chat. The chat can be pretty ephemeral, when they start a chat we can create a key (random UUID). Would also like to expose some MCP tools so both this chat as well as other agents can use the tools when asking questions. This functionality doesnt need to be secured but should probably rate limit somehow"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start a Chat from the Search Bar (Priority: P1)

A visitor on the profile homepage types a question or topic into the existing search bar. As they type, the familiar typeahead dropdown continues to show matching site content (blogs, jobs, skills). When the visitor presses Enter, instead of navigating to a search results page, the system opens an inline chat interface. The chat is seeded with their search query as the first message. A unique session key (UUID) is generated to identify this ephemeral conversation.

**Why this priority**: This is the core interaction — without it, there is no chat feature. It builds directly on the existing search bar, making it the natural entry point.

**Independent Test**: Can be fully tested by typing a query into the search bar, pressing Enter, and verifying a chat panel opens with the query as the first message and a unique session key assigned.

**Acceptance Scenarios**:

1. **Given** a visitor is on the profile homepage, **When** they type a query and press Enter in the search bar, **Then** a chat interface opens with their query displayed as the first user message
2. **Given** a visitor is typing in the search bar, **When** typeahead results appear and the visitor presses Enter (without clicking a result), **Then** the chat opens instead of navigating to a typeahead result
3. **Given** a visitor clicks a typeahead result, **When** the result is selected, **Then** the visitor navigates to that content as before (chat does not open)
4. **Given** a chat session is started, **When** the session begins, **Then** a unique session key (UUID) is generated and associated with the conversation
5. **Given** the search bar is empty, **When** the visitor presses Enter, **Then** no chat is opened and no action is taken

---

### User Story 2 - Conversational Chat Experience (Priority: P1)

Once the chat is open, the visitor can read responses and send follow-up messages in a conversational thread. The chat feels lightweight and responsive. The visitor can close the chat at any time and return to browsing. The conversation is ephemeral — it is not persisted after the session ends or the page is refreshed.

**Why this priority**: Equally critical to Story 1 — the chat must actually work as a conversation for the feature to deliver value.

**Independent Test**: Can be tested by opening a chat, sending multiple messages, receiving responses, and verifying the conversation flows naturally. Refreshing the page should clear the conversation.

**Acceptance Scenarios**:

1. **Given** a chat is open with an initial query, **When** the system processes the query, **Then** a response is displayed in the chat thread
2. **Given** a chat is active, **When** the visitor types a follow-up message and sends it, **Then** the message appears in the thread and a response follows
3. **Given** a chat is active, **When** the visitor closes the chat, **Then** the chat panel disappears and the visitor returns to the normal profile view
4. **Given** a chat was previously active, **When** the visitor refreshes the page, **Then** the previous conversation is gone (ephemeral)
5. **Given** a response is being generated, **When** the visitor is waiting, **Then** a visible loading/typing indicator is shown

---

### User Story 3 - MCP Tool Exposure for External Agents (Priority: P2)

The system exposes a set of MCP (Model Context Protocol) tool endpoints that allow both the built-in chat and external agents to query profile-related information. These tools provide structured access to profile data, blog content, job history, skills, and site search — enabling AI agents to answer questions about the site owner using real data.

**Why this priority**: MCP tools extend the feature's value beyond the built-in chat, allowing other AI agents and integrations to leverage the same data. However, the chat can function without external agent access initially.

**Independent Test**: Can be tested by calling the MCP tool endpoints directly (without the chat UI) and verifying they return structured profile data in the expected MCP format.

**Acceptance Scenarios**:

1. **Given** an external agent or the built-in chat, **When** a tool request is made to retrieve profile information, **Then** the system returns structured profile data (name, title, headline, description, contact details, social links)
2. **Given** a tool request for blog content, **When** the request includes a search query, **Then** the system returns matching blog summaries
3. **Given** a tool request for job/employment history, **When** the request is made, **Then** the system returns the job history in structured format
4. **Given** a tool request for skills, **When** the request is made, **Then** the system returns skill groups and individual skills
5. **Given** a tool request for site search, **When** a query is provided, **Then** the system returns grouped search results (blogs, jobs, skills)

---

### User Story 4 - Rate Limiting (Priority: P2)

The chat and MCP tool endpoints are rate-limited to prevent abuse. Since the feature is publicly accessible without authentication, rate limiting is applied per client to ensure fair usage and protect backend resources.

**Why this priority**: Important for production readiness and cost control (especially since an LLM service is involved), but the core feature works without it during development.

**Independent Test**: Can be tested by sending requests above the rate limit threshold and verifying that excess requests are rejected with an appropriate response.

**Acceptance Scenarios**:

1. **Given** a client is using the chat normally, **When** they send messages at a reasonable pace, **Then** all messages are processed without restriction
2. **Given** a client exceeds the rate limit, **When** they send an additional request, **Then** they receive a clear message indicating they should wait before sending more messages
3. **Given** a client was rate-limited, **When** the rate limit window resets, **Then** the client can resume sending messages
4. **Given** an external agent calls MCP tool endpoints, **When** the agent exceeds the rate limit, **Then** the request is rejected with an appropriate status

---

### Edge Cases

- What happens when the chat backend or LLM service is unavailable? The chat should display a friendly error message and allow the visitor to dismiss the chat or retry.
- What happens if a visitor presses Enter on the search bar multiple times? Each Enter with a new query should replace or continue the existing chat session rather than opening multiple panels.
- What happens with extremely long messages? Input should be capped at a reasonable length (500 characters) with a visible character count.
- What happens on mobile devices? The chat should be responsive and usable on smaller screens, potentially as a full-screen overlay.
- What happens if the visitor navigates to a different page (e.g., blog detail) and returns? The ephemeral chat session is lost.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST retain the existing typeahead search behaviour — displaying matching results in a dropdown as the user types
- **FR-002**: System MUST open an inline chat interface when the user presses Enter in the search bar with a non-empty query
- **FR-003**: System MUST generate a unique session key (UUID) for each new chat conversation
- **FR-004**: System MUST display the user's search query as the first message in the chat thread
- **FR-005**: System MUST process the user's message and return a contextual response using available profile, blog, job, and skills data
- **FR-006**: System MUST allow the user to send follow-up messages within the same chat session
- **FR-007**: System MUST display a loading/typing indicator while a response is being generated
- **FR-008**: System MUST allow the user to close the chat and return to the normal profile view
- **FR-009**: Chat sessions MUST be ephemeral — not persisted to any database or storage beyond the active browser session
- **FR-010**: System MUST expose MCP-compatible tool endpoints for: profile data, blog search, job history, skills, and site search
- **FR-011**: MCP tool endpoints MUST return structured data following the MCP tool response format
- **FR-012**: System MUST apply rate limiting to chat message endpoints and MCP tool endpoints
- **FR-013**: System MUST return a clear, user-friendly message when a rate limit is exceeded
- **FR-014**: System MUST cap user message input length at 500 characters
- **FR-015**: System MUST be responsive and functional on mobile devices

### Key Entities

- **Chat Session**: An ephemeral conversation identified by a UUID key, containing an ordered list of messages. Not persisted beyond the active browser session.
- **Chat Message**: A single message within a session, with a role (user or assistant), content text, and timestamp.
- **MCP Tool**: A named capability exposed via the MCP protocol that accepts structured input and returns structured output. Tools include: get_profile, search_blogs, get_jobs, get_skills, search_site.

## Assumptions

- The chat responses will be powered by an LLM service (the specific provider is an implementation detail to be decided during planning).
- The MCP tools will follow the Model Context Protocol specification for tool definitions and invocations.
- Rate limiting will use a token-bucket or sliding-window approach per IP address, with reasonable defaults (e.g., 20 messages per minute for chat, 60 requests per minute for MCP tools).
- The chat UI will appear as a panel or overlay on the profile page, not a separate page.
- The existing search bar styling and placement remain unchanged — only the Enter key behaviour is added.
- Mobile chat will use a full-screen or near-full-screen overlay for usability.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can initiate a chat conversation within 1 second of pressing Enter on the search bar
- **SC-002**: Chat responses begin streaming or appear within 5 seconds of sending a message
- **SC-003**: The typeahead search experience remains unaffected — results still appear within 300ms of typing
- **SC-004**: MCP tool endpoints return structured data within 2 seconds of a request
- **SC-005**: Rate-limited requests receive a clear rejection response rather than hanging or failing silently
- **SC-006**: The chat interface is usable on screens as small as 375px wide (standard mobile)
- **SC-007**: Chat sessions are fully ephemeral — no conversation data is recoverable after page refresh
