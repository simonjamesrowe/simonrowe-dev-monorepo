# Streaming Chat with Tool Indicators and Rich Widgets — Design

**Date:** 2026-05-30
**Status:** Approved (pending implementation plan)

## Summary

The site's AI chat currently *simulates* streaming: `ChatService.processMessage` makes a
blocking `chatClient...call()`, resolves all tool calls internally, and returns the full
answer as a single object that `ChatController` forwards as one large `STREAM_CHUNK`. Tool
activity is invisible to the user, and the only "rich" surface is a hack where the LLM is
prompted to emit a `/code-examples/{id}` markdown link that the frontend regex-matches to
open a drawer.

This redesign delivers three things:

1. **Real token streaming** — switch from `.call()` to `.stream()` so answers stream
   token-by-token.
2. **Visible tool activity** — each tool call surfaces a human-readable indicator
   (e.g. *"Looking up Simon's skills"*) that collapses into a quiet *"✓ Used N tools ▸"*
   expandable line once complete.
3. **Rich inline widgets** — tool results render as typed React cards inline in the chat
   flow (skills, employment history, code examples, blog posts).

## Decisions

These were settled during brainstorming and are fixed inputs to the plan:

- **Widget trigger model: automatic / tool-driven.** Every v1 tool result becomes a typed
  `WIDGET` event the frontend renders. The LLM does not decide whether to render a card —
  the tool result *is* the card.
- **Text/card relationship: card carries the data, text is a short framing sentence.** The
  system prompt steers the model to add brief context (e.g. *"Kafka and event-driven systems
  are where I go deepest"*) rather than re-listing data the card already shows.
- **Placement: cards inline, tools collapsed.** Cards render in the chat column in stream
  order. Tool activity folds into a quiet expandable line; a running tool shows a live
  spinner + label.
- **Emission mechanism: tools self-publish.** A small `ChatStreamPublisher` is injected into
  `ProfileMcpTools`; each v1 tool emits `TOOL_START → WIDGET → TOOL_END` using the
  `sessionId` already available via `ToolContext`, and still returns data to the LLM for the
  framing sentence. (Rejected: a central AOP/decorator interceptor — fragile `ToolContext`
  access under Reactor threading; inferring from Spring AI streamed tool metadata — no clean
  result payload.)
- **v1 widget scope: 4 tools.** `getSkills`, `getJobs`, `getCodeExamples`, and
  `getRecentBlogs`/`searchBlogs`. The remaining tools (`getProfile`, `searchNews`,
  `getUpcomingEvents`, `searchSite`, `submitContactForm`) stay as streamed text in v1 and can
  adopt the same mechanism later.
- **Transport unchanged.** Keep STOMP over WebSocket (`/ws/chat`, topic
  `/topic/chat.{sessionId}`). It already supports the bidirectional flow and reconnect logic.

## Architecture & Data Flow

```
User msg ──STOMP──▶ ChatController ──▶ ChatService.processMessage()
                                          │  chatClient.prompt()...stream()   ← REAL token streaming (was .call())
                                          ▼
        ┌──────────── streamed token chunks ──────────────┐
        │   model emits tool call → ProfileMcpTools.getSkills(ctx)
        │        ├─ publish TOOL_START {label:"Looking up Simon's skills"}
        │        ├─ query DB
        │        ├─ publish WIDGET {kind:"skills", payload:{...}}   (skipped if empty)
        │        ├─ publish TOOL_END
        │        └─ return data → model streams framing sentence
        ▼
   /topic/chat.{sessionId}  ──▶  frontend reducer builds ordered blocks
```

Tool execution runs on the streaming thread, so widgets emitted from inside a tool land
between the correct text chunks — giving correct stream ordering (indicator → card →
framing text) for free.

## Components

### Backend

- **`ChatService`** — replace the blocking `Flux.defer(() -> call())` with `.stream()`.
  Forward token chunks as `STREAM_CHUNK`, terminate with `STREAM_END`. Remove the
  `STREAM_RESET` / `toolCallSeen` tool-detection hack in `ChatController`, which exists only
  to work around the blocking call.
- **`ChatStreamPublisher`** (new) — thin wrapper over `SimpMessagingTemplate` that publishes
  typed events to `/topic/chat.{sessionId}`: `toolStart(sessionId, label)`,
  `widget(sessionId, kind, payload)`, `toolEnd(sessionId, label)`. Keeps the topic/serialization
  concern out of the tool methods.
- **`ProfileMcpTools`** — the 4 v1 tools gain widget emission. Each one: emits `TOOL_START`
  with its label, computes its result, emits `WIDGET` (only if the payload is non-empty),
  emits `TOOL_END`, and returns data to the LLM as today. `sessionId` comes from
  `ToolContext` (the pattern `submitContactForm` already uses).
- **`ChatResponse`** — extend with nullable `toolLabel`, `widgetKind`, and `payload` fields;
  add `TOOL_START`, `TOOL_END`, `WIDGET` to `MessageType`; remove `STREAM_RESET`.
- **Widget payload DTOs** — typed records serialized to JSON:
  - `skills`: `{ groups: [{ name, skills: [{ name, rating }] }] }`
  - `employment`: `{ jobs: [{ company, title, start, end, summary, skills: [...] }] }`
  - `code`: `{ examples: [{ id, title, description, language, code, skills: [...] }] }`
  - `blogs`: `{ posts: [{ id, title, summary, tags: [...], publishedDate, url }] }`
- **Tool labels** — a small constant map (tool → friendly label), e.g.
  `getSkills → "Looking up Simon's skills"`, `getJobs → "Pulling up employment history"`,
  `getCodeExamples → "Fetching code examples"`, `searchBlogs/getRecentBlogs → "Searching blog posts"`.
- **System prompt** — add: *"When you call the skills, jobs, code, or blog tools, the user
  already sees a visual card with the details. Add a brief framing sentence; do not re-list
  the data the card shows."*

### Frontend

- **Block model** — an assistant message becomes an ordered `Block[]` instead of a flat
  string:

  ```ts
  type Block =
    | { kind: 'text';   content: string }
    | { kind: 'widget'; widgetKind: string; payload: unknown }
    | { kind: 'tool';   label: string; status: 'running' | 'done' }
  ```

- **Stream reducer** (pure function) — folds incoming `ChatResponse` events into `Block[]`:
  - `STREAM_START` → start a new assistant message with empty blocks.
  - `STREAM_CHUNK` → append to the trailing `text` block, or start one if the last block is
    not text.
  - `TOOL_START` → push a `tool{running}` block; `TOOL_END` → flip the matching block to
    `done`.
  - `WIDGET` → push a `widget` block.
  - `STREAM_END` → finalize; `ERROR` → push an error text block and finalize.
- **Tool-activity rendering** — consecutive `tool` blocks collapse into one quiet
  *"✓ Used N tools ▸"* expandable line; a `running` tool shows the live spinner + its label.
- **Widget registry** — `{ skills, employment, code, blogs }` mapped to React components
  (`SkillsWidget`, `EmploymentWidget`, `CodeExampleWidget`, `BlogListWidget`). Unknown
  `widgetKind` is skipped gracefully.
- **`CodeExampleWidget`** renders inline with syntax highlighting. Requires a small new
  dependency (e.g. `react-syntax-highlighter`). The existing `/code-examples/{id}` markdown
  hack and `onCodeExampleClick`/`CodeExampleDrawer` flow are retired for the v1 tools
  (the drawer component may remain available but is no longer the primary surface).
- **`ChatPanel`** — `streamingContent: string` and `Message.content: string` become
  block-based; the `onMessage` handler delegates to the reducer. Existing concerns
  (stream timeout/finalize, reconnect, message limit, clear chat) are preserved, adapted to
  the block model.

## Error Handling & Edge Cases

- **Empty tool result** — tool emits `TOOL_END` only (no `WIDGET`); the model narrates the
  miss in text.
- **Multiple tools in one turn** — multiple `tool` blocks collapse into a single line listing
  each tool used.
- **Stream timeout / reconnect** — existing `STREAM_TIMEOUT_MS` finalize logic and STOMP
  reconnect are retained, adapted to block-based state.
- **Message limit** — unchanged (`MAX_MESSAGES_PER_SESSION` server-side,
  `MAX_USER_MESSAGES` client-side).
- **Unknown widget kind** — registry skips it; the framing text still renders.

## Testing

### Backend
- `ChatStreamPublisher` emits the correct event types/fields and ordering (mock
  `SimpMessagingTemplate`, assert `convertAndSend` payloads).
- Each v1 tool emits `TOOL_START → WIDGET → TOOL_END` with the correct kind/payload **and**
  still returns data to the LLM; empty results skip `WIDGET`.
- `ChatService` forwards `.stream()` chunks as `STREAM_CHUNK` and terminates with
  `STREAM_END` (mocked `ChatClient`).
- `ChatController` session-limit and overall flow still hold.

### Frontend (vitest)
- Stream reducer: feed representative event sequences and assert block structure (text
  appending, tool collapse, widget ordering, multi-tool turns).
- Widget registry: each widget renders its payload; unknown kind falls back gracefully.
- `ChatPanel` integration: simulate WS messages and assert the DOM shows
  spinner → card → framing text in order.

## Out of Scope (v1)

- Widgets for `getProfile`, `searchNews`, `getUpcomingEvents`, `searchSite`,
  `submitContactForm` (same mechanism, added later).
- Changing the transport away from STOMP/WebSocket.
- Persisting chat history or widgets beyond the existing in-memory session model.
