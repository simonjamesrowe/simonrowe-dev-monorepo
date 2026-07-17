# Quickstart: Public MCP Tools Page

How to run and verify the `/mcp` page locally.

## 1. Enable Streamable-HTTP on the backend

In `backend/src/main/resources/application.yml`, under `spring.ai.mcp.server`:

```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC
        protocol: STREAMABLE      # <-- add this line
        annotation-scanner:
          enabled: true
```

Restart the backend (`./scripts/start-backend.sh`).

## 2. Verify the handshake against the live server (do this BEFORE finalizing the client)

```bash
# initialize — note the Mcp-Session-Id response header
curl -si -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0.0.1"}}}'

# reuse SID=<Mcp-Session-Id from above>
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Confirm: `initialize` returns an `Mcp-Session-Id` header, and `tools/list` returns 10 tools. Note whether each response is `application/json` or `text/event-stream` and adjust `mcpClient.ts` parsing accordingly.

## 3. Run the frontend

```bash
./scripts/start-frontend.sh        # or: cd frontend && npm run dev
```

Open http://localhost:5173/mcp. The Vite dev proxy forwards `/mcp` → `localhost:8080`.

## 4. Manual verification checklist

- [ ] Page shows a loading state, then one card per tool (10 today).
- [ ] Each card shows name, description, and parameters (required marked).
- [ ] A read-only tool (e.g. `searchBlogs`) generates a form; Run shows a formatted result.
- [ ] `enum` param renders a `<select>`; `boolean` param renders a checkbox.
- [ ] `submitContactForm` shows docs + "not runnable here" badge, **no form**.
- [ ] Failing one tool's call shows a per-card error; other cards still work.
- [ ] Stop the backend and reload → single page-level error, no broken layout.
- [ ] "Connect your client" section shows Claude Code / Codex / Gemini snippets with `https://api.simonrowe.dev/mcp`; copy buttons work.
- [ ] "MCP" appears in desktop TopNav and mobile menu, navigates to `/mcp`.

## 5. Run the tests

```bash
cd frontend && npm test
```

Covers: `McpPage.test.tsx` (cards render from mocked `tools/list`; page error on failed connect), `ToolCard.test.tsx` (schema→form for text/enum/boolean; Run invokes `callTool` + renders result; denylisted tool shows badge, no form), `ConnectInstructions.test.tsx` (per-client snippets contain the MCP URL).

## 6. Production note

`nginx.conf` gains a `location /mcp` with `proxy_buffering off` so SSE-framed `tools/call` responses stream through. External clients use `https://api.simonrowe.dev/mcp` (already routed by the prod nginx-proxy), independent of this frontend proxy.
