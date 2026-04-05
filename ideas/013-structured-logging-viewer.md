# 013: Structured Log Viewer in Admin Console

## Summary
Add a log viewer to the admin console that displays structured application logs in real-time. Stream backend logs via WebSocket, with filtering by log level, component, and time range. Includes special views for chat conversations, API errors, and deployment events.

## Why
Currently, viewing logs requires SSH into the server or checking Grafana Cloud. A built-in log viewer in the admin console provides immediate visibility into what the application is doing, which is especially useful for debugging issues, monitoring deployments, and watching AI chat interactions in real-time.

## Features
- **Live Log Stream** - Real-time log tailing via WebSocket
- **Log Level Filter** - Toggle ERROR, WARN, INFO, DEBUG visibility
- **Component Filter** - Filter by package/module (chat, search, media, etc.)
- **Search** - Full-text search within log messages
- **Special Views**:
  - Chat conversations (formatted as conversation threads)
  - API error log (with request/response details)
  - Deployment log (Docker redeploy output)
  - Scheduled task log (search sync, cleanup)
- **Log Retention** - Last 24 hours in-memory, link to Grafana for historical

## Technical Approach
- Backend: Custom Logback appender that buffers recent log entries in a ring buffer
- WebSocket endpoint `/ws/admin/logs` streaming log entries to connected admin clients
- Structured log format: `{ timestamp, level, logger, message, mdc, stackTrace }`
- Frontend: Log viewer component with virtual scrolling (for performance), ANSI colour rendering
- Filters applied client-side for responsiveness

## Complexity
Medium. The custom Logback appender and WebSocket streaming are the main backend work. Virtual scrolling is important for frontend performance.

## Dependencies
- Existing WebSocket/STOMP infrastructure
- Logback (already in use via Spring Boot)
