---

description: "Task list for Chat Drawer Fix-Up"
---

# Tasks: Chat Drawer Fix-Up

**Input**: Design documents from `/specs/027-chat-fixup/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUDED — the spec's Testing section and User Story 5 explicitly require unit +
e2e tests (FR-022…FR-026).

**Organization**: Tasks are grouped by user story (priority order P1→P3) for independent
implementation and testing.

> **Implementation status (2026-07-17)**: 43/47 tasks complete. All code, unit/component
> tests, e2e specs, compose/observability config, and docs are done and verified
> (frontend `npm test` 239 pass, `tsc`/ESLint clean; backend targeted unit tests +
> Checkstyle pass; Playwright discovers 3 specs; compose YAML valid). The 4 remaining tasks
> (**T002, T009, T038, T044**) require a running local full stack (backend + OpenAI key) or
> prod access and are deferred to live verification — see each task's note.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1…US6, mapping to the spec's user stories

## Path Conventions

Web app: `backend/src/...`, `frontend/src/...`, `frontend/tests/...`, `frontend/e2e/...`,
deployment config at repo root.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm baseline before changing behaviour.

- [x] T001 Confirm baseline green: run `cd frontend && npm test` and `cd backend && ../gradlew test`; note any pre-existing failures so regressions introduced by this feature are distinguishable.
- [ ] T002 Bring up the local full stack (`./scripts/start.sh`) and manually reproduce the original bug per `specs/027-chat-fixup/quickstart.md` (double answer, scramble, "Used 1 tool", plain-prose links) to capture the pre-fix state for later comparison.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: No blocking cross-story foundation is required — the codebase already provides
the streaming transport, reducer, widgets, drawer hook, and test harnesses. Each user story
below is independently implementable. (Note: US1 and US3 both edit
`backend/.../chat/ChatConfig.java`; US3 tightens the prompt and US1 removes the band-aid line
— sequence those two edits, US3's prompt tightening then US1's band-aid removal, or do both
in one coordinated edit. US4 edits `frontend/src/components/chat/chatTypes.ts` which US3 also
reads; US4's additive id fields do not block US3.)

**Checkpoint**: Foundation ready — user story implementation can begin.

---

## Phase 3: User Story 1 - One clean, correctly-ordered answer (Priority: P1) 🎯 MVP

**Goal**: Exactly one assistant bubble per prompt; final text equals the server's
authoritative `fullResponse`; no reconnect double-send; band-aid prompt removed.

**Independent Test**: Ask any question → one bubble, clean/ordered text; simulate a reconnect
and confirm the initial query is sent once.

### Tests for User Story 1 ⚠️ (write first, ensure they fail)

- [x] T003 [P] [US1] Add reducer test in `frontend/src/components/chat/chatStreamReducer.test.ts`: given a message with scrambled/interleaved text block(s) plus a `tool` block and a `widget` block, applying `STREAM_END` with `content = fullResponse` yields a single clean text block equal to `fullResponse` with the tool and widget blocks preserved in order.
- [x] T004 [P] [US1] Add reducer test in `frontend/src/components/chat/chatStreamReducer.test.ts`: `STREAM_END` with empty/absent `content` leaves incrementally-built text untouched (fallback path).
- [x] T005 [P] [US1] Add `ChatPanel` test in `frontend/tests/components/chat/ChatPanel.test.tsx`: when `onConnect` fires twice (reconnect) with an `initialQuery`, `chatService.sendMessage` is called exactly once for that query.
- [x] T006 [P] [US1] Update `backend/src/test/java/com/simonrowe/chat/ChatConfigPromptTest.java` to assert the band-aid sentence "Do not start a new answer unless the visitor has sent a new prompt." is NO LONGER present in `widgetPromptGuidance()`.

### Implementation for User Story 1

- [x] T007 [US1] In `frontend/src/components/chat/chatStreamReducer.ts`, change the `STREAM_END` branch to reconcile prose: replace the message's text block content with `response.content` (authoritative `fullResponse`) while preserving `tool`/`widget` blocks and their order; keep the empty-content fallback. Collapse multiple streamed text fragments into a single reconciled text block positioned where prose belongs.
- [x] T008 [US1] In `frontend/src/components/chat/ChatPanel.tsx`, add an `initialQuerySentRef` guard so the `initialQuery` is published to `/app/chat.send` at most once across STOMP reconnects; verify no duplicate `sendMessage` on effect re-run or `onConnect` re-fire.
- [ ] T009 [US1] Verify (systematic debugging) against the running stack that the double-answer and scramble are gone with T007+T008 in place, BEFORE removing the band-aid; record the confirmation.
- [x] T010 [US1] In `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` `widgetPromptGuidance()`, remove the trailing band-aid sentence (coordinate with T017 which also edits this method).
- [x] T011 [US1] Run `frontend` and `backend` test suites; confirm T003–T006 pass.

**Checkpoint**: One clean answer per prompt, verified live and by unit tests. MVP complete.

---

## Phase 4: User Story 2 - Clear tool-activity labels (Priority: P2)

**Goal**: Finished tool block shows the contextual label with a checkmark, no expander;
multiple tools stack as labelled lines.

**Independent Test**: Ask a tool-triggering question → running shows spinner + label, finished
shows "✓ <label>" (not "Used 1 tool").

### Tests for User Story 2 ⚠️

- [x] T012 [P] [US2] Add test in `frontend/tests/components/chat/ToolActivityBlock.test.tsx` (create if absent): `status:'done'` renders `block.label` with a check icon and NO `<details>`/"Used 1 tool"; `status:'running'` renders spinner + `block.label`.

### Implementation for User Story 2

- [x] T013 [US2] In `frontend/src/components/chat/ToolActivityBlock.tsx`, remove the `<details>`/`<summary>` and the hardcoded "Used 1 tool"; render the `done` state as a single line: `<Check>` icon + `block.label`. Keep the `running` state (spinner + label) unchanged.
- [x] T014 [US2] Adjust `frontend/src/styles.css` (BEM `chat-tool--done` classes) so the finished line matches the running line's layout; no visual redesign.
- [x] T015 [US2] Run frontend tests; confirm T012 passes.

**Checkpoint**: Contextual tool labels shown in running and finished states.

---

## Phase 5: User Story 3 - Rich, safe links and images in answers (Priority: P2)

**Goal**: Answer prose links navigate in-site / open external safely; images render inline;
fabricated/unsafe URLs and non-allowlisted images never render as live links/images.

**Independent Test**: Trigger an answer with blog/news links + image → links work, image
renders; a fabricated URL renders as plain text; a `javascript:` link renders as text.

### Tests for User Story 3 ⚠️

- [x] T016 [P] [US3] Add unit tests in `frontend/tests/components/chat/linkPolicy.test.ts` (new): internal route → treated as `<Link>`; allowlisted `https` → new-tab anchor; non-allowlisted/fabricated `https` → plain text; `javascript:`/`data:`/`http:` → plain text; `[Workcover Queensland](/experience Macquarie Group,)` → plain text (not a live link); image `src` in allowlist or `/uploads/` origin → rendered, otherwise dropped.
- [x] T017 [P] [US3] Add test in `frontend/tests/components/chat/ChatMessage.test.tsx` (create if absent): given an assistant message whose blocks include a `blogs` widget, the derived per-message allowlist admits that blog's `url`/`imageUrl`; a link to an unrelated external URL is stripped to text.

### Implementation for User Story 3

- [x] T018 [P] [US3] Create `frontend/src/components/chat/linkPolicy.ts`: `isInternalRoute(href)` (patterns from contracts/deep-link-urls.md incl. `?job=`/`?skillGroup=` and section hashes), `buildAllowlist(blocks)` (blog url+imageUrl, news originalUrl+imageUrl, event originalUrl(+imageUrl), code/profile image URLs), `classifyLink(href, allowlist)` → `internal | external-allowed | strip`, `isAllowedImage(src, allowlist)` (allowlist OR uploads origin `/uploads/` or `${API_BASE_URL}/uploads/`, reusing the logic in `widgets/chatWidgetImages.ts`).
- [x] T019 [US3] In `frontend/src/components/chat/ChatMessage.tsx`, build the per-message allowlist from the message's widget blocks and pass custom `components={{ a, img }}` to `ReactMarkdown`: `a` → React Router `<Link>` for internal, new-tab safe anchor for external-allowed, plain `<span>`/text for strip; `img` → lazy `<img>` (max-width 100%, height auto, rounded, `alt`) if allowed else render nothing. Do NOT enable `rehype-raw`.
- [x] T020 [US3] In `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` `widgetPromptGuidance()` (and/or `chat.system-prompt` in `application.yml`), tighten guidance: link blog mentions to `/blogs/:id`, role/skill mentions to `/experience?job=<id>`/`?skillGroup=<id>`, news/events to their external URL, embed images only from given URLs, and NEVER invent/guess URLs (link nothing if no URL). Coordinate with T010 (same method).
- [x] T021 [US3] Update `backend/src/test/java/com/simonrowe/chat/ChatConfigPromptTest.java` to assert the new link/image guidance phrases are present.
- [x] T022 [US3] Run frontend + backend tests; confirm T016, T017, T021 pass.

**Checkpoint**: Links/images render richly and safely; fabricated URLs degrade to text.
(Depends on US4 for job/skill deep-link *destinations* to actually open drawers, but the link
render policy itself is independently testable.)

---

## Phase 6: User Story 4 - Item-level deep links open the right drawer (Priority: P3)

**Goal**: `/experience?job=<id>`/`?skillGroup=<id>` auto-open the correct drawer; section
hashes scroll; backend supplies job/skill-group ids.

**Independent Test**: Visit `/experience?job=<id>` and `?skillGroup=<id>` → correct drawer
opens; `/experience#roles` scrolls; unknown id degrades gracefully.

### Tests for User Story 4 ⚠️

- [x] T023 [P] [US4] Add backend test in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`: capturing the published `SkillsWidgetPayload` shows each `Group.id` equals the source `SkillGroupSummaryDto.id`; capturing `EmploymentWidgetPayload` shows each `Job.id` equals the source `JobSummaryDto.id`.
- [x] T024 [P] [US4] Add `ExperiencePage` test in `frontend/tests/pages/ExperiencePage.test.tsx` (create if absent): `?job=<id>` calls `openJob(id)`; `?skillGroup=<id>` calls `openSkillGroup(id)`; closing the drawer clears the param; an unknown id does not throw.
- [x] T025 [P] [US4] Add a scroll-to-hash test (in `ExperiencePage`/`NewsEventsPage` test or a `useScrollToHash` hook test) asserting the section element with the matching `id` is scrolled into view when a `#hash` is present.

### Implementation for User Story 4

- [x] T026 [P] [US4] In `backend/src/main/java/com/simonrowe/chat/SkillsWidgetPayload.java`, add `String id` as the first field of `Group`.
- [x] T027 [P] [US4] In `backend/src/main/java/com/simonrowe/chat/EmploymentWidgetPayload.java`, add `String id` as the first field of `Job`.
- [x] T028 [US4] In `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`, map source ids through in `toSkillsPayload` (SkillGroupSummaryDto.id → Group.id) and `toEmploymentPayload` (JobSummaryDto.id → Job.id); confirm `getSkills`/`getJobs` model-facing returns expose ids (no-query branch already does; do not regress the query branch).
- [x] T029 [P] [US4] In `frontend/src/components/chat/chatTypes.ts`, add optional `id?: string` to `SkillWidgetPayload.groups[]` and `EmploymentWidgetPayload.jobs[]` (additive; safe for US3).
- [x] T030 [P] [US4] Create `frontend/src/hooks/useScrollToHash.ts`: scrolls to the element matching `location.hash` after navigation.
- [x] T031 [US4] In `frontend/src/pages/ExperiencePage.tsx`, read `useSearchParams()` on mount/param-change → `openJob`/`openSkillGroup`; clear the param when the drawer closes; add stable section ids `roles` and `skills`; use `useScrollToHash`.
- [x] T032 [P] [US4] In `frontend/src/pages/NewsEventsPage.tsx`, add stable section ids `news` and `events`; use `useScrollToHash`.
- [x] T033 [US4] Run backend + frontend tests; confirm T023–T025 pass.

**Checkpoint**: Chat deep links land on the correct drawer/section; ids flow end-to-end.

---

## Phase 7: User Story 5 - End-to-end tests lock in chat behaviour (Priority: P3)

**Goal**: Playwright drives the real chat on a local full stack (single bubble, ordering,
tool label, link/image rules) + a read-only prod smoke check.

**Independent Test**: `npm run e2e` passes against a running local stack; `npm run e2e:prod-smoke`
confirms prod chat connects and returns a non-empty answer.

### Implementation for User Story 5

- [x] T034 [US5] Add `@playwright/test` to `frontend/package.json` devDependencies; add `frontend/playwright.config.ts` (test dir `e2e/`, baseURL from env, generous LLM timeouts, projects for local + prod-smoke); add `e2e` and `e2e:prod-smoke` npm scripts; ensure `frontend/e2e/` is excluded from the Vitest `include`.
- [x] T035 [US5] Add stable `data-testid` hooks where needed for deterministic selection in `frontend/src/components/chat/ChatPanel.tsx`, `ChatMessage.tsx`, `ToolActivityBlock.tsx` (e.g. assistant-bubble, tool-activity, answer-link, answer-image) without altering visuals.
- [x] T036 [US5] Create `frontend/e2e/chat.local.spec.ts`: open the drawer, ask a skills question, assert exactly one assistant bubble, coherent/ordered text (structure not wording), contextual tool label present ("Looking up Simon's skills", not "Used 1 tool"), skills widget rendered; click an internal link → in-site navigation (URL change / drawer open, no full reload); assert an answer image renders; assert a fabricated/non-allowlisted URL is not a live link.
- [x] T037 [P] [US5] Create `frontend/e2e/chat.prod-smoke.spec.ts`: open the chat drawer on the deployed site, confirm WebSocket connect and a non-empty answer; read-only (no data mutation).
- [ ] T038 [US5] Run `npm run e2e` against the local stack and confirm the primary suite passes; document invocation in `specs/027-chat-fixup/quickstart.md` if it drifted.

**Checkpoint**: Regression coverage locks US1–US4 behaviour against a real stack.

---

## Phase 8: User Story 6 - Langfuse observability works and is verifiable (Priority: P3)

**Goal**: Deterministic Langfuse org/project/keys via compose; evaluate content capture;
verification script.

**Independent Test**: Owner logs in → project visible; a chat message → trace within ~1 min;
survives redeploy.

### Implementation for User Story 6

- [x] T039 [US6] In `docker-compose.prod.yml`, add `LANGFUSE_INIT_ORG_ID`, `LANGFUSE_INIT_PROJECT_ID`, `LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}`, `LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_SECRET_KEY}`, `LANGFUSE_INIT_USER_EMAIL`, `LANGFUSE_INIT_USER_NAME` to the `langfuse` service `environment:` (alongside the `<<: *langfuse-env` merge), sourcing project keys from the same values Alloy uses.
- [x] T040 [P] [US6] Update `.env.example` (repo root) to document the new `LANGFUSE_INIT_*` variables and note that `LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY` must equal the init project keys.
- [x] T041 [US6] Evaluate Spring AI 1.1.4 observation content capture in `backend/src/main/resources/application.yml`: verify the exact property names for the OpenAI SDK starter, document the privacy trade-off of storing visitor chat content, and record the decision (default off unless justified). Confirm gen_ai spans appear regardless.
- [x] T042 [P] [US6] Create `scripts/verify-langfuse-trace.sh` (bash, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` per Principle IX): send/point to a chat message then query the Langfuse public API with the project keys to confirm a corresponding trace exists.
- [x] T043 [P] [US6] Document the owner-executed prod runbook in `docs/` (or `specs/027-chat-fixup/quickstart.md`): reconcile deploy `.env`, restart only `langfuse`+`alloy` (respect the nginx four-upstream restart gotcha), log in as `admin@simonrowe.dev`, run the verify script.

**Checkpoint**: Observability provisioned deterministically and verifiable.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T044 [P] Run the full manual reproduction in `specs/027-chat-fixup/quickstart.md` and confirm all Success Criteria SC-001…SC-007.
- [x] T045 [P] Run Checkstyle/Google Java Style + ESLint; fix any violations introduced by this feature.
- [x] T046 Remove the duplicate `frontend/src/components/chat/ChatPanel.test.tsx` vs `frontend/tests/components/chat/ChatPanel.test.tsx` divergence if the guard change lands in only one (keep a single source of truth).
- [x] T047 Update `CLAUDE.md` "Recent Changes" if needed and ensure `specs/027-chat-fixup/quickstart.md` reflects final commands.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: none blocking (documented).
- **User Stories (Phases 3–8)**: all can start after Setup. Recommended order by priority
  P1→P3. Cross-story notes:
  - US1 (T010) and US3 (T020) both edit `ChatConfig.widgetPromptGuidance()` — coordinate.
  - US3 render policy is testable alone, but its job/skill deep-link *destinations* only
    fully work once US4 wires the drawers (T031) and supplies ids (T026–T029).
  - US5 e2e assumes US1–US4 behaviour exists; run its assertions after those land.
  - US6 is fully independent (deployment/observability).
- **Polish (Phase 9)**: after desired stories complete.

### Within Each User Story

- Tests written first and failing → implementation → re-run tests green.
- Backend record/type changes before mapper/consumer changes.

### Parallel Opportunities

- Setup T001/T002 sequential (T002 needs a running stack).
- US1 tests T003–T006 in parallel; US4 T026/T027/T029/T030/T032 in parallel; US6 T040/T042/T043 in parallel.
- US2, US5, US6 can be worked independently of US1/US3/US4 by different developers.

---

## Parallel Example: User Story 1

```bash
# Launch US1 test tasks together (different files):
Task: "Reducer STREAM_END reconcile test in frontend/src/components/chat/chatStreamReducer.test.ts"
Task: "Reducer empty-content fallback test in frontend/src/components/chat/chatStreamReducer.test.ts"
Task: "ChatPanel single-send test in frontend/tests/components/chat/ChatPanel.test.tsx"
Task: "ChatConfigPromptTest band-aid-removed assertion in backend/.../chat/ChatConfigPromptTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → 2. Phase 3 US1 (one clean answer) → 3. STOP & VALIDATE live → deploy/demo.

### Incremental Delivery

US1 (MVP) → US2 (tool labels) → US3 (safe rendering) → US4 (deep links) → US5 (e2e lock-in)
→ US6 (observability). Each is an independently testable, deployable increment.

---

## Notes

- [P] = different files, no dependencies.
- Verify tests fail before implementing.
- Do NOT remove the band-aid prompt line (T010) until T009 confirms the real fix works live.
- Do NOT enable `rehype-raw`; safety is enforced in the render policy (T018/T019).
- Prod `.env` reconciliation + service restart are owner-executed (T043), not automated here.
