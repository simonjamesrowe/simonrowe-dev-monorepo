# Data Model: Improve MCP Tools

**Feature**: 022-improve-mcp-tools | **Date**: 2026-04-15

## Entity Changes

### Chat Session (extended)

The existing in-memory chat session tracking in `ChatController` gains a new field:

| Field | Type | Storage | Description |
|-------|------|---------|-------------|
| sessionId | String | Key in ConcurrentHashMap | Existing — unique session identifier |
| messageCount | AtomicInteger | `sessionMessageCounts` map | Existing — tracks messages per session |
| contactSubmitted | boolean | `contactSubmittedSessions` Set | New — tracks whether contact tool succeeded |

**Lifecycle**: Set to `true` on successful contact submission. Cleared when session is evicted by `ChatSessionCleanupService`.

### Contact Submission (unchanged)

The existing `ContactSubmission` record is reused as-is for the MCP tool flow:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| firstName | String | Required, max 100 chars | Visitor's first name |
| lastName | String | Required, max 100 chars | Visitor's last name |
| email | String | Required, valid email, max 254 chars | Visitor's email |
| subject | String | Required, max 200 chars | Message subject |
| message | String | Required, max 5000 chars | Message body |
| referrer | String | Optional | Source page (set to "AI Chat" for tool submissions) |

### Search Indices (unchanged)

No new indices are created. Existing indices are reused:

- **site_search**: Already indexes blogs, jobs, skills, news, events with `type` field for filtering
- **blog_search**: Already has field-level boosting for blog-specific queries

## New Search Method

`SearchService` gains a new method:

```
searchByType(query: String, type: String) -> List<SiteSearchResult>
```

Performs a multi-match query on `site_search` index filtered by the `type` field. Returns results sorted by relevance score.

## State Diagram: Contact Tool per Session

```
[No Contact] --submit(valid)--> [Pending Email]
[Pending Email] --email success--> [Contact Submitted]
[Pending Email] --email failure--> [No Contact] (retry allowed)
[Contact Submitted] --submit attempt--> [Rejected] (return error message)
[Any State] --session evicted--> [Cleared]
```
