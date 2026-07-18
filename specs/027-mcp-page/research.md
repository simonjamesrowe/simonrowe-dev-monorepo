# Research: Public MCP Tools Page

**Phase 0 output** — resolves the design's flagged risks (transport handshake + client-config accuracy) against the live system and current docs.

## Decision 1 — MCP transport: enable Streamable-HTTP on the backend

**Decision**: Add `spring.ai.mcp.server.protocol: STREAMABLE` under `spring.ai.mcp.server` in `backend/src/main/resources/application.yml`. This exposes the Streamable-HTTP endpoint at `/mcp`. The frontend client speaks Streamable-HTTP.

**Evidence gathered** (live probe of the running backend on `localhost:8080`, 2026-07-17):
- `POST /mcp` (initialize) → **404** `NoResourceFoundException: No static resource mcp` — nothing mapped at `/mcp`.
- `GET /sse` → **200**; `GET /mcp/message` → 404 (needs POST + session). The server is running the **SSE transport**.
- Config metadata from `spring-ai-autoconfigure-mcp-server-common-1.1.4.jar`:
  - `spring.ai.mcp.server.protocol` — **default `sse`**, type `ServerProtocol` enum.
  - `sse-endpoint` default `/sse`, `sse-message-endpoint` default `/mcp/message`.
  - `streamable-http.mcp-endpoint` default `/mcp`.
  - Autoconfig classes present: `McpServerSseWebMvcAutoConfiguration`, `McpServerStreamableHttpWebMvcAutoConfiguration`, `McpServerStatelessWebMvcAutoConfiguration` (protocol selects which activates).

**Rationale**:
- The design's premise ("Streamable-HTTP transport at `/mcp`") was **incorrect for the current config** — the default is SSE. This was the design's explicit #1 risk ("verify against the running server early"). Verified: false.
- The chat consumes the tools **in-process** — `ChatConfig.chatClient(...)` calls `.defaultTools(profileMcpTools)` (`backend/src/main/java/com/simonrowe/chat/ChatConfig.java:48`). No backend MCP *client* exists; nothing internal depends on the HTTP transport. Switching SSE→Streamable-HTTP has **zero impact on chat**.
- Streamable-HTTP is the modern, recommended MCP transport; SSE transport is deprecated (confirmed in Claude Code docs, early 2026). It keeps the frontend client simple (stateless request/response with a session header) and makes the connect-your-client snippets (`--transport http`, `httpUrl`, Codex `url`) correct for external clients.
- The change is a **single YAML line**, not Java code and not a new endpoint — a config toggle of the constitution-mandated `spring-ai-starter-mcp-server-webmvc`.

**Deviation from spec non-goal**: The design listed "no backend change" as a non-goal. This one-line config change was surfaced to the user and **explicitly approved** (choose Streamable-HTTP). Documented in plan.md Constitution Check.

**Alternatives considered**:
- *Frontend speaks SSE transport (zero backend change)*: rejected by user. Would require a persistent SSE connection, endpoint-discovery event handling, and response correlation by id across the stream — materially more complex — AND would force the connect-your-client snippets to use the deprecated `--transport sse` / `/sse` URL, which also wouldn't match the design's promised HTTP snippets.
- *Add a bespoke REST introspection endpoint*: rejected — explicit non-goal, new Java code, and defeats the "genuine MCP client" goal.

## Decision 2 — Streamable-HTTP handshake the client implements

**Decision**: The client performs a JSON-RPC 2.0 handshake over `POST /mcp`, tracking the session id header:

1. **initialize** — `POST /mcp` with body `{jsonrpc, id, method:"initialize", params:{protocolVersion, capabilities:{}, clientInfo:{name,version}}}`. Capture the **`Mcp-Session-Id`** response header (case-insensitive read via `Headers.get`).
2. **notifications/initialized** — `POST /mcp` with the `Mcp-Session-Id` header and body `{jsonrpc, method:"notifications/initialized"}` (a notification: no `id`, no response body expected).
3. **tools/list** — `POST /mcp` with the session header → `result.tools: McpTool[]`.
4. **tools/call** — `POST /mcp` with `params:{name, arguments}` → `result` (a `ToolResult` with `content[]` and optional `isError`).

**Required request headers** (every call): `Content-Type: application/json` and `Accept: application/json, text/event-stream`.

**Response parsing**: read the `Content-Type`. If `application/json` → `JSON.parse(bodyText)`. If `text/event-stream` → the body is one or more SSE frames; extract the `data:` line(s) and `JSON.parse` the concatenated payload. Bodies are short single request/response payloads, so **read full text** (`await res.text()`) rather than incrementally streaming.

**Rationale**: Matches the MCP Streamable-HTTP spec and Spring AI's `WebMvcStreamableServerTransportProvider`. Handling both content types is required because Spring AI may answer a POST with either a JSON body or a single SSE frame.

**Verification status**: The exact framing (session-id header casing, JSON-vs-SSE for each method) is confirmed from the spec + Spring AI metadata but **NOT yet curled against a live `/mcp`** (the running backend still has the SSE default and is possibly owned by another workspace — not restarted to avoid disruption). → **First implementation task after the config change**: restart the local backend and curl the four calls, capturing exact headers, before finishing `mcpClient.ts`. (Design's stated mitigation.)

**Alternatives considered**: `EventSource`/streaming reader — rejected; responses are short, `res.text()` is simpler and works for both content types.

## Decision 3 — Destructive-tool gating (frontend denylist)

**Decision**: A one-line constant `const DENYLISTED_TOOLS = ['submitContactForm']` in the frontend. Denylisted tools render full docs but no run form — a "not runnable here" badge instead.

**Rationale**: `submitContactForm` (`ProfileMcpTools.java:373`) sends email; a public run form is an email-spam vector. No backend change means gating must live client-side. The other 9 tools (`getProfile`, `searchBlogs`, `getJobs`, `getSkills`, `getRecentBlogs`, `searchSite`, `getCodeExamples`, `searchNews`, `getUpcomingEvents`) are read-only and safe. Existing 60 req/min rate limiter on `/mcp/**` (`WebConfig`/`RateLimitInterceptor`) is the backstop.

**Accepted trade-off**: a future destructive tool must be added to this constant. All read-only tools remain fully automatic.

## Decision 4 — Connect-your-client snippets (verified against current docs, July 2026)

External clients target the already-public prod endpoint `https://api.simonrowe.dev/mcp` (prod nginx routes `api.simonrowe.dev → backend:8080`), independent of the frontend `/mcp` proxy.

- **Claude Code** (verified — HTTP is the recommended transport; SSE deprecated):
  ```
  claude mcp add --transport http simonrowe-dev https://api.simonrowe.dev/mcp
  ```
- **Gemini CLI** (`~/.gemini/settings.json`) — uses `httpUrl` for streamable-HTTP endpoints:
  ```json
  { "mcpServers": { "simonrowe-dev": { "httpUrl": "https://api.simonrowe.dev/mcp" } } }
  ```
- **Codex CLI** (`~/.codex/config.toml`) — now supports **native** Streamable-HTTP via `url` (supersedes the design's `mcp-remote` bridge):
  ```toml
  [mcp_servers.simonrowe-dev]
  url = "https://api.simonrowe.dev/mcp"
  ```
  On older Codex versions that only pick up stdio servers, add `experimental_use_rmcp_client = true` at the top of the file (or upgrade Codex).

**Rationale**: All three now support remote HTTP MCP natively; no bridge needed. The server is unauthenticated, so no `--header`/`bearer_token` is required.

**Sources**:
- [Connect Claude Code to tools via MCP — Claude Code Docs](https://code.claude.com/docs/en/mcp)
- [MCP servers with the Gemini CLI — GitHub docs](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md)
- [Model Context Protocol — OpenAI Codex docs](https://developers.openai.com/codex/mcp)

## Decision 5 — Frontend integration patterns (verified in-repo)

Confirmed conventions to reuse (no new patterns introduced):
- **Route**: `named(() => import('./pages/McpPage'), 'McpPage')` lazy import + `<Route element={<PublicLayout><McpPage/></PublicLayout>} path="/mcp" />` in `App.tsx`.
- **Page effect**: `useEffect(() => { trackPageView('/mcp'); document.title = 'MCP Tools' }, [])` (mirrors `NewsEventsPage.tsx`).
- **States**: reuse `LoadingIndicator` (`message` prop) and `ErrorMessage` (`message`, optional `onRetry`) from `components/common/`.
- **Same-origin**: `API_BASE_URL` from `config/api.ts` (default `''`). Client posts to `` `${API_BASE_URL}/mcp` ``.
- **Nav**: add `NavLink to="/mcp"` in `TopNav.tsx` links and a `{ label: 'MCP', to: '/mcp' }` entry in `MobileMenu.tsx` `navItems`.
- **Proxy**: add `/mcp` to `vite.config.ts` `server.proxy` (target `http://localhost:8080`, `changeOrigin: true`); add a `location /mcp` in `nginx.conf` proxying to `http://backend:8080/mcp` with `proxy_http_version 1.1` and `proxy_buffering off` (SSE-framed responses must not be buffered), plus the standard forwarded headers used by the other locations.
- **Styles**: new `.mcp-page` / `.mcp-page__*` / `.mcp-tool-card` BEM block in the single `styles.css`.
- **Tests**: colocated `*.test.tsx` with Vitest + Testing Library; `src/test/setup.ts` provides `@testing-library/jest-dom`.

## Open items for implementation

1. **Live handshake curl against `/mcp`** after the config change (Decision 2) — capture exact session-id header casing and per-method content-type before finalizing `mcpClient.ts`.
2. Confirm nginx `proxy_buffering off` on `location /mcp` actually passes an SSE-framed `tools/call` response (Decision 1/2) — verify in prod-like run.
