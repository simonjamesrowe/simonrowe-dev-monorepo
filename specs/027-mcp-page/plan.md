# Implementation Plan: Public MCP Tools Page

**Branch**: `027-mcp-page` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/027-mcp-page/spec.md`

## Summary

Add a public `/mcp` page to the React frontend that is a genuine MCP client: on mount it performs the Streamable-HTTP handshake against the site's own MCP server, renders one card per tool from the live `tools/list` response, and runs `tools/call` on demand. Adding a backend `@Tool` surfaces it on the page with zero frontend changes. A "Connect your client" section gives copy-paste setup snippets for Claude Code, Codex CLI, and Gemini CLI pointing at the public production endpoint.

**Key research outcome (resolves the design's #1 risk):** the live Spring AI 1.1.4 MCP server currently uses the **SSE transport** (`/sse` + `/mcp/message`), not Streamable-HTTP at `/mcp`. Per user decision, the plan enables Streamable-HTTP with a **one-line backend YAML change** (`spring.ai.mcp.server.protocol: STREAMABLE`). This exposes `/mcp`, keeps the frontend client simple, and makes the connect-your-client snippets (`--transport http`, `httpUrl`) correct for external clients. The chat registers tools in-process (`ChatConfig.defaultTools(profileMcpTools)`), so switching the HTTP transport has **zero impact** on chat.

## Technical Context

**Language/Version**: TypeScript 5.x (frontend); Java 21 / Spring Boot 3.5.x (backend — config only, no Java code)

**Primary Dependencies**: React (latest stable), React Router v7, Vite, Vitest, Lucide React; Spring AI 1.1.4 `spring-ai-starter-mcp-server-webmvc` (existing)

**Storage**: N/A — no persistence. Tool catalogue is fetched live from the MCP server at page load.

**Testing**: Vitest + Testing Library (frontend), colocated `*.test.tsx`. No backend test needed (config-only change; existing MCP server autoconfig is unchanged in behaviour beyond transport).

**Target Platform**: Modern browsers (same as rest of site). Same-origin requests to `/mcp` via dev/prod proxy.

**Project Type**: Web application (frontend + backend) — this feature is almost entirely frontend plus one backend config line and two proxy config lines.

**Performance Goals**: Page interactive after a 3-request handshake (initialize → initialized → tools/list). Standard web-app expectations; no special throughput target. Protected by the existing 60 req/min rate limiter on `/mcp/**`.

**Constraints**: No new backend Java code, no REST introspection endpoint, no auth on the page. Streaming (SSE-framed) responses must pass through nginx (`proxy_buffering off`). Destructive tools must not be runnable from the public page.

**Scale/Scope**: 10 tools today; the page is fully dynamic so scale is bounded only by how many tools the server reports. ~4 new frontend files (client, types, page, 2 components), ~1 styles block, plus route/nav wiring and 3 config edits.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against `.specify/memory/constitution.md` (v1.11.0):

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo / separate containers | ✅ PASS | Frontend change in `frontend/`, backend config in `backend/`, no container coupling. nginx already proxies backend paths; adding a `/mcp` location follows the same pattern. |
| II. Modern Java & React stack | ✅ PASS | Reuses React (latest), plain CSS + BEM in single `styles.css`, Lucide React icons, React Router. **Constitution explicitly mandates the MCP server via `spring-ai-starter-mcp-server-webmvc`** — enabling Streamable-HTTP is a supported config of that starter, not a new dependency or provider. No CSS framework, no new icon lib, no new LLM SDK. |
| III. Quality gates | ✅ PASS | Vitest tests for the client, page, and both components (critical user journeys per Principle III). No backend Java changed, so JaCoCo/Checkstyle unaffected. |
| IV. Observability | ✅ PASS | No change to metrics/tracing/logging. Rate limiter and CORS already cover `/mcp/**`. |
| V. Simplicity & incremental delivery | ✅ PASS | Simplest working solution: a small pure-TS client + presentational components. No persistence (nothing to store — YAGNI). Three independently testable/deliverable user stories (catalogue → run → connect). Denylist is a one-line constant, not an abstraction. |
| VI. Admin CMS UX | ✅ N/A | Public page, not admin CMS. |
| VII. Interactive tour | ✅ N/A | Not a tour feature. |
| VIII. Backup & restore | ✅ N/A | No data. |
| IX. Shell scripting | ✅ N/A | No scripts added. |

**Gate result: PASS.** No violations. The one nuance — a backend config change vs. the spec's "no backend change" assumption — was surfaced to and approved by the user, and is a config toggle of the constitution-mandated MCP starter (not new code). No Complexity Tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/027-mcp-page/
├── plan.md              # This file
├── research.md          # Phase 0 output — transport decision, handshake protocol, client-config verification
├── data-model.md        # Phase 1 output — MCP client-side entity shapes (TS types)
├── quickstart.md        # Phase 1 output — how to run/verify the page locally
├── contracts/
│   └── mcp-streamable-http.md   # JSON-RPC method contracts the client depends on
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit.specify)
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
└── src/main/resources/application.yml     # +1 line: spring.ai.mcp.server.protocol: STREAMABLE

frontend/
├── vite.config.ts                          # + '/mcp' dev proxy entry
├── nginx.conf                              # + location /mcp (SSE-friendly)
└── src/
    ├── App.tsx                             # + lazy '/mcp' route under PublicLayout (named() helper)
    ├── styles.css                          # + .mcp-page / .mcp-tool-card BEM block
    ├── types/
    │   └── mcp.ts                          # NEW — McpTool, McpJsonSchema, ToolResult, JSON-RPC envelopes
    ├── services/
    │   └── mcpClient.ts                    # NEW — Streamable-HTTP client (connect/listTools/callTool)
    ├── pages/
    │   ├── McpPage.tsx                     # NEW — orchestration + connect section + tool grid
    │   └── McpPage.test.tsx                # NEW
    └── components/
        └── mcp/
            ├── ConnectInstructions.tsx     # NEW — per-client copy-paste snippets
            ├── ConnectInstructions.test.tsx# NEW
            ├── ToolCard.tsx                # NEW — schema-driven form + run + result panel
            └── ToolCard.test.tsx           # NEW
    └── components/layout/
        ├── TopNav.tsx                      # + "MCP" desktop nav link
        └── MobileMenu.tsx                  # + "MCP" mobile nav item
```

**Structure Decision**: Web application. The feature slots into the established frontend conventions verified during research: `named()` lazy route imports and `PublicLayout` in `App.tsx`; the `NewsEventsPage` effect pattern (`trackPageView` + `document.title`); reuse of `LoadingIndicator`/`ErrorMessage`; per-domain service modules under `src/services/`; `API_BASE_URL` (`''` = same-origin) from `config/api.ts`; colocated Vitest tests. The only non-frontend edits are one backend YAML line and the two proxy config additions (`vite.config.ts`, `nginx.conf`), mirroring the existing `/api`, `/ws`, `/uploads` entries.

## Complexity Tracking

> No constitution violations — section intentionally empty.
