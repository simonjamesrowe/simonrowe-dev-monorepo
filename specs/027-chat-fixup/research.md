# Phase 0 Research: Chat Drawer Fix-Up

All open questions from the Technical Context are resolved below. Findings are grounded in
the current code (verified 2026-07-17) and the approved design doc.

## R1 — Tool activity "Used 1 tool" display

- **Decision**: In `ToolActivityBlock.tsx`, remove the `<details>`/`<summary>` wrapper for
  the `done` state and render a single line: `<Check>` icon + `block.label`. The running
  state (`<Loader2>` spinner + `block.label`) is already correct and stays.
- **Rationale**: `block.label` already carries the friendly, server-supplied label
  (`response.toolLabel`); "Used 1 tool" is a hardcoded literal at `ToolActivityBlock.tsx:23`.
  The reducer already pushes one `{kind:'tool'}` block per tool, so multiple tools naturally
  render as stacked labelled lines — no reducer change needed.
- **Alternatives considered**: Keep the expander but change the summary text — rejected;
  the design explicitly wants the label visible without interaction. Aggregate a count —
  rejected; per-tool labels are more informative and already available.

## R2 — One clean answer (double-answer + scramble)

- **Decision (authoritative text)**: On `STREAM_END`, reconcile the message's text block(s)
  to the server's `response.content` (the accumulated `fullResponse`). Currently
  `chatStreamReducer.ts:46-51` only uses `response.content` when `blocks.length === 0`.
  Change: on `STREAM_END`, replace the *text* blocks' content with the authoritative
  `fullResponse` while **preserving** tool/widget blocks and their relative position
  (collapse the streamed text into a single reconciled text block placed where prose
  belongs; keep tool/widget blocks intact).
- **Decision (single generation/send)**: Guard the frontend so the `initialQuery` is sent
  exactly once. Today `ChatPanel.tsx` schedules the initial-query send inside `onConnect`
  via a 50ms timer with no "already sent" guard; `@stomp/stompjs` `reconnectDelay: 5000`
  means a reconnect re-fires `onConnect` and can re-send. Add an `initialQuerySentRef`
  (boolean) set on first send and checked before re-sending. Confirm `ChatController`
  subscribes exactly once (it does — single `.subscribe()`), so there is no backend double
  subscription; the `Flux` is cold and only subscribed once.
- **Decision (remove band-aid)**: Once the send guard + authoritative reconcile are in
  place and verified by e2e, remove the trailing sentence "Do not start a new answer unless
  the visitor has sent a new prompt." from `ChatConfig.widgetPromptGuidance()` and update
  `ChatConfigPromptTest` accordingly.
- **Root-cause note (systematic debugging)**: The perceived "double answer" is most
  consistent with the client rendering both the incrementally-appended chunks **and** the
  `STREAM_END` `content` as separate text — plus a possible reconnect re-send. The scramble
  is consistent with out-of-order/interleaved chunk delivery on the shared
  `/topic/chat.{sessionId}` topic (controller chunks interleave with tool/widget messages
  from `ChatStreamPublisher`). Making `fullResponse` authoritative on `STREAM_END` fixes the
  rendered scramble regardless of intermediate ordering; the send guard removes the reconnect
  duplicate. A live reproduction against the running stack (US1 + e2e) confirms before the
  band-aid is removed.
- **Alternatives considered**: Per-message topic dedup keyed by a generation id — heavier;
  not needed once the client trusts `fullResponse` and sends once. Buffering chunks and
  ignoring them entirely (render only `STREAM_END`) — rejected; loses the streaming UX.

## R3 — Safe, allowlisted link/image rendering

- **Decision**: Add a `linkPolicy.ts` module and custom `components={{ a, img }}` renderers
  in `ChatMessage.tsx`. Do **not** enable `rehype-raw` (keeps constitution's raw-HTML
  prohibition). Policy:
  - **Internal routes** (pattern match, always allowed): `/`, `/profile`, `/experience`,
    `/blogs`, `/blogs/:id`, `/news-events`, plus `/experience?job=…`, `/experience?skillGroup=…`,
    and section hashes `#roles`/`#skills`/`#news`/`#events`. Render as React Router `<Link>`
    (in-site nav, no reload).
  - **External `https://…`**: render as `<a target="_blank" rel="noopener noreferrer">`
    **only if** the URL is in the per-message allowlist; otherwise strip to plain text.
  - **Any other scheme** (`javascript:`, `data:`, `http:`…): always strip to plain text.
  - **Images**: render only if `src` is allowlisted or begins with our uploads origin
    (`/uploads/` or `${API_BASE_URL}/uploads/`, matching `resolveChatWidgetImageUrl`);
    lazy-loaded, `max-width:100%`, height auto, rounded, with `alt`. Otherwise dropped.
- **Allowlist construction**: Built per-message from that message's already-streamed widget
  blocks — blog `url` + `imageUrl`, news `originalUrl` + `imageUrl`, event `originalUrl`,
  code/profile image URLs. No new backend channel. `ChatMessage` already receives the
  message's `blocks`, so the allowlist is derivable client-side at render time.
- **Rationale**: Safety by construction on the render layer means even a fabricated URL from
  the model (e.g. `[Workcover Queensland](/experience Macquarie Group,)`) cannot become a
  live/dangerous link — it is not a recognized internal route and not in the allowlist, so it
  degrades to text. Reuses the existing `resolveChatWidgetImageUrl` origin logic for images.
- **Alternatives considered**: `rehype-sanitize` alone — insufficient; it sanitizes schemes
  but would still render fabricated internal-looking links as live anchors and does not do
  React Router navigation. Backend-side link rewriting — rejected; the design wants the model
  free to write prose links while the client enforces safety, and it avoids a new streaming
  channel.

## R4 — Item-level deep links (jobs / skill groups)

- **Decision (backend ids)**: Add `id` to `SkillsWidgetPayload.Group` and
  `EmploymentWidgetPayload.Job`, and map the real ids through in
  `ProfileMcpTools.toSkillsPayload` / `toEmploymentPayload` (source DTOs
  `SkillGroupSummaryDto.id` / `JobSummaryDto.id` already carry them). Ensure the LLM-facing
  tool returns for `getSkills`/`getJobs` also expose ids — the no-query branches already
  return the id-bearing summary DTOs; verify the query branches (search results) do not
  regress and that the prompt can rely on ids being present.
- **Decision (frontend wiring)**: `ExperiencePage` reads `useSearchParams()` on mount and on
  param change; `?job=<id>` → `openJob(id)`, `?skillGroup=<id>` → `openSkillGroup(id)`. When
  the drawer closes, clear the param (so back/refresh behave). Add stable `id`s to sections
  (`roles`, `skills` on Experience; `news`, `events` on NewsEventsPage) and a small
  scroll-to-hash effect (shared hook `useScrollToHash`) that scrolls to `#hash` after nav.
- **Rationale**: Reuses `useDrawer` (`openJob`/`openSkillGroup`, mutually exclusive
  `selectedJobId`/`selectedGroupId`) — no new drawer machinery. Stale/unknown ids degrade
  gracefully because the drawer components already handle a missing entity by showing nothing
  / listing.
- **Alternatives considered**: New `/jobs/:id` `/skills-groups/:id` public routes — rejected
  (see Complexity Tracking; adds pages that don't exist and duplicates the drawer). Storing
  deep-link state in the chat message — rejected; URL is the natural carrier and supports
  share/back/refresh.

## R5 — Playwright e2e harness

- **Decision**: Add `@playwright/test` as a frontend dev dependency with a
  `playwright.config.ts` and a `frontend/e2e/` directory. Two suites:
  - `chat.local.spec.ts` (primary, deterministic): assumes a local full stack is up
    (`./scripts/start.sh` or docker-compose). Drives the real drawer: asks a skills
    question, asserts exactly one assistant bubble, coherent/ordered text (structure, not
    wording), contextual tool label present ("Looking up Simon's skills", not "Used 1 tool"),
    skills widget rendered; clicks an internal link and asserts in-site navigation (URL
    change / drawer open, no full reload); asserts an answer image renders; asserts a
    fabricated/non-allowlisted URL is not a live link.
  - `chat.prod-smoke.spec.ts` (secondary, read-only): opens the chat drawer on the deployed
    site, confirms WebSocket connect and a non-empty answer. No data mutation.
- **Rationale**: Constitution requires FE tests for critical journeys; the double-answer,
  ordering, and link/image bugs live on the live WS + SPA-navigation surfaces that Vitest
  (with a mocked `chatService`) cannot exercise. Assertions target structure/behaviour to
  stay deterministic despite a real LLM.
- **Determinism approach**: Assert on `data-testid`/role and counts (bubble count, label
  present, link/img presence and href/target rules), never on exact model text. Use generous
  timeouts for the LLM response and `expect.poll` on the answer bubble.
- **Alternatives considered**: Cypress — rejected; Playwright is the modern default, better
  WS/timeout handling, no extra runtime. Recording/replay of the LLM — out of scope; the
  design wants a real backend for the primary suite.

## R6 — Langfuse observability bootstrap + Spring AI content capture

- **Decision (bootstrap)**: Add `LANGFUSE_INIT_*` env to the `langfuse` service in
  `docker-compose.prod.yml` (alongside the existing `<<: *langfuse-env` merge):
  `LANGFUSE_INIT_ORG_ID`, `LANGFUSE_INIT_PROJECT_ID`,
  `LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}`,
  `LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_SECRET_KEY}`,
  `LANGFUSE_INIT_USER_EMAIL`, `LANGFUSE_INIT_USER_NAME`. Sourcing the project keys from the
  same `${LANGFUSE_PUBLIC_KEY}`/`${LANGFUSE_SECRET_KEY}` that Alloy uses guarantees exporter
  and project agree by construction. Langfuse init is idempotent (creates only absent
  resources), so it is safe to leave in permanently.
- **Decision (env reconcile — runbook, not automated)**: The owner reconciles `.env` in the
  deploy dir so `LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY` match the init keys, then
  restarts `langfuse` and `alloy`. Documented as a runbook step (prod-touching, outside this
  workspace). Respect the nginx four-upstream restart gotcha: confirm frontend/backend/
  portainer/langfuse all running before restarting nginx (restarting only langfuse/alloy is
  fine).
- **Decision (Spring AI content capture)**: Evaluate enabling prompt/completion capture for
  Spring AI 1.1.4 on the OpenAI SDK starter. The exact property names MUST be verified
  against 1.1.4 before enabling (candidates in the `spring.ai.chat.client.observations` /
  `spring.ai.chat.observations` namespaces, e.g. `log-prompt`/`log-completion`). Document the
  privacy trade-off (storing visitor chat content) and the decision; default to leaving
  content capture off unless the value clearly outweighs storing visitor text, but confirm
  gen_ai spans appear regardless.
- **Decision (verification)**: Add `scripts/verify-langfuse-trace.sh` — sends a chat message
  (or instructs how to) and queries the Langfuse public API with the project keys to confirm
  a corresponding trace exists. Bash, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR`
  resolution per constitution Principle IX.
- **Rationale**: The trace path already exists (backend → Alloy :4317 → Langfuse OTLP); the
  only gap is that the account has no org/project for the referenced keys. Deterministic init
  closes that gap without manual UI steps and survives redeploys.
- **Alternatives considered**: Manual org/project creation in the UI — rejected; not
  reproducible, breaks on volume loss/redeploy. A separate one-shot init container — rejected;
  Langfuse's built-in `LANGFUSE_INIT_*` is the supported mechanism and simpler.

## Cross-cutting resolved unknowns

- **No new persistence**: Chat sessions stay in-memory; no MongoDB schema change (ids already
  exist on source DTOs).
- **No new streaming channel**: Allowlist is derived client-side from already-streamed widget
  blocks.
- **Transport unchanged**: STOMP topic `/topic/chat.{sessionId}`, `/app/chat.send`, remains.
- **Style unchanged**: Plain CSS/BEM; new tool-block/image styles reuse existing `styles.css`
  conventions.
