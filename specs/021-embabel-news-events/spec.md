# Feature Specification: Embabel-Powered News & Events Aggregation

**Feature Branch**: `021-embabel-news-events`  
**Created**: 2026-04-12  
**Status**: Draft  
**Input**: User description: "Build a new feature using the Embabel framework to pull articles/blogs and events from multiple external sources on a schedule, summarize with AI, and present on the screen in two new tabs - News and Events. Store summaries in MongoDB, make them searchable and available in the chatbot. Additionally, a weekly digest agent should auto-generate summary articles about site activity."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse Curated Tech News (Priority: P1)

As a site visitor, I want to browse a "News" tab showing AI-summarized articles from external tech sources so I can quickly catch up on relevant industry developments without visiting multiple sites.

**Why this priority**: The News tab is the primary new content surface. Without aggregated and summarized content, no other feature (search, chat, weekly digest) has data to work with. This is the foundational capability.

**Independent Test**: Can be fully tested by navigating to the News tab and seeing a list of summarized articles with source attribution, publication dates, and links to originals. Delivers immediate value as a curated news feed.

**Acceptance Scenarios**:

1. **Given** the site has aggregated articles from external sources, **When** a visitor clicks the "News" tab in the top navigation, **Then** they see a chronologically ordered list of summarized articles with titles, source names, publication dates, and brief AI-generated summaries.
2. **Given** the News tab is displayed, **When** a visitor clicks on a news item, **Then** they are taken to the original article on the external source site (opens in a new browser tab).
3. **Given** multiple sources have been aggregated, **When** a visitor views the News tab, **Then** each item clearly shows which source it came from (e.g., "AI Native Dev", "Rundown AI", "Spring Blog").
4. **Given** no articles have been aggregated yet (first deployment), **When** a visitor views the News tab, **Then** they see a friendly message indicating content is being gathered and to check back soon.

---

### User Story 2 - Browse Upcoming and Past Events (Priority: P1)

As a site visitor, I want to browse an "Events" tab showing tech community events (meetups, conferences) so I can discover relevant events to attend.

**Why this priority**: Events are time-sensitive and represent a distinct content type from news articles. Delivering events alongside news provides the two new tabs requested and covers both content categories.

**Independent Test**: Can be fully tested by navigating to the Events tab and seeing a list of events with dates, venues, and descriptions. Delivers value as an event discovery feed.

**Acceptance Scenarios**:

1. **Given** events have been aggregated from external sources, **When** a visitor clicks the "Events" tab in the top navigation, **Then** they see events separated into "Upcoming" and "Past" sections, with upcoming events shown first.
2. **Given** an event listing is displayed, **When** a visitor views an event, **Then** they see the event title, date/time, venue/location, a brief description, and a link to the original event page.
3. **Given** an upcoming event exists, **When** a visitor clicks on it, **Then** they are directed to the original event page (e.g., Meetup.com) in a new browser tab.

---

### User Story 3 - Scheduled Content Aggregation (Priority: P1)

As the site owner, I want external content to be automatically fetched and summarized on a recurring schedule so the News and Events tabs stay current without manual intervention.

**Why this priority**: Without automated scheduled scraping and summarization, the feature requires manual content entry, defeating its purpose. This is the engine that powers everything.

**Independent Test**: Can be tested by verifying that after a scheduled run completes, new content from external sources appears in the database and on the News/Events tabs. Can also be triggered manually for testing.

**Acceptance Scenarios**:

1. **Given** the scheduled aggregation is configured, **When** the schedule triggers (default: every 6 hours), **Then** the system fetches new content from all configured sources (AI Native Dev, Rundown AI, London Java Community, Spring Blog).
2. **Given** new articles are fetched, **When** the summarization step runs, **Then** each article receives an AI-generated summary of 2-3 sentences capturing the key points.
3. **Given** an article has already been aggregated, **When** the same article appears in a subsequent fetch, **Then** the system recognizes the duplicate and does not create a second entry.
4. **Given** an external source is temporarily unavailable, **When** the scheduled fetch runs, **Then** the system logs the failure for that source and continues processing the remaining sources without interruption.
5. **Given** the aggregation completes, **When** new content is stored, **Then** it is automatically indexed for search and embedded for the AI chatbot's knowledge base.

---

### User Story 4 - Search Aggregated Content (Priority: P2)

As a site visitor, I want to find aggregated news and events through the site's existing search so I can quickly locate specific topics across all site content.

**Why this priority**: Integrating with existing search makes aggregated content discoverable alongside blogs, jobs, and skills. This leverages existing infrastructure and adds significant value.

**Independent Test**: Can be tested by performing a site search for a term that appears in aggregated content and verifying news/event results appear in search results alongside existing content types.

**Acceptance Scenarios**:

1. **Given** aggregated articles exist about "Spring Boot", **When** a visitor searches for "Spring Boot" in the site search, **Then** relevant news items appear in search results alongside blogs and other content, clearly labeled as "News" type.
2. **Given** an event titled "Java and Gen AI" exists, **When** a visitor searches for "Gen AI", **Then** the event appears in search results labeled as "Event" type.
3. **Given** search results include news/events, **When** a visitor clicks a news result, **Then** they are navigated to the News tab for that item.

---

### User Story 5 - Ask the Chatbot About News and Events (Priority: P2)

As a site visitor, I want to ask the AI chatbot questions about recent news and upcoming events so I can get conversational answers about aggregated content.

**Why this priority**: The chatbot already serves as a primary interaction method. Extending its knowledge to include aggregated content creates a more comprehensive assistant.

**Independent Test**: Can be tested by asking the chatbot "What are the latest news about Spring?" and verifying it references aggregated news content in its response with source attribution.

**Acceptance Scenarios**:

1. **Given** news articles about "Embabel" have been aggregated, **When** a visitor asks the chatbot "What's new with Embabel?", **Then** the chatbot responds with relevant information from aggregated articles, citing sources.
2. **Given** upcoming events exist, **When** a visitor asks "Are there any Java meetups coming up?", **Then** the chatbot responds with event details including dates and venues.
3. **Given** the chatbot references aggregated content, **When** it provides an answer, **Then** responses include source attribution (source name and link to original).

---

### User Story 6 - Weekly Site Activity Digest (Priority: P3)

As the site owner, I want an automated weekly digest article generated that summarizes the week's activity - new features added to the site, blogs written, and notable aggregated news - so visitors have a "this week in review" experience.

**Why this priority**: This is an advanced feature building on top of all other capabilities. It requires the aggregation pipeline, blog data, and AI summarization to all be working. Valuable but depends on the core features.

**Independent Test**: Can be tested by triggering the weekly digest generation and verifying a new blog-style article is created summarizing the past week's activity, visible on the site.

**Acceptance Scenarios**:

1. **Given** the weekly schedule triggers (default: every Monday morning), **When** the digest agent runs, **Then** it generates a summary article covering the past 7 days of activity.
2. **Given** the past week had new blog posts published, **When** the digest is generated, **Then** the summary mentions the new blog posts with brief descriptions.
3. **Given** the past week had notable aggregated news, **When** the digest is generated, **Then** the summary highlights the most significant news items.
4. **Given** new site features were deployed in the past week, **When** the digest is generated, **Then** the summary mentions the new features (based on commit history or release notes).
5. **Given** the digest is generated, **When** it is published, **Then** it appears as a regular blog post on the site, tagged appropriately (e.g., "Weekly Digest"), and is searchable and available to the chatbot.
6. **Given** it was a quiet week with minimal activity, **When** the digest agent runs, **Then** it either generates a brief "quiet week" summary or skips generation entirely, avoiding low-value content.

---

### Edge Cases

- What happens when an external source changes its page structure? The system should log scraping failures and continue with other sources; an alert mechanism should notify the site owner.
- What happens when the AI summarization produces a poor or irrelevant summary? Summaries should be stored but the site owner should be able to review and hide individual items via admin.
- How does the system handle rate limiting from external sources? The scraper should respect rate limits and implement appropriate delays between requests.
- What happens when aggregated content contains inappropriate or irrelevant material? The system should provide basic content filtering and allow the site owner to remove items.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST aggregate articles and blog posts from at least four external sources: AI Native Dev (ainativedev.io), Rundown AI (rundown.ai/articles), London Java Community Meetup (meetup.com/londonjavacommunity), and Spring Blog (spring.io/blog). For each source, the system MUST follow links from listing pages to scrape full article content for summarization.
- **FR-002**: System MUST generate AI summaries (2-3 sentences) for each aggregated article using an LLM.
- **FR-003**: System MUST run content aggregation on a configurable schedule (default: every 6 hours).
- **FR-004**: System MUST store aggregated content (title, source, original URL, summary, publication date, content type) persistently.
- **FR-005**: System MUST detect and skip duplicate content based on source URL to prevent re-aggregation.
- **FR-006**: System MUST present a "News" navigation tab showing summarized articles in reverse chronological order.
- **FR-007**: System MUST present an "Events" navigation tab showing events with upcoming/past separation.
- **FR-008**: System MUST index aggregated content for the site's existing search functionality, with results labeled by type (News/Event).
- **FR-009**: System MUST embed aggregated content into the vector store so the AI chatbot can reference it in conversations.
- **FR-010**: System MUST run a weekly digest agent (default: every Monday) that generates a summary article covering the past week's site activity.
- **FR-011**: Weekly digest articles MUST be published as regular blog posts, tagged as "Weekly Digest", and automatically indexed and embedded.
- **FR-012**: System MUST use the Embabel agent framework for orchestrating the aggregation, summarization, and digest workflows.
- **FR-013**: System MUST handle individual source failures gracefully, continuing with remaining sources and logging errors.
- **FR-014**: System MUST provide an admin capability to review, hide, or remove aggregated content items.
- **FR-015**: All aggregated content MUST include clear source attribution (source name and link to original).
- **FR-016**: System MUST support adding new external sources through configuration without code changes.

### Key Entities

- **AggregatedArticle**: Represents a news article or blog post fetched from an external source. Key attributes: title, source name, source URL, original URL, AI-generated summary, publication date, content type (news/event), fetch timestamp, visibility status.
- **AggregatedEvent**: Represents an event fetched from an external source. Key attributes: title, source name, original URL, event date/time, venue/location, description, AI-generated summary, fetch timestamp, visibility status.
- **ContentSource**: Represents a configured external source. Key attributes: name, base URL, source type (blog/news/events), active status.
- **WeeklyDigest**: Represents an auto-generated weekly summary. Key attributes: generated date, coverage period (start/end dates), summary content, associated blog post reference, generation status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors can access curated tech news within 2 clicks from the homepage (via News tab).
- **SC-002**: Visitors can discover upcoming tech events within 2 clicks from the homepage (via Events tab).
- **SC-003**: New content from external sources appears on the site within 8 hours of publication on the original source.
- **SC-004**: 95% of site searches for topics covered by aggregated content return relevant news/event results.
- **SC-005**: The AI chatbot correctly references aggregated content in at least 80% of relevant queries.
- **SC-006**: Weekly digest articles are automatically published every Monday without manual intervention.
- **SC-007**: The system successfully aggregates content from at least 3 out of 4 configured sources on each scheduled run.
- **SC-008**: Individual source failures do not prevent content from other sources from being aggregated (zero cascading failures).
- **SC-009**: Aggregated content pages load within 2 seconds on standard broadband connections.

## Clarifications

### Session 2026-04-12

- Q: How much content should be scraped from each external source? → A: Full article content by following links from listing pages; summaries generated from complete article text.
- Q: How long should aggregated content be retained? → A: Kept indefinitely with no automatic cleanup or archival.
- Q: Should weekly digest articles require admin approval before publishing? → A: Auto-publish immediately with no approval step.

## Assumptions

- External sources do not provide public APIs; content will be fetched via web scraping (HTML parsing) or RSS feeds where available.
- The Spring Blog (spring.io/blog) may require JavaScript rendering for content extraction; a headless browser approach may be needed for this source.
- The Embabel agent framework (v0.3.5+) is compatible with the existing Spring Boot 3.5.x stack and can be added as a dependency.
- OpenAI API (already configured for the existing chatbot) will be used for AI summarization within Embabel agents.
- The site owner is the primary admin user; no multi-user admin workflow is required for content moderation.
- RSS feeds will be preferred over HTML scraping where available, as they are more stable and structured.
- The weekly digest agent has access to the site's git history or a changelog mechanism to detect "new features added to the site."
- Content aggregation respects robots.txt and rate limiting of external sources.
- Aggregated content is retained indefinitely; no automatic cleanup or archival mechanism is required.
