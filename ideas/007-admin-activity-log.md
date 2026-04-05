# 007: Admin Activity Log and Audit Trail

## Summary
Add a comprehensive activity log to the admin console that tracks all content changes, system operations, and AI interactions. Provides a timeline view of what happened, when, and what changed - with diff views for content edits.

## Why
Currently there's no visibility into what changes were made or when. An activity log helps track content publishing history, debug issues ("when did this blog last change?"), and provides accountability. It's also useful for understanding AI agent actions if Embabel agents (idea 002) are introduced.

## Features
- **Activity Timeline** - Chronological feed of all admin actions
- **Action Types**: Blog created/updated/published/deleted, job updated, skill changed, media uploaded, backup/restore triggered, deploy triggered
- **Diff View** - Click any content change to see a before/after diff
- **Filters** - Filter by action type, content type, date range
- **AI Actions** - Track AI-assisted edits separately (if ideas 002/003 are implemented)
- **Retention** - Keep 90 days of activity, auto-archive older entries

## Technical Approach
- Backend: `ActivityLog` MongoDB collection with documents: `{ action, entityType, entityId, timestamp, before, after, actor }`
- Use Spring AOP or MongoDB change streams to automatically capture mutations
- New `AdminActivityController` with paginated list and detail endpoints
- Frontend: Activity log page in admin console with infinite scroll and expandable diff viewer
- Use a lightweight diff library for rendering content changes

## Complexity
Medium. The core logging is straightforward; the diff view and change stream integration add complexity.

## Dependencies
- MongoDB change streams (or AOP interceptors)
- A diff rendering library (frontend)
