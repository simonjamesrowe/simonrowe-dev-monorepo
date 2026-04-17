# MCP Tools API Contracts

**Feature**: 022-improve-mcp-tools | **Date**: 2026-04-15

These are the MCP tool signatures exposed to the AI via Spring AI `@Tool` annotations. They are not REST endpoints — they are invoked by the LLM during chat conversations.

## New Tool: submitContactForm

**Description**: Submit a contact message to Simon on behalf of the visitor. Can only be used once per chat session. Failed submissions can be retried.

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| firstName | String | Yes | Visitor's first name (max 100 chars) |
| lastName | String | Yes | Visitor's last name (max 100 chars) |
| email | String | Yes | Visitor's email address (must be valid format) |
| subject | String | Yes | Message subject (max 200 chars) |
| message | String | Yes | Message body (max 5000 chars) |

**Returns**: `String` — success confirmation or error message

**Responses**:
- Success: `"Message sent successfully to Simon. He will respond to {email} soon."`
- Already submitted: `"A contact message has already been sent in this chat session. Only one message per session is allowed."`
- Validation error: `"Invalid email address"` / `"All fields are required"` etc.
- Delivery failure: `"Failed to send message. Please try again."`

**Session context**: Requires `sessionId` injected from the chat session context (not a tool parameter).

---

## Modified Tool: searchBlogs

**Change**: Delegates to `blogSearch()` instead of `siteSearch()`

**Parameters**: Unchanged — `query: String`

**Returns**: `List<BlogSearchResult>` (was `GroupedSearchResponse`)

**Return type change**: Now returns blog-specific results with `title`, `shortDescription`, `image`, `publishedDate`, `url` instead of grouped multi-type results.

---

## Modified Tool: getJobs

**Change**: Adds optional `query` parameter

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | String | No | Search keywords to filter jobs. Pass null/empty for all jobs. |

**Returns**: `List<JobSummaryDto>` (when no query) or `List<SiteSearchResult>` (when query provided)

**Behaviour**:
- No query: Returns all jobs from MongoDB (backwards compatible)
- With query: Searches `site_search` index filtered by `type=job`

---

## Modified Tool: getSkills

**Change**: Adds optional `query` parameter

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | String | No | Search keywords to filter skills. Pass null/empty for all skills. |

**Returns**: `List<SkillGroupSummaryDto>` (when no query) or `List<SiteSearchResult>` (when query provided)

**Behaviour**:
- No query: Returns all skill groups from MongoDB (backwards compatible)
- With query: Searches `site_search` index filtered by `type=skill`

---

## Modified Tool: getUpcomingEvents

**Change**: Adds optional `query` parameter

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | String | No | Search keywords to filter events. Pass null/empty for upcoming events. |

**Returns**: `List<Map<String, Object>>` (unchanged format)

**Behaviour**:
- No query: Returns upcoming events from MongoDB (backwards compatible)
- With query: Searches `site_search` index filtered by `type=event`

---

## Modified Tool: searchNews

**Change**: Uses Elasticsearch instead of in-memory filtering

**Parameters**: Unchanged — `query: String`

**Returns**: `List<Map<String, Object>>` (unchanged format)

**Behaviour**:
- No query: Returns latest 10 articles (unchanged)
- With query: Searches `site_search` index filtered by `type=news` (was in-memory `.contains()`)

---

## Modified Tool: searchSite

**No change** — already correctly delegates to `searchService.siteSearch(query)`.

---

## Unchanged Tools

- **getProfile()**: No changes
- **getRecentBlogs()**: No changes (simple listing, covered by searchBlogs for keyword search)
- **getCodeExamples(language)**: No changes

---

## Error Handling (all search tools)

When Elasticsearch is unavailable, search-based tools return:
```
"Search is temporarily unavailable. Please try again later."
```
