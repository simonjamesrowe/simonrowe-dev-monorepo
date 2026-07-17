# Implementation Plan: Chat Drawer Fix-Up

**Branch**: `027-chat-fixup` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/027-chat-fixup/spec.md`

## Summary

Fix four visible defects in the "Ask me anything" chat drawer and add regression
coverage plus observability. The work is six coordinated slices: (1) show the real
contextual tool label in the finished tool block instead of "Used 1 tool"; (2) render
exactly one clean answer per prompt by making the server's `STREAM_END` `fullResponse`
authoritative for prose and by removing the double-send/double-generation vectors; (3)
add safe, allowlisted `a`/`img` react-markdown renderers so answers link to site content
and embed images without the model being able to fabricate a URL; (4) add job/skill-group
ids to widget payloads and tool returns and wire `/experience?job=`/`?skillGroup=` +
section-hash deep links via the existing `useDrawer` hook; (5) introduce Playwright as an
e2e harness driving the real chat against a local full stack plus a read-only prod smoke
check; (6) bootstrap Langfuse org/project/keys deterministically in `docker-compose.prod.yml`
and evaluate Spring AI content capture. Technical approach is guided by the approved design
doc `docs/superpowers/specs/2026-07-17-chat-fixup-design.md`, verified against current code.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend, Vite)

**Primary Dependencies**: Spring Boot 3.5.9, Spring AI 1.1.4 (OpenAI SDK starter + MCP
server + Elasticsearch vector store), Spring WebSocket/STOMP; React Router 7, react-markdown
+ remark-gfm, `@stomp/stompjs`; new dev dep: `@playwright/test`

**Storage**: MongoDB (primary), Elasticsearch (vector + keyword). No schema changes — job
and skill-group ids already exist on source DTOs (`JobSummaryDto.id`, `SkillGroupSummaryDto.id`);
they are merely dropped by the widget mappers today.

**Testing**: Vitest + Testing Library (frontend unit/component), JUnit 5 + Mockito +
Testcontainers (backend), Playwright (new frontend e2e). Existing `AbstractIntegrationTest`
for Spring slice tests.

**Target Platform**: Linux server via Docker Compose (prod); local dev via `./scripts/start.sh`.

**Project Type**: Web application (separate backend + frontend containers).

**Performance Goals**: Chat streaming latency unchanged; e2e must remain deterministic
despite a live LLM (structure/behaviour assertions, not exact wording). Langfuse trace
visible within ~1 minute of a chat message.

**Constraints**: No change to auth, rate limiting, session limits, streaming transport
(STOMP/WS), or chat visual style. No new content pages. Model MUST NOT be able to render a
fabricated or unsafe URL/image (safe by construction on the frontend). Langfuse init must be
idempotent. Prod `.env` reconciliation + service restart are owner-executed runbook steps,
not automated in this workspace.

**Scale/Scope**: Single-site portfolio app; a handful of concurrent chat sessions. Scope is
the chat drawer, the experience/news-events pages' deep-link wiring, widget payload id
plumbing, prompt tuning, an e2e harness, and prod compose/observability config.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle II (Modern Java & React Stack)** — PASS. Keeps Spring AI 1.1.4 + OpenAI SDK,
  the custom `ContextAwareQuestionAnswerAdvisor`, MCP `ProfileMcpTools`, STOMP transport,
  `@stomp/stompjs` client, plain CSS/BEM, Lucide icons, react-markdown + remark-gfm. No new
  LLM provider, CSS framework, or icon library. The constitution states "Markdown content
  rendered in the frontend MUST use React Markdown with GFM… Raw HTML rendering MUST NOT be
  enabled for arbitrary user-submitted content" — this feature does **not** enable
  `rehype-raw`; it adds custom `a`/`img` component renderers that *restrict* output, keeping
  chat markdown safe by construction. Aligned.
- **Principle II — routing convention deviation (documented).** The constitution lists
  `/jobs/{id}` and `/skills-groups/{id}` as drawer route conventions. The **actual** public
  site implements job/skill-group drawers as `useDrawer` state on `/experience` (those
  path-based routes exist only in admin). The design doc (approved) uses
  `/experience?job=<id>` / `?skillGroup=<id>` query deep links, which match the real
  implementation and reuse `useDrawer`. See Complexity Tracking.
- **Principle III (Quality Gates)** — PASS. Backend changes covered by Mockito unit tests
  (widget-payload id assertions, prompt wiring) following existing `ProfileMcpToolsTest` /
  `ChatConfigPromptTest` patterns; Google Java Style + JaCoCo unaffected. Frontend changes
  covered by Vitest unit tests. Playwright e2e adds critical-journey coverage (constitution
  requires FE tests for critical journeys). No mocked infrastructure introduced for
  integration-level checks.
- **Principle IV (Observability & Operability)** — PASS / directly advanced. Keeps the
  OpenTelemetry Spring Boot starter (no Java agent) → Alloy → Langfuse OTLP path. Adds
  deterministic Langfuse provisioning so traces are actually visible. Nginx WS-upgrade and
  the four-upstream restart gotcha are respected (compose-only edits to the `langfuse`
  service env; no upstream removed).
- **Principle V (Simplicity & Incremental Delivery)** — PASS. Six independently testable
  slices ordered by priority; reuses existing drawer/streaming/widget mechanisms; no new
  backend streaming channel (allowlist derived from already-streamed widget payloads); no new
  persistence.

**Initial gate: PASS** (one documented, justified deviation — see Complexity Tracking).

## Project Structure

### Documentation (this feature)

```text
specs/027-chat-fixup/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (message/payload + URL contracts)
│   ├── chat-stream-contract.md
│   ├── widget-payloads.md
│   └── deep-link-urls.md
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit.specify)
└── tasks.md             # /speckit.tasks output (created next)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── chat/
│   │   ├── ChatController.java              # STREAM_END already sends fullResponse (authoritative)
│   │   ├── ChatConfig.java                  # widgetPromptGuidance(): tighten + drop band-aid line
│   │   ├── SkillsWidgetPayload.java         # ADD id to Group
│   │   ├── EmploymentWidgetPayload.java     # ADD id to Job
│   │   └── ContextAwareQuestionAnswerAdvisor.java  # (reference: context header format)
│   └── mcp/
│       └── ProfileMcpTools.java             # toSkillsPayload/toEmploymentPayload map ids through;
│                                            # ensure getSkills/getJobs LLM returns expose ids
├── src/main/resources/application.yml       # evaluate spring.ai observation content capture
└── src/test/java/com/simonrowe/
    ├── mcp/ProfileMcpToolsTest.java          # assert ids in skills/employment payloads
    └── chat/ChatConfigPromptTest.java        # assert new guidance, band-aid removed

frontend/
├── src/components/chat/
│   ├── ToolActivityBlock.tsx                # remove <details>; show ✓ + block.label
│   ├── chatStreamReducer.ts                 # STREAM_END reconciles text to fullResponse
│   ├── ChatPanel.tsx                        # guard single initial-query send / reconnect
│   ├── ChatMessage.tsx                      # custom a/img renderers + per-message allowlist
│   ├── chatTypes.ts                         # add Group.id / Job.id to payload types
│   ├── linkPolicy.ts                        # NEW: internal-route + allowlist link/img rules
│   └── widgets/…                            # unchanged
├── src/pages/
│   ├── ExperiencePage.tsx                   # read ?job=/?skillGroup=, section ids, scroll-to-hash
│   └── NewsEventsPage.tsx                   # section ids (news, events), scroll-to-hash
├── tests/…                                  # Vitest unit tests for the above
└── e2e/                                     # NEW: Playwright
    ├── chat.local.spec.ts                   # single bubble, ordering, tool label, link/img rules
    └── chat.prod-smoke.spec.ts              # read-only: drawer connects + non-empty answer

docker-compose.prod.yml                       # ADD LANGFUSE_INIT_* to langfuse service env
scripts/verify-langfuse-trace.sh              # NEW: send chat + confirm trace via Langfuse API
docs/                                         # runbook note for owner-executed .env reconcile
```

**Structure Decision**: Web application (Option 2). Backend and frontend evolve together;
the majority of behaviour changes are frontend (reducer, renderers, deep-link wiring, e2e),
with small backend changes (widget id plumbing, prompt guidance, observability config) and
one deployment-config change (compose Langfuse init). Directories above are the real,
verified paths.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Deep links use `/experience?job=`/`?skillGroup=` query params instead of the constitution's `/jobs/{id}` `/skills-groups/{id}` path routes | Jobs and skill groups have no standalone public page — they open as drawers on `/experience` via the existing `useDrawer` state. Query params reuse that exact mechanism with graceful degradation on stale ids. | Introducing `/jobs/{id}` `/skills-groups/{id}` public routes would mean building standalone pages or route-to-drawer redirects that don't exist today, adding surface area for a navigation the drawer already serves — violating Principle V (Simplicity). The approved design doc selects the query-param form; the constitution's path convention reflects an aspirational scheme not implemented on the public experience page. |
| New Playwright dependency + `frontend/e2e/` harness (no e2e exists today) | The spec (US5) and design Section 5 require locking in the single-bubble/ordering/tool-label/link-image behaviour against a real running stack; Vitest cannot drive the STOMP-backed live chat end-to-end. | Vitest component tests with a mocked `chatService` already exist and are kept, but they cannot exercise the real WebSocket stream, reconnect path, or SPA navigation — the exact surfaces where the double-answer and link bugs live. |
