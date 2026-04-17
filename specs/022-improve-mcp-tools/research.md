# Research: Improve MCP Tools

**Feature**: 022-improve-mcp-tools | **Date**: 2026-04-15

## R-001: Contact Tool Session Tracking

**Decision**: Track contact-used state as a `boolean` flag on the existing `ConcurrentHashMap`-based session management in `ChatController`, alongside the existing `sessionMessageCounts` map.

**Rationale**: The chat session lifecycle is already managed in-memory with `ConcurrentHashMap<String, AtomicInteger>` for message counts and `ConcurrentHashMap<String, Instant>` for activity tracking. Adding a `ConcurrentHashMap<String, Boolean>` (or `Set<String>`) for contact-used tracking follows the same pattern. The `ChatSessionCleanupService` already evicts stale sessions — the contact-used flag will be cleaned up in the same pass.

**Alternatives considered**:
- Store flag in `ChatService` alongside `sessionActivity` — rejected because the tool itself needs to check/set the flag, and tool invocation happens at the `ProfileMcpTools` level, not `ChatService`.
- Store flag in a dedicated `ContactToolState` component — rejected as over-engineering for a single boolean per session.
- Use `ChatMemory` to track state — rejected because chat memory is for conversation messages, not control flags.

## R-002: Contact Tool reCAPTCHA Bypass

**Decision**: The contact MCP tool will bypass reCAPTCHA verification. It will call `EmailService.send()` directly rather than going through `ContactService.submit()` which enforces reCAPTCHA.

**Rationale**: The chat system already has rate limiting (20 req/min via Bucket4j), session message limits (10 messages max), and the one-contact-per-session restriction. reCAPTCHA is designed for public HTML forms, not AI tool invocations. The `ContactRequest` record requires a `@NotBlank recaptchaToken` field, so reusing the existing service path would require either making the token optional or generating a fake token.

**Alternatives considered**:
- Make `recaptchaToken` optional in `ContactRequest` — rejected because it weakens the public form validation.
- Create a separate `ContactSubmission`-accepting method on `ContactService` — this is the chosen approach. Add a `submitFromChat(ContactSubmission)` method that skips reCAPTCHA but still sends email.

## R-003: Blog Search Differentiation

**Decision**: Change `searchBlogs` in `ProfileMcpTools` to call `searchService.blogSearch(query)` instead of `searchService.siteSearch(query)`. The `blogSearch` method already exists with proper field boosting (`title^3`, `tags^2`, `shortDescription^2`, `content`, `skills`) and returns `List<BlogSearchResult>`.

**Rationale**: The `blogSearch` method and `blog_search` index already exist and are correctly configured. The only issue is that `ProfileMcpTools.searchBlogs()` calls `siteSearch()` instead of `blogSearch()`. This is a one-line fix.

**Alternatives considered**:
- Create a new search method — rejected because `blogSearch()` already does exactly what's needed.
- Add a type filter to `siteSearch()` — rejected because it still uses the site_search index which lacks blog-specific field boosting.

## R-004: Adding Search to Jobs, Skills, and Events Tools

**Decision**: Add optional `query` parameters to `getJobs()`, `getSkills()`, and `getUpcomingEvents()`. When a query is provided, use Elasticsearch `site_search` index filtered by type (`job`, `skill`, `event` respectively). When no query is provided, return all items from MongoDB as before.

**Rationale**: The `site_search` index already indexes jobs, skills, and events with text fields (`name`, `shortDescription`, `longDescription`, `company`). Using the existing index avoids creating new indices. The type field in `site_search` can be used to filter results to the correct content type.

**Alternatives considered**:
- Filter in-memory from MongoDB results — rejected because it doesn't provide relevance ranking and scales poorly.
- Create dedicated indices per content type — rejected as unnecessary given the existing `site_search` index supports type-based filtering.
- Add search to `SearchService` — chosen approach: add `searchByType(query, type)` method to `SearchService` that filters `site_search` by type.

## R-005: News Article Elasticsearch Integration

**Decision**: News articles are already indexed in the `site_search` index (via `IndexService.indexArticleContent()` with type="news"). Change `searchNews` in `ProfileMcpTools` to use the new `SearchService.searchByType(query, "news")` method instead of in-memory filtering.

**Rationale**: `IndexService` already handles news article indexing with `articleToSiteDocument()`. The `fullSyncSiteIndex()` method already syncs articles. The only gap is that `ProfileMcpTools.searchNews()` queries MongoDB directly instead of using Elasticsearch.

**Alternatives considered**:
- Create a dedicated news search index — rejected because news is already in `site_search`.
- Keep in-memory filtering — rejected because it lacks relevance ranking and requires loading all articles.

## R-006: Elasticsearch Unavailability Handling

**Decision**: Search-based tools will catch `IOException` / `ElasticsearchException` and return a descriptive error string that the AI can relay to the visitor.

**Rationale**: The spec clarification states tools should return an error rather than falling back or returning empty results. Since MCP tools return their result directly to the AI, returning a string error message (e.g., "Search is temporarily unavailable. Please try again later.") is the simplest approach. The AI will naturally relay this to the visitor.

**Alternatives considered**:
- Throw an exception — rejected because the AI tool framework may not handle exceptions gracefully.
- Fall back to MongoDB — rejected per spec clarification.
- Return empty results — rejected per spec clarification.
