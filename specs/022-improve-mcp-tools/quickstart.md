# Quickstart: Improve MCP Tools

**Feature**: 022-improve-mcp-tools | **Date**: 2026-04-15

## Overview

This feature modifies the existing `ProfileMcpTools` component and related services. No new infrastructure, indices, or external dependencies are introduced.

## Files to Modify

### Backend (primary changes)

| File | Change |
|------|--------|
| `ProfileMcpTools.java` | Add `submitContactForm` tool, fix `searchBlogs` delegation, add query params to `getJobs`/`getSkills`/`getUpcomingEvents`, change `searchNews` to use ES |
| `SearchService.java` | Add `searchByType(query, type)` method |
| `ContactService.java` | Add `submitFromChat(ContactSubmission)` method (skips reCAPTCHA) |
| `ProfileMcpToolsTest.java` | Update tests for changed tool signatures, add contact tool tests |

### Backend (supporting changes)

| File | Change |
|------|--------|
| `ChatController.java` | Add `contactSubmittedSessions` Set, expose check/mark methods |
| `ChatSessionCleanupService.java` | Clear contact-submitted flag on session eviction |
| `SearchServiceTest.java` | Add tests for `searchByType` |

### No frontend changes

All changes are backend-only. The MCP tools are invoked by the AI during chat — no frontend UI changes needed.

## Prerequisites

- Backend `.env` file with `OPENAI_API_KEY`, `SPRING_MAIL_*` variables
- Running MongoDB and Elasticsearch instances (via Docker Compose or Testcontainers for tests)
- Existing `site_search` and `blog_search` indices populated (via `SearchIndexSyncScheduler`)

## Testing Strategy

1. **Unit tests**: Mock service dependencies in `ProfileMcpToolsTest` for all modified/new tools
2. **Integration tests**: Testcontainers-backed tests for `SearchService.searchByType()` with real Elasticsearch
3. **Manual testing**: Open chat, test contact tool flow, verify search tools return filtered results

## Key Decisions

- Contact tool bypasses reCAPTCHA (chat has its own rate limiting)
- No new ES indices — reuse `site_search` with type filtering
- `searchBlogs` return type changes from `GroupedSearchResponse` to `List<BlogSearchResult>`
- Session tracking for contact-used state uses `Set<String>` in `ChatController`
