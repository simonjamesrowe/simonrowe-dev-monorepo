# Contract: Chat Stream Events

The backend publishes JSON events to `/topic/chat.{sessionId}`. The frontend sends visitor messages to `/app/chat.send` using the existing request shape:

```json
{
  "sessionId": "session-id",
  "message": "What Spring Boot and Kafka patterns does Simon use?"
}
```

## Base Event

Every event includes:

```json
{
  "sessionId": "session-id",
  "content": "",
  "type": "STREAM_START",
  "timestamp": "2026-05-30T12:00:00Z",
  "toolLabel": null,
  "widgetKind": null,
  "payload": null
}
```

## Event Types

### STREAM_START

Starts a new assistant response for the session.

Required:

- `sessionId`
- `type`
- `timestamp`

### STREAM_CHUNK

Carries streamed assistant text.

Required:

- `sessionId`
- `type`
- `timestamp`
- `content`

### TOOL_START

Shows a running tool indicator.

Required:

- `sessionId`
- `type`
- `timestamp`
- `toolLabel`

Example:

```json
{
  "sessionId": "session-id",
  "content": "",
  "type": "TOOL_START",
  "timestamp": "2026-05-30T12:00:01Z",
  "toolLabel": "Looking up Simon's skills",
  "widgetKind": null,
  "payload": null
}
```

### WIDGET

Shows a typed inline card.

Required:

- `sessionId`
- `type`
- `timestamp`
- `widgetKind`
- `payload`

Allowed `widgetKind` values for v1:

- `skills`
- `employment`
- `code`
- `blogs`

Example:

```json
{
  "sessionId": "session-id",
  "content": "",
  "type": "WIDGET",
  "timestamp": "2026-05-30T12:00:02Z",
  "toolLabel": null,
  "widgetKind": "skills",
  "payload": {
    "groups": [
      {
        "name": "Backend",
        "skills": [
          { "name": "Java", "rating": 9 },
          { "name": "Spring Boot", "rating": 9 }
        ]
      }
    ]
  }
}
```

### TOOL_END

Marks a tool indicator complete.

Required:

- `sessionId`
- `type`
- `timestamp`
- `toolLabel`

### STREAM_END

Finalizes the assistant response.

Required:

- `sessionId`
- `type`
- `timestamp`

`content` may contain a fallback final text snapshot, but the frontend should primarily rely on accumulated blocks.

### ERROR

Finalizes the assistant response with a user-safe error message.

Required:

- `sessionId`
- `type`
- `timestamp`
- `content`

## Ordering Rules

- `STREAM_START` precedes all events for one assistant response.
- Tool-emitted widget events should occur in the order `TOOL_START`, optional `WIDGET`, `TOOL_END`.
- `STREAM_CHUNK` events may appear before or after tool/widget events.
- `STREAM_END` or `ERROR` ends the active assistant response.
- `STREAM_RESET` is not part of the new contract.
