# Contract: Chat Stream (STOMP) — reconcile behaviour

Transport unchanged. This contract fixes how the **client** interprets existing messages.

## Topic / destinations (unchanged)
- Client publishes: `/app/chat.send` with `{ sessionId, message }`.
- Client subscribes: `/topic/chat.{sessionId}`.

## Message sequence for one prompt
```
STREAM_START            { type, sessionId }
[ TOOL_START            { type, sessionId, toolLabel } ]
[ WIDGET                { type, sessionId, widgetKind, payload } ]
[ TOOL_END              { type, sessionId, toolLabel } ]
STREAM_CHUNK*           { type, sessionId, content }        # incremental prose (may be messy/interleaved)
STREAM_END              { type, sessionId, content }        # content == authoritative fullResponse
```
Tool/widget/chunk messages may interleave on the shared topic.

## Client contract (new/changed rules)
1. **Exactly one** assistant `ChatMessageModel` per `STREAM_START`. `STREAM_END` MUST NOT
   create a second message.
2. On `STREAM_END`, the client MUST reconcile the assistant message's **text** block(s) to
   `response.content` (the server `fullResponse`), preserving tool/widget blocks and order.
   - If `content` is empty/absent, keep the incrementally-built text.
3. The `initialQuery` MUST be sent to `/app/chat.send` **at most once** per drawer session,
   even across STOMP reconnects (guard with a "sent" flag).
4. Session guard: ignore any message whose `sessionId` ≠ current session (existing behaviour).

## Acceptance
- One bubble per prompt (FR-004); final text == server `fullResponse` (FR-005); tool/widget
  blocks preserved (FR-006); no reconnect double-send (FR-007).

## Test hooks
- `chatStreamReducer.test.ts`: STREAM_END with existing scrambled text blocks + a tool block
  → result has one clean text block equal to `fullResponse` and the tool block intact.
- `ChatPanel.test.tsx`: simulate `onConnect` firing twice (reconnect) → `sendMessage` called
  once for `initialQuery`.
