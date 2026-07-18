---

description: "Task list for Chat on-topic guardrail + live web search"
---

# Tasks: Chat — reliable on-topic answers + live web search

**Input**: Design documents from `/specs/028-chat-ontopic-web-search/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included — the feature spec (Tests section) and SC-008 explicitly require updated
`GuardrailAdvisorTest` and a new `WebSearchToolsTest`, and `../gradlew test` must pass.

**Organization**: Tasks are grouped by user story. US1 and US2 are both P1 and both realized by
the single domain-aware rewrite of `GuardrailAdvisor` (they are two behavioural halves of the
same change); they share files and so run sequentially. US3 (P2, web search) is independent.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3
- All paths are absolute-from-repo-root under `backend/`.

## Path Conventions

- Web app: backend module at `backend/src/main/java/com/simonrowe/...`, tests at
  `backend/src/test/java/com/simonrowe/...`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration plumbing shared by the web-search story; inert by default.

- [X] T001 Add web search config block to `backend/src/main/resources/application.yml`: `web-search.searxng.base-url: ${SEARXNG_URL:}` and `web-search.searxng.max-results: 5`.
- [X] T002 [P] Add `SEARXNG_URL=` (blank default) to `backend/.env`.
- [X] T003 [P] Add the internal `searxng` service (no published ports) + `config/searxng/settings.yml` (JSON format on, limiter off) to `docker-compose.prod.yml`, and set `SEARXNG_URL: http://searxng:8080` in the backend service `environment:`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: No cross-story foundational code is required — the guardrail change (US1/US2) and
the web-search tool (US3) each stand alone on top of existing chat infrastructure
(`ChatConfig`, `ChatStreamPublisher`, `ProfileMcpTools`). This phase is intentionally empty.

**Checkpoint**: Setup complete → user stories can begin.

---

## Phase 3: User Story 1 - Reliably answer on-topic questions (Priority: P1) 🎯 MVP

**Goal**: Domain-aware guardrail so in-domain questions (recent blogs, tech/AI/Spring news,
events, skills, jobs, profile, code, greetings, meta, short follow-ups) are answered, not
refused.

**Independent Test**: Ask the previously-refused in-domain questions and confirm the assistant
proceeds to answer rather than returning the canned deflection.

### Tests for User Story 1 ⚠️ (write first, must fail before implementation)

- [X] T004 [US1] In `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java`, add/adjust tests asserting: a SAFE-classified input proceeds down the chain (`chain.nextCall`/`nextStream` invoked, no deflection) for both `adviseCall` and `adviseStream`; and assert the classification prompt passed to the mocked `ChatModel` now contains the domain description (blogs/news/events/skills/jobs/profile and the SAFE-bias wording).

### Implementation for User Story 1

- [X] T005 [US1] In `backend/src/main/java/com/simonrowe/chat/GuardrailAdvisor.java`, extract the duplicated classification prompt into a single shared helper/constant used by both `adviseCall` and `adviseStream` (no behaviour change yet).
- [X] T006 [US1] Rewrite the shared classification prompt in `GuardrailAdvisor.java` to describe boundary A (SAFE = Simon/career/bio/contact, blogs, skills, jobs/companies, code, aggregated tech/AI/Spring news, community events, tech/company/people connected to his work, greetings, meta, short follow-ups) and instruct "bias to SAFE when uncertain; only block the obvious cases", keeping `gpt-4o-mini` @ temp 0, `getOrder() == 0`, and fail-open behaviour unchanged.

**Checkpoint**: In-domain questions answer; fail-open preserved. US1 independently testable.

---

## Phase 4: User Story 2 - Stay on topic and deflect unrelated/harmful input (Priority: P1)

**Goal**: The relaxed guardrail still deflects clearly unrelated questions and
harmful/jailbreak/prompt-injection input with the unchanged canned message, and still fails
open on error.

**Independent Test**: Ask off-topic questions (weather, cooking, "write my essay") and known
jailbreak prompts and confirm the deflection message is returned and no tools run; simulate a
classifier error/empty response and confirm the assistant proceeds (fail-open).

> Note: US2 is realized by the same `GuardrailAdvisor` prompt (T006) — the OFF_TOPIC/HARMFUL
> branches in the prompt. These tasks add the negative-path tests and verify plumbing is
> unchanged; they share `GuardrailAdvisor.java`/`GuardrailAdvisorTest.java` with US1 (sequential).

### Tests for User Story 2 ⚠️

- [X] T007 [US2] In `backend/src/test/java/com/simonrowe/chat/GuardrailAdvisorTest.java`, add/keep tests asserting: `OFF_TOPIC` and `HARMFUL` classifications return the unchanged canned deflection message (for both `adviseCall` and `adviseStream`) and do NOT call the chain; and fail-open cases (null/empty classification response, and `ChatModel` throwing) proceed down the chain.

### Implementation for User Story 2

- [X] T008 [US2] Verify in `GuardrailAdvisor.java` that the deflection branch (`contains("OFF_TOPIC") || contains("HARMFUL")`), the exact canned message text, and the fail-open early-returns remain unchanged after the US1 rewrite; adjust only if the refactor in T005/T006 altered them.

**Checkpoint**: Off-topic/harmful deflected, fail-open intact. US1 + US2 both hold.

---

## Phase 5: User Story 3 - Enrich answers with live web search (Priority: P2)

**Goal**: A `webSearch` `@Tool` (self-hosted SearXNG-backed) the model can call to enrich Simon-grounded
topics, citing sources inline as markdown links, with a "Searching the web" progress indicator
and graceful degradation when unconfigured/failing.

**Independent Test**: With a key set, ask a Simon-grounded current-info question → tool runs,
"Searching the web" indicator shows, answer cites inline links. Unset the key → assistant still
answers gracefully. Ask an unrelated question → tool not invoked.

### Tests for User Story 3 ⚠️ (write first, must fail before implementation)

- [X] T009 [P] [US3] Create `backend/src/test/java/com/simonrowe/websearch/WebSearchToolsTest.java` asserting, with a mocked `SearxngClient`: result mapping (`title`/`url`/`snippet`) from client results; blank/null query returns without calling the client; unconfigured (blank base URL) returns the "web search is unavailable" message with no client call; a client exception is caught and converted to the "unavailable" message (never throws); and missing sessionId skips tool labels.

### Implementation for User Story 3

- [X] T010 [P] [US3] Create `backend/src/main/java/com/simonrowe/websearch/WebSearchResult.java` — a record `{ String title, String url, String snippet }`.
- [X] T011 [P] [US3] Create `backend/src/main/java/com/simonrowe/websearch/SearxngClient.java` — a Spring bean using `RestClient` GETting `{base-url}/search?q={query}&format=json`, short (~5s) timeout, mapping `results[]` (skip blank title/url, cap at max-results) to `List<WebSearchResult>`; reads `web-search.searxng.base-url`/`max-results` via `@Value`; `isConfigured()` = base URL non-blank; a separate injectable bean so it is mockable.
- [X] T012 [US3] Create `backend/src/main/java/com/simonrowe/chat/WebSearchTools.java` — `@Tool webSearch(query, ToolContext)` with the boundary-A description; short-circuit unavailable when base URL blank and empty/blank query; on success call `SearxngClient` and return `List<WebSearchResult>`; catch client errors → "web search is unavailable" (warn log, never throws); publish `toolStart`/`toolEnd` "Searching the web" via `ChatStreamPublisher` using `sessionId` from `ToolContext` (null-safe), mirroring `ProfileMcpTools`; annotate with `@WithSpan`.
- [X] T013 [US3] Register the tool in `backend/src/main/java/com/simonrowe/chat/ChatConfig.java`: inject `WebSearchTools` and change to `.defaultTools(profileMcpTools, webSearchTools)`.
- [X] T014 [US3] Update `chat.system-prompt` in `backend/src/main/resources/application.yml`: add a `webSearch` bullet to the tools list with a one-line usage rule mirroring boundary A (enrich Simon-grounded topics; cite sources as markdown links; do not use for unrelated questions), and add a short nudge to use `getRecentBlogs`/`searchNews` for "what's he writing about lately" / "what's new in Spring/AI news".

**Checkpoint**: Web search enriches Simon-grounded answers, degrades gracefully, no frontend changes.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T015 Run `cd backend && ../gradlew checkstyleMain checkstyleTest` (or `../gradlew check`) and fix any Google-style violations in the new/edited files.
- [X] T016 Run `cd backend && ../gradlew test` and confirm the full suite (including `GuardrailAdvisorTest` and `WebSearchToolsTest`) passes.
- [ ] T017 Execute the manual verification steps in `specs/028-chat-ontopic-web-search/quickstart.md` (guardrail on/off-topic/jailbreak/fail-open; web search with and without a key).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately. (Only strictly needed for US3.)
- **Foundational (Phase 2)**: Empty.
- **US1 (Phase 3)** and **US2 (Phase 4)**: Independent of Setup; both edit `GuardrailAdvisor.java`
  and `GuardrailAdvisorTest.java`, so they run **sequentially** (US1 rewrite first, then US2
  negative-path tests/verification).
- **US3 (Phase 5)**: Depends on Setup (Phase 1) for config/env. Independent of US1/US2 — can run
  in parallel with the guardrail work (different files).
- **Polish (Phase 6)**: After all desired stories are complete.

### User Story Dependencies

- **US1 (P1)**: MVP. No dependency on other stories.
- **US2 (P1)**: Same file as US1 → sequence after US1; no logical dependency otherwise.
- **US3 (P2)**: Needs Phase 1 config. Independent of US1/US2.

### Within Each User Story

- Tests written first and failing before implementation (T004 before T005/T006; T009 before
  T010–T012).
- `WebSearchResult` + `SearxngClient` before `WebSearchTools`; `WebSearchTools` before
  `ChatConfig` registration.

### Parallel Opportunities

- T002 and T003 [P] (different files) alongside T001.
- The guardrail work (US1/US2) can proceed in parallel with the web-search work (US3) —
  disjoint files.
- Within US3: T009, T010, T011 are [P] (distinct new files); T012 depends on T010/T011; T013
  depends on T012; T014 is config-only.

---

## Parallel Example: User Story 3

```bash
# New, independent files can be created together:
Task: "Create WebSearchToolsTest.java (mocked SearxngClient assertions)"
Task: "Create WebSearchResult.java record"
Task: "Create SearxngClient.java (RestClient + mapping + timeout)"
# Then sequentially: WebSearchTools.java → ChatConfig registration → system-prompt.
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Phase 1 Setup (optional if only shipping the guardrail fix).
2. US1 (Phase 3): rewrite guardrail domain-aware → in-domain questions answer.
3. US2 (Phase 4): confirm deflection + fail-open unchanged.
4. **STOP and VALIDATE**: run `GuardrailAdvisorTest`; manual guardrail checks from quickstart.
5. Deploy/demo — this alone resolves the production defect.

### Incremental Delivery

1. Guardrail fix (US1+US2) → test → deploy (MVP).
2. Add web search (US3) → test with/without key → deploy.
3. Polish (Phase 6) → full `check` + quickstart validation.

---

## Notes

- [P] = different files, no dependencies.
- No persistence, no schema changes, no frontend changes.
- Keep `gpt-4o-mini` @ temp 0 and `getOrder() == 0` for the guardrail; keep the exact canned
  deflection message.
- `SearxngClient` is mocked with Mockito in tests (external HTTP client, no Testcontainer needed).
- Commit after each task or logical group.
