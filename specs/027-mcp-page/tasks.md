# Tasks: Public MCP Tools Page

**Input**: Design documents from `/specs/027-mcp-page/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/mcp-streamable-http.md, quickstart.md

**Tests**: INCLUDED — the spec's Testing section explicitly requests colocated Vitest tests (`McpPage.test.tsx`, `ToolCard.test.tsx`, `ConnectInstructions.test.tsx`).

**Organization**: Grouped by user story (US1 catalogue, US2 run+gating, US3 connect) so each is an independently testable increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish have no story label)

## Path Conventions

Web app: backend config in `backend/`, all frontend code under `frontend/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Enable `/mcp` transport and same-origin routing — nothing can connect without these.

- [X] T001 Add `protocol: STREAMABLE` under `spring.ai.mcp.server` in `backend/src/main/resources/application.yml` (keep existing `type: SYNC` and `annotation-scanner.enabled: true`).
- [X] T002 (VERIFIED LIVE: initialize 200 + Mcp-Session-Id; notifications/initialized 202; tools/list=10; tools/call getProfile OK) Restart the local backend (`./scripts/start-backend.sh`) and verify the Streamable-HTTP handshake per `quickstart.md` §2: curl `initialize` (capture `Mcp-Session-Id` header), `notifications/initialized`, and `tools/list` (expect 10 tools) against `http://localhost:8080/mcp`. Record the exact session-id header casing and each response's `Content-Type` (`application/json` vs `text/event-stream`) for T007.
- [X] T003 [P] Add a `/mcp` entry to `server.proxy` in `frontend/vite.config.ts` (target `http://localhost:8080`, `changeOrigin: true`), mirroring the existing `/api` entry.
- [X] T004 [P] Add a `location /mcp` block to `frontend/nginx.conf` proxying to `http://backend:8080/mcp` with `proxy_http_version 1.1`, `proxy_buffering off`, and the standard forwarded headers used by the `/api`/`/ws` locations.

**Checkpoint**: `/mcp` reachable same-origin in dev; handshake behaviour confirmed against the live server.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Protocol types, the MCP client, and page/route/nav scaffolding that all user stories build on.

**⚠️ CRITICAL**: Complete before any user-story phase.

- [X] T005 [P] Create `frontend/src/types/mcp.ts` with the shapes from data-model.md: `JsonRpcRequest`/`JsonRpcResponse`/`JsonRpcError`, `McpTool`, `McpJsonSchema`, `McpJsonSchemaProperty`, `ToolResult`, `ToolResultContent`.
- [X] T006 [P] Add `DENYLISTED_TOOLS = ['submitContactForm']` and `PUBLIC_MCP_URL = 'https://api.simonrowe.dev/mcp'` constants (e.g. in `frontend/src/services/mcpClient.ts` or a small `frontend/src/config/mcp.ts`), exported for reuse by page/components.
- [X] T007 Implement `frontend/src/services/mcpClient.ts` — pure TS, no React: `connect()` (POST `initialize`, capture `Mcp-Session-Id`, POST `notifications/initialized`), `listTools()` (POST `tools/list` → `McpTool[]`), `callTool(name, args)` (POST `tools/call` → `ToolResult`). Send `Content-Type: application/json` + `Accept: application/json, text/event-stream` on every call; parse both `application/json` and `text/event-stream` (`data:` line) responses via `res.text()`; throw `Error(error.message)` on JSON-RPC errors. Post to `` `${API_BASE_URL}/mcp` `` using `API_BASE_URL` from `config/api.ts`. Match the exact framing recorded in T002.
- [X] T008 Create `frontend/src/pages/McpPage.tsx` scaffold: `useEffect` calling `trackPageView('/mcp')` + `document.title = 'MCP Tools'` (mirror `NewsEventsPage`); `loading`/`error`/`tools` state; on mount `connect()` → `listTools()`; render `LoadingIndicator` while loading and `ErrorMessage` on failure (page-level). Leave the tool grid / connect section as placeholders for US1/US3.
- [X] T009 Wire the lazy route in `frontend/src/App.tsx`: `const McpPage = named(() => import('./pages/McpPage'), 'McpPage')` and `<Route element={<PublicLayout><McpPage /></PublicLayout>} path="/mcp" />`.
- [X] T010 [P] Add an "MCP" `NavLink to="/mcp"` to the links group in `frontend/src/components/layout/TopNav.tsx`.
- [X] T011 [P] Add `{ label: 'MCP', to: '/mcp' }` to `navItems` in `frontend/src/components/layout/MobileMenu.tsx`.
- [X] T012 [P] Add the base `.mcp-page` / `.mcp-page__*` BEM block (page header, layout grid container) to `frontend/src/styles.css` using existing CSS custom properties.

**Checkpoint**: `/mcp` route renders, connects, lists tools (raw), and reachable from nav — user stories can proceed.

---

## Phase 3: User Story 1 — Discover the available MCP tools (Priority: P1) 🎯 MVP

**Goal**: One card per live tool showing name, description, and parameters; page-level error if the catalogue can't load.

**Independent Test**: Load `/mcp` against a server/mock exposing N tools → exactly N cards with correct name/description/params; kill the server → single page-level error.

- [X] T013 [US1] Create `frontend/src/components/mcp/ToolCard.tsx` (docs-only for this story): render tool name, description, and a parameter list from `inputSchema.properties` — each param's name, type, description, and a required marker for names in `inputSchema.required`. No form/run yet.
- [X] T014 [US1] Render the tool grid in `frontend/src/pages/McpPage.tsx`: map `tools` → `<ToolCard>` per tool. Handle the empty-catalogue case (valid empty grid, no crash).
- [X] T015 [P] [US1] Add `.mcp-tool-card` / `.mcp-tool-card__*` BEM styles (card, param list, required marker) to `frontend/src/styles.css`.
- [X] T016 [US1] Create `frontend/src/pages/McpPage.test.tsx`: mock `mcpClient`; assert N cards render from a mocked `tools/list`; assert page-level `ErrorMessage` on a failed `connect()`.

**Checkpoint**: MVP — the page is a working live tool catalogue.

---

## Phase 4: User Story 2 — Run a tool and see its result (Priority: P2)

**Goal**: Schema-driven form per runnable tool, execute via `callTool`, render formatted result; per-card error isolation; denylisted tools show a badge, no form.

**Independent Test**: Fill a tool's form, Run → `callTool` invoked with entered values and result rendered; a denylisted tool shows the badge and no form; one failing call leaves other cards usable.

- [X] T017 [US2] Extend `frontend/src/components/mcp/ToolCard.tsx` with a schema-generated form: text `<input>` by default, `<select>` for `enum`, checkbox for `boolean`; mark required fields; a tool with no properties shows only a Run button. Coerce number/integer inputs; omit empty optional fields (per data-model.md rules).
- [X] T018 [US2] Add Run behaviour to `ToolCard`: per-card `running`/`error`/`result` state; on Run call `mcpClient.callTool(name, args)`; render the result panel (pretty-print JSON text content in `<pre>`, else raw text) and a per-card error state on failure/`isError`.
- [X] T019 [US2] Apply the denylist in `ToolCard`: if `tool.name ∈ DENYLISTED_TOOLS`, render full docs + a "not runnable here" badge instead of the form/Run button.
- [X] T020 [P] [US2] Add `.mcp-tool-card__form` / `__result` / `__badge` / running+error state styles to `frontend/src/styles.css`.
- [X] T021 [US2] Create `frontend/src/components/mcp/ToolCard.test.tsx`: assert form generated from schema (text/enum/boolean, required marks); Run invokes `callTool` with entered args and renders the result; a denylisted tool shows the badge and renders no form.

**Checkpoint**: Tools are runnable in-browser; destructive tools gated.

---

## Phase 5: User Story 3 — Connect an external MCP client (Priority: P3)

**Goal**: "Connect your client" section with per-client copy-paste snippets and copy buttons.

**Independent Test**: Load `/mcp` → a snippet per client (Claude Code / Codex / Gemini) each containing `https://api.simonrowe.dev/mcp`; copy button places snippet on the clipboard.

- [X] T022 [US3] Create `frontend/src/components/mcp/ConnectInstructions.tsx`: render the three verified snippets from research.md Decision 4 (Claude Code `claude mcp add --transport http …`; Gemini `~/.gemini/settings.json` `httpUrl`; Codex `~/.codex/config.toml` `url`), each parameterised by `PUBLIC_MCP_URL`, each with a copy-to-clipboard button (guard against unavailable `navigator.clipboard`). Use a Lucide icon for the copy action.
- [X] T023 [US3] Render `<ConnectInstructions>` as a section in `frontend/src/pages/McpPage.tsx` (shown regardless of catalogue state, so it appears even when tools are empty).
- [X] T024 [P] [US3] Add `.mcp-page__connect` / snippet / copy-button BEM styles to `frontend/src/styles.css`.
- [X] T025 [US3] Create `frontend/src/components/mcp/ConnectInstructions.test.tsx`: assert each client's snippet renders and contains the MCP URL; assert clicking copy calls the clipboard API.

**Checkpoint**: External-client onboarding available.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T026 [P] Verify responsive layout (desktop grid + mobile single-column) and light/dark theming of all `.mcp-*` styles using existing CSS custom properties.
- [X] T027 [P] Run `cd frontend && npm test` and `npm run lint`; fix any failures/ESLint issues in the new files.
- [X] T028 (VERIFIED LIVE in browser via Playwright: 10 cards, getProfile + searchBlogs runs render results, submitContactForm shows badge+no form, nav link works, connect snippets render) Run the full manual verification checklist in `quickstart.md` §4 against a local backend (including stop-backend → page-level error, and `submitContactForm` badge).
- [~] T029 [P] (dev Vite proxy VERIFIED passing SSE-framed tools/list; prod nginx config written w/ method-branch + proxy_buffering off but not run locally) Prod-like check: confirm an SSE-framed `tools/call` response passes through `nginx.conf`'s `location /mcp` with `proxy_buffering off` (per research.md open item 2).

---

## Dependencies & Execution Order

- **Setup (T001–T004)** → blocks everything (T002 handshake findings feed T007).
- **Foundational (T005–T012)** → blocks all user stories. T007 (client) depends on T002/T005/T006; T008 depends on T007; T009 depends on T008.
- **User stories** depend only on Foundational, then are largely independent:
  - **US1 (T013–T016)** — MVP. T014 depends on T013; T016 depends on T013/T014.
  - **US2 (T017–T021)** — extends `ToolCard` (T013) and needs `callTool` (T007). T018 depends on T017; T021 depends on T017–T019.
  - **US3 (T022–T025)** — independent of US1/US2 (only needs the page shell T008 and `PUBLIC_MCP_URL` T006). Can be built in parallel with US1/US2.
- **Polish (T026–T029)** → after the stories it verifies.

## Parallel Opportunities

- Setup: T003, T004 in parallel (after/alongside T001).
- Foundational: T005, T006 in parallel; then T010, T011, T012 in parallel once the route exists.
- Cross-story: US3 (T022–T025) can proceed in parallel with US1/US2 since it touches only new files + the page shell.
- Within stories, `[P]` style tasks (T015, T020, T024) touch `styles.css` — serialize edits to that one file even though they're logically independent.

## Implementation Strategy

- **MVP = Phase 1 + Phase 2 + Phase 3 (US1)**: a live, self-updating tool catalogue with page-level error handling. Independently shippable.
- **Increment 2 = US2**: interactive execution + destructive-tool gating.
- **Increment 3 = US3**: external-client connect instructions.
- Test tasks are colocated at the end of each story (T016, T021, T025) per the spec's requested Vitest coverage.

## Format validation

All 29 tasks use `- [ ] Txxx [P?] [US?] description + file path`. Setup/Foundational/Polish carry no story label; US1/US2/US3 tasks carry their label.


---

## Verification findings (fixes made during live end-to-end testing)

These were discovered only by running the real stack (backend + frontend + browser) and are now applied:

- [X] T030 Backend: MCP server exposed **0 tools** — `@Tool` methods are chat-only (consumed via `ChatClient.defaultTools`), never bridged to the MCP server. Added `backend/src/main/java/com/simonrowe/mcp/McpServerConfig.java` registering a `ToolCallbackProvider` (`MethodToolCallbackProvider` over `ProfileMcpTools`). Verified: `tools/list` now returns 10; app starts cleanly (no duplicate-tool conflict); chat context still builds.
- [X] T031 Proxy: the SPA route `/mcp` and backend endpoint `/mcp` collide — a browser GET for the page was proxied to the backend (400 "Invalid Accept header"). Fixed by branching on method: `vite.config.ts` `bypass` serves the SPA for GET; `nginx.conf` `location = /mcp` rewrites GET → `/index.html`, proxies non-GET. Verified: GET /mcp → 200 text/html, POST /mcp → 200.
- [X] T032 Client: `VITE_API_BASE_URL` is set in dev, so `${API_BASE_URL}/mcp` called the backend **cross-origin**, and the `Mcp-Session-Id` response header is not CORS-exposed → "Session ID missing". Fixed `mcpClient.ts` to always use same-origin relative `/mcp` (per the design's proxy architecture). Verified: handshake + tools render.
