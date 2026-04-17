# Feature Specification: Improve MCP Tools

**Feature Branch**: `022-improve-mcp-tools`  
**Created**: 2026-04-15  
**Status**: Draft  
**Input**: Review and improve existing MCP tools — add contact form tool with single-use-per-chat spam protection, make listing tools require search parameters, fix duplicate search behaviour, and leverage Elasticsearch for tool-based searching.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Contact Simon via Chat (Priority: P1)

A website visitor is chatting with Simon's AI assistant and wants to get in touch. Instead of navigating away from the chat to find a contact form, they ask the AI to send a message to Simon. The AI collects the visitor's name, email, subject, and message, then submits it on their behalf. The visitor receives confirmation that the message was sent. If the visitor tries to send another message in the same chat session, the AI informs them that only one contact submission is allowed per session to prevent spam.

**Why this priority**: This is the only net-new feature in the request. It bridges the chat experience with the contact functionality, reducing friction for visitors who are already engaged in conversation.

**Independent Test**: Can be tested by opening the chat, asking the AI to contact Simon, providing details, and verifying the email is received. A second attempt in the same session should be declined.

**Acceptance Scenarios**:

1. **Given** a visitor is chatting with the AI and has not yet used the contact tool, **When** they ask to send a message to Simon and provide their name, email, subject, and message, **Then** the system submits the contact request and confirms delivery to the visitor.
2. **Given** a visitor has already successfully submitted a contact request in the current chat session, **When** they ask to send another message, **Then** the system declines the request and informs them that only one contact submission is allowed per session.
3. **Given** a visitor asks to contact Simon but provides an invalid email address, **When** the AI attempts to submit the form, **Then** the system returns a validation error and the AI relays the issue to the visitor.
4. **Given** a visitor starts a new chat session after previously submitting a contact request, **When** they ask to send a message, **Then** the system allows it because the previous session has ended.

---

### User Story 2 - Search-Based Tool Responses (Priority: P2)

A visitor asks the AI about Simon's experience with a specific technology (e.g., "What has Simon done with Kubernetes?"). Instead of the AI receiving a dump of all jobs, all skills, and all blog posts and then filtering client-side, the tools accept search parameters and return only relevant results. This produces faster, more focused, and more accurate answers.

**Why this priority**: This improves the quality and relevance of every AI-assisted conversation. Returning all items wastes tokens, increases response latency, and risks overwhelming the AI's context with irrelevant data.

**Independent Test**: Can be tested by asking the AI about a specific technology and verifying that only relevant jobs, skills, or blog posts are returned rather than the full list.

**Acceptance Scenarios**:

1. **Given** a visitor asks about a specific technology, **When** the AI calls the jobs tool with a search query, **Then** only jobs mentioning that technology are returned.
2. **Given** a visitor asks about a broad topic, **When** the AI calls the skills tool with a search query, **Then** only matching skill groups are returned.
3. **Given** a visitor asks a general question like "tell me about Simon", **When** the AI needs all items, **Then** the tools still support returning all results when no query is provided (backwards compatibility).

---

### User Story 3 - Deduplicated and Correct Blog Search (Priority: P2)

A visitor asks the AI about a topic Simon has written about. Currently, two separate tools (`searchBlogs` and `searchSite`) both invoke the same underlying site search, returning identical results. After this improvement, `searchBlogs` performs a blog-specific search with field-level relevance boosting (title, tags, content), while `searchSite` searches across all content types. The visitor gets more precise blog results when asking about blog content specifically.

**Why this priority**: This is a bug fix — two tools currently do the same thing. Differentiating them improves search relevance for blog-specific queries and makes the tool set clearer for the AI to reason about.

**Independent Test**: Can be tested by calling `searchBlogs` and `searchSite` with the same query and verifying they return different, appropriately scoped result sets.

**Acceptance Scenarios**:

1. **Given** a visitor asks the AI to find blog posts about "Spring Boot", **When** the AI calls the blog search tool, **Then** results are scoped to blog posts only with relevance ranking by title, tags, and content.
2. **Given** a visitor asks a broad question spanning multiple content types, **When** the AI calls the site search tool, **Then** results include blogs, jobs, skills, news, and events grouped by type.
3. **Given** a visitor asks about a topic that appears in both blog posts and job descriptions, **When** the AI calls both tools, **Then** the blog search returns only blog matches and the site search returns matches across all types.

---

### User Story 4 - Elasticsearch-Powered News Search (Priority: P3)

A visitor asks about recent tech news on a topic. Currently, news search performs in-memory string matching against article titles and summaries fetched from the database. After this improvement, news articles are indexed in Elasticsearch and searched with proper relevance ranking, producing faster and more accurate results.

**Why this priority**: This improves search quality for news but is lower priority since news is secondary content. The current in-memory approach works but scales poorly and lacks relevance ranking.

**Independent Test**: Can be tested by searching for a news topic and verifying results are ranked by relevance rather than simple string containment.

**Acceptance Scenarios**:

1. **Given** news articles are indexed in the search system, **When** a visitor asks about a tech topic, **Then** the news search tool returns articles ranked by relevance to the query.
2. **Given** a visitor searches for news with a partial keyword, **When** the search executes, **Then** results include partial matches across titles, summaries, and source names.

---

### Edge Cases

- What happens when the contact tool is called with missing required fields (e.g., no email)?
- What happens when the contact submission fails due to an email delivery error?
- What happens when search tools are called with an empty or whitespace-only query?
- What happens when Elasticsearch is unavailable? Tools return an error message to the AI, which informs the visitor that search is temporarily unavailable.
- What happens when a chat session expires mid-conversation — is the contact-used flag lost (allowing a fresh submission)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a contact submission tool accessible to the AI during chat conversations
- **FR-002**: System MUST limit the contact submission tool to one successful invocation per chat session. Failed submissions (e.g., email delivery errors) do not count against this limit, allowing the visitor to retry.
- **FR-003**: System MUST validate contact submissions (valid email format, required fields: first name, last name, email, subject, message)
- **FR-004**: System MUST deliver contact submissions to the site owner via the existing email delivery mechanism
- **FR-005**: System MUST return a clear success or failure indication to the AI after a contact submission attempt
- **FR-006**: The blog search tool MUST search only blog content with field-level relevance weighting (title, tags, skills weighted higher than body content)
- **FR-007**: The site search tool MUST search across all content types (blogs, jobs, skills, news, events) and return results grouped by type
- **FR-008**: The jobs tool SHOULD accept an optional search query to filter results by keyword
- **FR-009**: The skills tool SHOULD accept an optional search query to filter results by keyword
- **FR-010**: The events tool SHOULD accept an optional search query to filter results by keyword
- **FR-011**: Tools that accept optional search parameters MUST return all results when no query is provided, preserving backwards compatibility
- **FR-012**: The recent blogs tool remains a simple "get latest N" listing tool with no search parameter (blog search is covered by the dedicated blog search tool)
- **FR-013**: News article search SHOULD use indexed search with relevance ranking rather than in-memory string matching
- **FR-014**: When the search infrastructure is unavailable, search-based tools MUST return an error message rather than falling back or returning empty results

### Assumptions

- The existing contact form email delivery (via Brevo SMTP) will be reused for the contact tool — no new email infrastructure is needed
- reCAPTCHA verification is not required for contact submissions made through the AI chat tool, since the chat itself already has rate limiting and session controls
- The existing blog search index with field-level boosting (title, tags, skills weighted higher) can be reused for the blog search tool differentiation
- Session-level tracking for the one-contact-per-session limit uses the existing chat session identity mechanism
- News articles can be added to the existing site search index as an additional content type, rather than requiring a separate index

### Key Entities

- **Chat Session**: An ephemeral conversation between a visitor and the AI. Tracks whether a contact submission has been made during this session.
- **Contact Submission**: A message from a visitor to the site owner, containing: first name, last name, email, subject, message.
- **Search Index (Site)**: A unified content index spanning blogs, jobs, skills, news, and events with type-based grouping.
- **Search Index (Blog)**: A blog-specific index with field-level relevance weighting for focused blog queries.

## Clarifications

### Session 2026-04-15

- Q: Should failed contact submissions count against the one-per-session limit? → A: No — only successful submissions count. Failed attempts allow retry.
- Q: Which listing tools should gain optional search parameters? → A: Jobs, skills, and events. Recent blogs stays as a simple listing (covered by searchBlogs).
- Q: How should search tools behave when Elasticsearch is unavailable? → A: Return an error message to the AI, which relays "search is temporarily unavailable" to the visitor.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors can submit a contact request through the AI chat without leaving the conversation, completing the flow in under 1 minute
- **SC-002**: A second contact submission attempt in the same chat session is rejected 100% of the time
- **SC-003**: Blog-specific searches return results scoped exclusively to blog content, with zero cross-type contamination
- **SC-004**: Search-based tool queries return results in under 2 seconds
- **SC-005**: Tools that previously returned all items now return only relevant results when a search query is provided, reducing average response payload size by at least 50% for targeted queries
- **SC-006**: All existing chat functionality continues to work without regression after tool changes
