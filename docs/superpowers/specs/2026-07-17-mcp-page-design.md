# MCP Page — Design

**Date:** 2026-07-17
**Status:** Approved (pending implementation plan)

## Summary

Add a public `/mcp` page to the frontend that documents every tool exposed by the
site's Model Context Protocol (MCP) server and provides an interactive test harness
to execute them. The page is a genuine MCP client: it performs the Streamable-HTTP
handshake against the site's own `/mcp` endpoint and renders its tool catalogue from
the live `tools/list` response. Adding a new `@Tool` in the backend surfaces it on the
page automatically, with **zero frontend changes**.

## Goals

- A public page listing every MCP tool with its name, description, and parameters.
- An interactive harness to execute tools and view their results.
- Fully dynamic: new backend tools appear without editing the page.
- No abuse surface for destructive tools on a public page.
- Copy-paste setup instructions for connecting popular MCP clients (Claude Code,
  Codex CLI, Gemini CLI) to the server.

## Non-Goals

- No new backend Java code or REST introspection endpoint (the page speaks the MCP
  protocol directly).
- No authentication on the page (it stays public, like the rest of the site).
- No changes to the MCP tools themselves.

## Context (current state)

- **Backend MCP server:** Spring AI 1.1.4 `spring-ai-starter-mcp-server-webmvc`,
  `type: SYNC`, annotation scanner enabled
  (`backend/src/main/resources/application.yml:99-103`). Streamable-HTTP transport at
  the `/mcp` endpoint (no SSE-only / stdio transport).
- **Tools:** 10 `@Tool` methods in
  `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — `getProfile`,
  `searchBlogs`, `getJobs`, `getSkills`, `getRecentBlogs`, `searchSite`,
  `getCodeExamples`, `searchNews`, `getUpcomingEvents`, `submitContactForm`.
- **Auth:** `/mcp` is unauthenticated (`SecurityConfig.java` — only `/api/admin/**`
  requires `DEV_PORTAL_ADMIN`), protected by a 60 req/min rate limiter applied to
  `/mcp/**` (`WebConfig.java:64`, `RateLimitInterceptor.java`).
- **CORS:** registered on `/**` (`WebConfig.java:39-48`); allowed origins from
  `CORS_ALLOWED_ORIGINS` (dev default `http://localhost:5173`). Not exercised here
  because the page calls `/mcp` same-origin through the proxy.
- **Frontend:** React Router v7 (declarative `<Routes>` in `src/App.tsx`), public pages
  in `src/pages/` under `PublicLayout`, single `src/styles.css` (BEM), per-domain
  service files in `src/services/`. `API_BASE_URL` defaults to `''` (same-origin).
  Model page: `src/pages/NewsPage.tsx`; model service: `src/services/newsApi.ts`.
- **Routing to backend:** Vite dev proxy (`vite.config.ts`) and prod frontend nginx
  (`frontend/nginx.conf`) proxy `/api`, `/ws`, `/uploads` to `backend:8080` — but
  **not `/mcp`**. This must be added.

## Architecture

A public `/mcp` route rendering `McpPage`. On mount the page uses a protocol client to:

1. `initialize` (POST `/mcp`, JSON-RPC) — capture the `Mcp-Session-Id` response header.
2. `notifications/initialized` — POST with the session header.
3. `tools/list` — retrieve the tool catalogue (name, description, `inputSchema`).

It then renders one card per tool. Each card generates a form from the tool's JSON
schema and runs `tools/call` on demand, displaying the returned content.

Because the catalogue is fetched at runtime, new `@Tool` methods appear with no
frontend edits.

## Components

Kept small and independently testable:

- **`src/services/mcpClient.ts`** — the MCP Streamable-HTTP client. Manages JSON-RPC
  request/response, the `Mcp-Session-Id` header lifecycle, the required
  `Accept: application/json, text/event-stream` header, and parsing responses that come
  back as either `application/json` or `text/event-stream` (SSE — extract `data:` lines
  and `JSON.parse`). Responses are short request/response bodies, so read full text
  rather than streaming. Exposes `connect()` (initialize + initialized), `listTools()`,
  and `callTool(name, args)`. Pure TypeScript, no React, mockable in tests.
- **`src/types/mcp.ts`** — `McpTool`, JSON-schema param types (`McpJsonSchema`,
  property `type` / `enum` / `description` / `required`), `ToolResult`, JSON-RPC
  envelope types.
- **`src/pages/McpPage.tsx`** — orchestration: connect → list, `loading`/`error`/`data`
  states (reusing `LoadingIndicator` and `ErrorMessage`), `trackPageView()` +
  `document.title` in an effect (per `NewsPage` pattern), the "Connect your client"
  section (see below), and the grid of tool cards.
- **`src/components/mcp/ConnectInstructions.tsx`** — the "Connect your client" section:
  shows the public MCP URL and a copy-paste snippet per client (Claude Code, Codex CLI,
  Gemini CLI), each with a copy button. Static boilerplate parameterised only by the MCP
  URL, so it needs no change when tools are added.
- **`src/components/mcp/ToolCard.tsx`** — renders one tool's name/description/params and
  a **dynamically generated form** from its JSON schema: text input by default,
  `enum` → `<select>`, `boolean` → checkbox; marks required fields. Includes a Run
  button, per-card running/error state, and a formatted result panel (pretty-printed
  JSON / text content). For denylisted tools, renders a "not runnable here" badge in
  place of the form.

## Destructive-tool gating

Because there is no backend change, gating lives in the frontend as a small denylist
constant (initially `['submitContactForm']`). Denylisted tools still show their full
docs but display a "not runnable here" badge instead of a Run form, preventing public
abuse (e.g. email spam via `submitContactForm`). The page otherwise relies on the
existing 60 req/min rate limiter on `/mcp/**`.

**Accepted trade-off:** adding a *new* destructive tool later requires adding its name
to this one-line denylist. All non-destructive tools remain fully automatic.

## Connect-your-client instructions

The page includes a "Connect your client" section with a copy-paste snippet per client.
External clients point at the already-live public endpoint `https://api.simonrowe.dev/mcp`
(the prod nginx-proxy already routes `api.simonrowe.dev → backend:8080`, so this works
today independent of the frontend `/mcp` proxy). Planned content:

- **Claude Code:**
  ```
  claude mcp add --transport http simonrowe-dev https://api.simonrowe.dev/mcp
  ```
- **Gemini CLI** (`~/.gemini/settings.json`):
  ```json
  { "mcpServers": { "simonrowe-dev": { "httpUrl": "https://api.simonrowe.dev/mcp" } } }
  ```
- **Codex CLI** (`~/.codex/config.toml`), via the `mcp-remote` bridge for HTTP transport:
  ```toml
  [mcp_servers.simonrowe-dev]
  command = "npx"
  args = ["-y", "mcp-remote", "https://api.simonrowe.dev/mcp"]
  ```

The exact flags/fields for all three CLIs must be **verified against current docs at
implementation time** — these tools change their MCP configuration surface frequently.

## Wiring

- **Route:** lazy-loaded `/mcp` route in `src/App.tsx` under `PublicLayout`
  (alongside the existing public routes; use the `named()` lazy-import helper).
- **Nav:** add an "MCP" link to `src/components/layout/TopNav.tsx` (desktop) and
  `src/components/layout/MobileMenu.tsx` (mobile).
- **Styles:** a new `.mcp-page` / `.mcp-page__*` / `.mcp-tool-card` BEM block in
  `src/styles.css`.

## Proxy config (one-time infra)

- **`frontend/vite.config.ts`** — add `/mcp` to the dev `server.proxy` map, target
  `http://localhost:8080`, `changeOrigin: true`.
- **`frontend/nginx.conf`** — add a `location /mcp` proxying to `http://backend:8080/mcp`
  with SSE-friendly settings: `proxy_http_version 1.1`, `proxy_buffering off`, and the
  standard forwarded headers used by the other locations.

These are one-time changes; they do not need revisiting when tools are added.

## Data flow

```
McpPage mount
  → mcpClient.connect()        POST /mcp initialize        → Mcp-Session-Id
                               POST /mcp notifications/initialized
  → mcpClient.listTools()      POST /mcp tools/list         → McpTool[]
  → render ToolCard[] (denylist applied)

ToolCard "Run"
  → mcpClient.callTool(name, args)  POST /mcp tools/call    → ToolResult
  → render result panel
```

## Error handling

- Failed connect / list → page-level `ErrorMessage` (reuse existing component).
- Failed `tools/call` → per-card error state, other cards unaffected.
- SSE vs JSON content-type handled transparently in `mcpClient`.

## Testing (Vitest, colocated)

- `src/pages/McpPage.test.tsx` — mock `mcpClient`; assert cards render from a mocked
  `tools/list`; assert page-level error state on a failed connect.
- `src/components/mcp/ToolCard.test.tsx` — assert form generated from schema
  (text/enum/boolean); Run invokes `callTool` and renders the result; a denylisted tool
  shows the badge and no form.
- `src/components/mcp/ConnectInstructions.test.tsx` — assert the per-client snippets
  render and contain the MCP URL.

## Risks

- **Exact Streamable-HTTP handshake of Spring AI 1.1.4** (required headers, session-id
  header casing, SSE framing). Mitigation: verify against the running server early
  (curl the handshake) before building the client, rather than trusting assumptions.
- **SSE through nginx** requires `proxy_buffering off`; verified as part of the nginx
  change.
