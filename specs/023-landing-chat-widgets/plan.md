# Implementation Plan: Landing Chat Widgets

**Branch**: `feat/frontend/landing-chat-widgets` | **Date**: 2026-05-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/023-landing-chat-widgets/spec.md`

## Summary

Refresh the public homepage using the supplied `designs/` handoff and replace the chat drawer's string-only, blocking response model with real streamed assistant blocks. The implementation keeps the existing Spring Boot + React monorepo shape, STOMP transport, in-memory chat sessions, design token approach, and current profile/content data sources. Backend work adds typed chat events and tool-published widgets; frontend work introduces a reducer-based assistant block model, inline widget registry, and chat-first landing presentation.

## Technical Context

**Language/Version**: Java 21, TypeScript 5.7, React 19

**Primary Dependencies**: Spring Boot 3.5.x, Spring AI 1.1.4, Spring WebSocket STOMP, MongoDB, Elasticsearch, Kafka, Vite 6, @stomp/stompjs, lucide-react, react-markdown, react-syntax-highlighter

**Storage**: MongoDB for existing profile/jobs/skills/blog/code-example data; in-memory chat sessions only for chat state

**Testing**: JUnit 5, Mockito, Spring Boot Test, Testcontainers where integration infrastructure is exercised, Vitest + Testing Library for frontend reducer/components

**Target Platform**: Public web app served by frontend container with backend API/WebSocket container behind Nginx

**Project Type**: Monorepo web application with separate backend and frontend containers

**Performance Goals**: Visitors see visible chat progress within 2 seconds for 95% of supported questions; landing page remains responsive at mobile, tablet, and desktop widths

**Constraints**: Keep STOMP `/ws/chat` transport; keep `/topic/chat.{sessionId}` subscription shape; no persisted public chat history; use plain CSS in `frontend/src/styles.css`; use BEM naming; preserve existing visitor message limits and public authentication boundary

**Scale/Scope**: One public landing page refresh plus four v1 widget categories: skills, employment, code examples, and blogs

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Monorepo with Separate Containers**: PASS. Work remains inside existing `backend/` and `frontend/` projects.
- **II. Modern Java & React Stack**: PASS. Uses Java 21, Spring Boot/Spring AI/STOMP, React 19, TypeScript, lucide-react, plain CSS, React Markdown, and existing routing conventions.
- **III. Quality Gates**: PASS. Plan includes backend unit tests and frontend Vitest coverage for critical user journeys.
- **IV. Observability & Operability**: PASS. Existing structured logging and OpenTelemetry spans remain; new publisher/tool paths should log errors without exposing them to visitors.
- **V. Simplicity & Incremental Delivery**: PASS. Uses direct typed chat events and local reducer instead of introducing a new transport, persistence store, or widget orchestration service.
- **VI. Admin CMS UX Standards**: PASS. Admin CMS is not in scope.
- **VII. Interactive Site Tour**: PASS. Existing tour providers remain untouched except where homepage selectors may need to keep working.
- **VIII. Backup & Restore**: PASS. No backup/restore changes.
- **IX. Shell Scripting Standards**: PASS. No new scripts planned.

## Project Structure

### Documentation (this feature)

```text
specs/023-landing-chat-widgets/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── chat-stream-events.md
│   └── frontend-blocks.md
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/chat/
│   ├── ChatController.java
│   ├── ChatResponse.java
│   ├── ChatService.java
│   ├── ChatStreamPublisher.java
│   └── widget payload records
├── src/main/java/com/simonrowe/mcp/
│   └── ProfileMcpTools.java
└── src/test/java/com/simonrowe/
    ├── chat/
    └── mcp/

frontend/
├── src/components/chat/
│   ├── ChatPanel.tsx
│   ├── ChatMessage.tsx
│   ├── chatStreamReducer.ts
│   ├── chatTypes.ts
│   └── widgets/
├── src/components/home/
├── src/pages/HomePage.tsx
├── src/services/chatService.ts
├── src/styles.css
└── src/test/

designs/
├── Landing Page.html
├── chat.jsx
├── landing.css
└── ui_kits/website/
```

**Structure Decision**: Use the existing backend/frontend boundaries. Backend owns stream event production and tool result payload shaping; frontend owns assistant block reduction, widget rendering, and landing page composition. Design files remain references only and are not copied into the runtime as prototype code.

## Complexity Tracking

No constitution violations require justification.

## Phase 0: Research

Research output: [research.md](./research.md)

Key decisions:

- Keep STOMP/WebSocket as the transport and extend event payloads.
- Stream tokens from the Spring AI chat client instead of blocking until a full answer is available.
- Let tools self-publish `TOOL_START`, `WIDGET`, and `TOOL_END` events using the session id already available in tool context.
- Model frontend assistant messages as ordered blocks and update them through a pure reducer.
- Port visual intent from `designs/` into existing React components and `styles.css`, not by adopting the prototype runtime.

## Phase 1: Design & Contracts

Design outputs:

- [data-model.md](./data-model.md)
- [contracts/chat-stream-events.md](./contracts/chat-stream-events.md)
- [contracts/frontend-blocks.md](./contracts/frontend-blocks.md)
- [quickstart.md](./quickstart.md)

### Backend Design

- Extend `ChatResponse` with optional `toolLabel`, `widgetKind`, and `payload` fields.
- Replace `STREAM_RESET` with explicit tool/widget events.
- Add `ChatStreamPublisher` as the only class that formats `/topic/chat.{sessionId}` destinations for tool events.
- Change `ChatService.processMessage` from blocking response collection to streaming response emission.
- Simplify `ChatController` so it forwards streamed text chunks, preserves message-limit handling, sends `STREAM_START`/`STREAM_END`, and handles errors.
- Update `ProfileMcpTools` for `getSkills`, `getJobs`, `getCodeExamples`, `getRecentBlogs`, and `searchBlogs` to publish widgets when data exists while still returning data to the model.
- Update the chat system prompt so assistant prose briefly frames rendered cards without re-listing card details.

### Frontend Design

- Update `frontend/src/services/chatService.ts` to accept `TOOL_START`, `TOOL_END`, and `WIDGET`.
- Add `chatTypes.ts` for `ChatBlock`, widget payload types, and stream event types.
- Add `chatStreamReducer.ts` as the central state transition for assistant blocks.
- Refactor `ChatPanel` away from `streamingContent: string` into a current assistant message with ordered blocks.
- Add widget registry and cards for skills, employment, code examples, and blogs.
- Keep unknown widget kinds non-fatal by skipping the widget block while preserving adjacent text.
- Retire the `/code-examples/{id}` markdown-link drawer path as the primary code example experience for v1 widgets.
- Refresh `HomePage`, `HeroSection`, `AboutSection`, layout chrome, and `styles.css` against `designs/Landing Page.html`, `designs/chat.jsx`, and `designs/ui_kits/website/*`.

### Test Design

- Backend unit tests for `ChatStreamPublisher`, `ChatResponse`, `ChatService`, `ChatController`, and v1 tool widget emission.
- Frontend Vitest tests for `chatStreamReducer`, widget registry/cards, and `ChatPanel` event integration.
- Responsive and keyboard validation through the quickstart using the local frontend and backend.

## Constitution Check Post-Design

- **I. Monorepo with Separate Containers**: PASS. No container boundary changes.
- **II. Modern Java & React Stack**: PASS. No new framework or icon/styling library introduced.
- **III. Quality Gates**: PASS. Tests are planned for backend behavior, frontend reducer/UI behavior, and critical chat journeys.
- **IV. Observability & Operability**: PASS. Errors preserve user-facing fallback behavior and server logs.
- **V. Simplicity & Incremental Delivery**: PASS. Work is decomposed into independently testable user-story phases.
- **VI-IX**: PASS. No admin, tour data, backup, or shell-script violation.
