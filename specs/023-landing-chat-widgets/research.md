# Research: Landing Chat Widgets

## Decision: Keep STOMP/WebSocket Transport

**Rationale**: The existing app already connects to `/ws/chat`, subscribes to `/topic/chat.{sessionId}`, proxies WebSocket traffic through Vite/Nginx, and handles reconnects through `@stomp/stompjs`. Extending event types preserves production routing and avoids introducing a second realtime stack.

**Alternatives considered**:

- Server-sent events: simpler one-way streaming, but would require a new transport and reconnect path.
- Fetch streaming: useful for tokens, but does not fit the existing bidirectional STOMP chat flow or tool-published side events as cleanly.

## Decision: Stream from Chat Service Instead of Blocking

**Rationale**: `ChatService.processMessage` currently calls the model and returns a complete response. Moving to the streaming response path gives visible progress for normal text and removes the need for the controller's `STREAM_RESET` tool-call workaround.

**Alternatives considered**:

- Simulated character streaming from the final response: does not satisfy the requirement for real progress or correct tool/card ordering.
- Keep blocking calls and only add tool indicators: would leave the main user-visible wait unresolved.

## Decision: Tools Self-Publish Widget Events

**Rationale**: `ProfileMcpTools.submitContactForm` already uses `ToolContext` to access `sessionId`, so v1 tools can publish events in the same execution context where results are known. This preserves stream order: activity label, result widget, then model framing text.

**Alternatives considered**:

- Central interceptor around all tools: higher risk because tool context and result payload extraction are harder to guarantee across Reactor and Spring AI internals.
- Infer widgets from streamed model/tool metadata: does not provide a stable typed result payload for the frontend.

## Decision: Use Typed Widget Payload Records on the Backend

**Rationale**: Typed payloads make contract tests and frontend rendering predictable while keeping the model's text answer separate from the card data. The first release only needs skills, employment, code, and blog payloads.

**Alternatives considered**:

- Send raw repository/service results as widget payloads: lower effort but leaks backend shapes and can make frontend cards brittle.
- Ask the model to output JSON: gives the model too much responsibility for display data and increases hallucination risk.

## Decision: Use a Pure Frontend Stream Reducer

**Rationale**: The chat UI has several event transitions: streaming text, running tools, completed tools, widgets, errors, timeouts, and finalization. A pure reducer makes these transitions testable without a live socket or model.

**Alternatives considered**:

- Keep concatenating a single streaming string: cannot represent interleaved cards and tool indicators.
- Manage each event directly in `ChatPanel`: works initially but makes timeout and multi-tool behavior harder to test.

## Decision: Port Design Intent, Not Prototype Runtime

**Rationale**: The design handoff is high-fidelity, but its HTML/JSX uses inline UMD React, Babel, and prototype conventions. The app must keep React 19, TypeScript, routing, `lucide-react`, and the canonical `styles.css` token/BEM approach.

**Alternatives considered**:

- Copy prototype files into production: conflicts with the app stack and would duplicate styling/runtime patterns.
- Refresh only chat and leave landing visuals untouched: misses the user-requested updated landing page design.

## Decision: No New Public Chat Persistence

**Rationale**: The constitution requires in-memory chat sessions unless a concrete recovery requirement exists. The feature only needs current-session rendering and existing limits.

**Alternatives considered**:

- Persist conversation blocks to MongoDB: adds privacy and lifecycle complexity without a stated user need.
