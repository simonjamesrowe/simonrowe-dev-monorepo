# Quickstart: Landing Chat Widgets

## Prerequisites

- Backend and frontend dependencies installed.
- `OPENAI_API_KEY` configured if running real model responses.
- Local backend dependencies available through the repo's normal Docker Compose flow.

## Backend Checks

Run targeted backend tests while implementing:

```bash
cd backend
../gradlew test --tests 'com.simonrowe.chat.*' --tests 'com.simonrowe.mcp.ProfileMcpToolsTest'
```

Expected behavior:

- `ChatService` streams chunks from the model client.
- `ChatController` emits `STREAM_START`, text chunks, `STREAM_END`, and `ERROR` without `STREAM_RESET`.
- `ChatStreamPublisher` sends `TOOL_START`, `WIDGET`, and `TOOL_END` to `/topic/chat.{sessionId}`.
- v1 MCP tools publish widgets for non-empty results and skip widgets for empty results.

## Frontend Checks

Run targeted frontend tests while implementing:

```bash
cd frontend
npm test -- chat
```

Expected behavior:

- Reducer appends streamed text into text blocks.
- Tool blocks enter running state and complete correctly.
- Widget blocks preserve stream order.
- Unknown widgets do not break rendering.
- ChatPanel handles timeout, reconnect, clear chat, message limit, and errors with block state.

## Manual Local Validation

Start the local app:

```bash
cd frontend
npm run dev
```

Open `http://localhost:5173`.

Validate:

- Homepage first viewport shows Simon Rowe, role, engineering positioning, and inline AI chat.
- Prompt chips can open or send chat questions.
- Asking about skills shows tool activity, a skills card, and brief framing text.
- Asking about jobs shows an employment card.
- Asking for code examples shows an inline code card with syntax highlighting.
- Asking about recent blogs shows blog cards with links.
- Empty/no-result responses do not leave loading indicators.
- Dark and light themes preserve contrast.
- Keyboard navigation reaches nav, theme toggle, chat prompts, composer, card links, CTAs, and footer links.
- Mobile, tablet, and desktop widths have no overlapping text or clipped controls.

## Full Quality Gates Before Merge

```bash
cd frontend
npm run build
npm test
npm run lint

cd ../backend
../gradlew test
```
