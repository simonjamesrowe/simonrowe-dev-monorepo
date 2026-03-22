# Implementation Plan: Profile Chat with MCP Tools

**Branch**: `009-profile-chat` | **Date**: 2026-02-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/009-profile-chat/spec.md`

## Summary

Add an AI-powered chat feature to the profile homepage, triggered by pressing Enter in the existing search bar. The chat uses Groq (via Spring AI's OpenAI-compatible client) for LLM responses, WebSocket (STOMP) for real-time streaming, and in-memory session management with UUID-based ephemeral conversations. Additionally, expose profile data as MCP tools using Spring AI's MCP Server WebMVC starter, enabling both the built-in chat and external AI agents to query profile, blog, job, and skills data. Rate limiting via Bucket4j protects all chat and MCP endpoints.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, Spring AI 1.1.2 (OpenAI starter for Groq, MCP Server WebMVC, Chat Memory), Spring WebSocket (STOMP), Bucket4j, React 19, @stomp/stompjs
**Storage**: MongoDB (existing — no new collections), In-memory `ConcurrentHashMap` for chat sessions
**Testing**: JUnit 5 + Testcontainers (backend), Vitest (frontend)
**Target Platform**: Linux container (GraalVM native image), Web browser
**Project Type**: Web application (monorepo: backend + frontend)
**Performance Goals**: Chat response streaming starts within 5 seconds, MCP tool responses within 2 seconds
**Constraints**: Ephemeral sessions only (no persistence), rate limited (20 chat msgs/min, 60 MCP reqs/min per IP)
**Scale/Scope**: Single-user portfolio site, low concurrent usage

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Backend and frontend remain separate containers. No new container introduced. |
| II. Modern Java & React Stack | PASS with justification | Java 21, Spring Boot 3.5.x, MongoDB, React 19. Spring AI 1.1.2 is a new dependency but aligns with Spring ecosystem. WebSocket is a Spring Boot starter. See Complexity Tracking for Spring AI addition. |
| III. Quality Gates | PASS | JaCoCo 80% coverage, Checkstyle, Testcontainers for integration tests. New code will follow existing patterns. |
| IV. Observability | PASS | New endpoints will be traced via OpenTelemetry. WebSocket connections can be instrumented. |
| V. Simplicity & YAGNI | PASS | No new database, no new external services beyond Groq API. In-memory chat memory is the simplest viable approach. Chat data is not persisted (per spec). |
| GraalVM Native Image | CAUTION | Spring AI 1.1.2 has native image support but MCP annotations may need reflection hints. Test early. |
| No CSS framework | PASS | Chat UI uses plain CSS with BEM conventions in styles.css. |
| Lucide React icons | PASS | Chat UI icons (send, close, message) use lucide-react. |
| bootBuildImage | PASS | No Dockerfile changes for backend. |

### Post-Design Re-check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo | PASS | No structural changes to container architecture. |
| II. Stack | PASS | Spring AI BOM 1.1.2 added to dependency management. WebSocket starter added. Bucket4j added. All align with Spring ecosystem. |
| III. Quality Gates | PASS | All new classes will have tests. MCP tools tested via integration tests. |
| IV. Observability | PASS | `@WithSpan` on chat service methods. |
| V. Simplicity | PASS | Minimal new abstractions. Tools delegate to existing services. |

## Project Structure

### Documentation (this feature)

```text
specs/009-profile-chat/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── chat-api.yml     # WebSocket message contracts
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── chat/
│   │   ├── ChatController.java            # WebSocket STOMP controller
│   │   ├── ChatService.java               # Orchestrates ChatClient, memory, tools
│   │   ├── ChatMessage.java               # Chat message record (role, content, timestamp)
│   │   ├── ChatRequest.java               # Inbound WebSocket message record
│   │   ├── ChatResponse.java              # Outbound WebSocket message record
│   │   ├── ChatConfig.java                # Spring AI ChatClient bean config
│   │   └── ChatSessionCleanupService.java # Scheduled stale session eviction
│   ├── mcp/
│   │   └── ProfileMcpTools.java           # @McpTool annotated methods for profile data
│   ├── ratelimit/
│   │   ├── RateLimitInterceptor.java      # HandlerInterceptor with Bucket4j
│   │   └── RateLimitConfig.java           # Rate limit configuration properties
│   └── WebSocketConfig.java               # @EnableWebSocketMessageBroker config
├── src/main/resources/
│   └── application.yml                    # Spring AI, WebSocket, rate limit config
└── src/test/java/com/simonrowe/
    ├── chat/
    │   ├── ChatControllerTest.java        # WebSocket integration test
    │   └── ChatServiceTest.java           # Unit test with mocked ChatClient
    ├── mcp/
    │   └── ProfileMcpToolsTest.java       # MCP tool integration test
    └── ratelimit/
        └── RateLimitInterceptorTest.java  # Rate limit behaviour test

frontend/
├── src/
│   ├── components/
│   │   ├── search/
│   │   │   └── SiteSearch.tsx             # Modified: add Enter key → onChatStart callback
│   │   └── chat/
│   │       ├── ChatPanel.tsx              # Main chat panel component
│   │       ├── ChatMessage.tsx            # Individual message bubble
│   │       ├── ChatInput.tsx              # Message input with send button
│   │       └── ChatTypingIndicator.tsx    # Typing/loading indicator
│   └── services/
│       └── chatService.ts                 # STOMP WebSocket client
└── src/__tests__/
    └── components/chat/
        └── ChatPanel.test.tsx             # Chat panel unit tests
```

**Structure Decision**: Web application (Option 2). New `chat/`, `mcp/`, and `ratelimit/` packages follow the existing domain-per-package convention (like `blog/`, `profile/`, `contact/`). Frontend chat components live under `components/chat/` following the established pattern.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Spring AI 1.1.2 (new dependency) | Required for LLM integration (Groq), chat memory, and MCP tool exposure — all core to this feature | Building custom LLM client, memory management, and MCP protocol handling would be significantly more code and maintenance |
| WebSocket (new transport) | User requirement for real-time streaming chat; REST polling would degrade UX | SSE is unidirectional — chat needs bidirectional communication for sending messages and receiving streamed responses |
| Bucket4j (new dependency) | Rate limiting is required per spec; no existing rate limiting in the project | Custom token bucket: Bucket4j is well-tested, lightweight (single jar, no external deps), and avoids reinventing concurrency primitives |
