# Contract: Frontend Assistant Blocks

Assistant messages render as an ordered list of blocks.

## Text Block

```ts
{
  kind: 'text',
  content: string
}
```

Rules:

- Consecutive `STREAM_CHUNK` events append to the trailing text block.
- If the trailing block is not text, a new text block is created.
- Assistant markdown support remains available for trusted assistant text.

## Tool Block

```ts
{
  kind: 'tool',
  label: string,
  status: 'running' | 'done'
}
```

Rules:

- `TOOL_START` adds a running tool block.
- `TOOL_END` marks the most recent running block with the same label as done.
- Running tools show spinner and label.
- Completed consecutive tool blocks collapse into a quiet summary such as `Used 2 tools`.
- The summary can expand to show labels.

## Widget Block

```ts
{
  kind: 'widget',
  widgetKind: 'skills' | 'employment' | 'code' | 'blogs' | string,
  payload: unknown
}
```

Rules:

- Known widget kinds render through the widget registry.
- Unknown widget kinds are skipped without disrupting the surrounding text.
- Empty payloads should not render a card.

## Message State

```ts
{
  role: 'assistant' | 'user',
  blocks?: ChatBlock[],
  content?: string,
  timestamp: string,
  finalized: boolean
}
```

Rules:

- User messages can remain string content.
- Assistant messages should use blocks.
- Timeouts finalize the active assistant message using the blocks accumulated so far.
- Clearing chat resets messages, active stream state, and session id.
