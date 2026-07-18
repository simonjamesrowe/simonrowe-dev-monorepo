# Feature Specification: Chat — reliable on-topic answers + live web search

**Feature Branch**: `028-chat-ontopic-web-search`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "Chat: reliable on-topic answers + live web search"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reliably answer on-topic questions (Priority: P1)

A visitor to Simon's portfolio site opens the chat assistant and asks about things the
site already covers — "what is he blogging about recently", "what's happening most
recently in Spring news", "what does he write about", "who are you", or a conversational
follow-up like "I don't think you answered that". The assistant answers each of these
using the site's own content and tools instead of returning a canned refusal.

**Why this priority**: This is the core defect. Today legitimate, in-domain questions are
intermittently blocked by a domain-blind gate, which directly damages the primary purpose
of the assistant. Fixing this restores baseline trust and usefulness and is independently
valuable even without web search.

**Independent Test**: Ask the set of previously-refused, in-domain questions (recent
blogs, tech/AI/Spring news, events, skills, jobs, profile, code examples, greetings, meta
questions, and short follow-ups) and confirm the assistant answers rather than deflects.

**Acceptance Scenarios**:

1. **Given** the chat assistant is available, **When** a visitor asks "what is he blogging about recently", **Then** the assistant answers using recent blog content rather than returning the canned refusal.
2. **Given** the chat assistant is available, **When** a visitor asks "what's happening most recently in Spring news", **Then** the assistant answers using aggregated news rather than deflecting.
3. **Given** a prior answer was given, **When** the visitor replies "I don't think you answered that", **Then** the assistant treats it as an on-topic follow-up and responds rather than deflecting.
4. **Given** the chat assistant is available, **When** a visitor asks a meta question such as "who are you" or "what can you do", **Then** the assistant answers.

---

### User Story 2 - Stay on topic and deflect unrelated/harmful input (Priority: P1)

A visitor asks something clearly unrelated to Simon or his technical domains (e.g. "what's
the weather", "write my essay", general life advice), or attempts a jailbreak / prompt
injection / harmful request. The assistant declines with the existing polite deflection
message and does not attempt to answer.

**Why this priority**: The relaxed guardrail must not become a general-purpose chatbot or
an injection vector. Preserving the boundary is as critical as fixing false refusals — the
two must be balanced together, so this is also P1.

**Independent Test**: Ask a set of clearly off-topic questions and known jailbreak/harmful
prompts and confirm the assistant returns the deflection message and does not run tools.

**Acceptance Scenarios**:

1. **Given** the chat assistant is available, **When** a visitor asks "what's the weather today", **Then** the assistant returns the polite deflection message.
2. **Given** the chat assistant is available, **When** a visitor submits a prompt-injection or jailbreak attempt, **Then** the assistant refuses with the deflection message and does not follow the injected instructions.
3. **Given** the guardrail check cannot complete (error, empty, or unavailable), **When** a visitor sends a message, **Then** the assistant proceeds to answer rather than blocking (fail-open).

---

### User Story 3 - Enrich answers with live web search (Priority: P2)

A visitor asks about a company Simon has worked at, a technology or skill he lists, or a
source/author in his content, and wants current information that isn't in the site's own
data. The assistant searches the live web, incorporates the results, and cites its sources
as inline links.

**Why this priority**: This is a genuine enhancement that extends the assistant's
usefulness, but the assistant remains valuable without it (User Stories 1 and 2 deliver a
working MVP). It also depends on an external service and an API key, so it is sequenced
after the core fix.

**Independent Test**: Ask a Simon-grounded question that benefits from current external
information (e.g. recent news about a company in his job history) and confirm the assistant
performs a web search, surfaces a "Searching the web" progress indicator, and cites
sources as inline links. Ask an unrelated question and confirm web search is not used.

**Acceptance Scenarios**:

1. **Given** web search is configured, **When** a visitor asks about current information regarding a company in Simon's job history, **Then** the assistant may search the web and cites sources as inline links.
2. **Given** web search is used, **When** the search runs, **Then** the visitor sees a "Searching the web" progress indicator consistent with the other tool indicators.
3. **Given** web search is not configured or the search fails, **When** the assistant would otherwise use it, **Then** the assistant continues to answer gracefully from available data and does not error out.
4. **Given** a visitor asks a question unrelated to Simon's profile/experience/skills, **When** the assistant responds, **Then** web search is not invoked.

---

### Edge Cases

- **Ambiguous follow-ups**: Short conversational replies ("no", "why?", "I don't think you answered that") must be treated as on-topic when they follow an on-topic exchange, not blocked as ambiguous.
- **Uncertain classification**: When the guardrail cannot confidently classify a message, it must bias toward allowing the answer (SAFE) rather than deflecting.
- **Web search for unrelated topics**: The model must not use web search for general/unrelated questions even though the tool exists.
- **Missing API key**: When the web search credential is absent, the feature degrades to a short "web search is unavailable" response rather than failing the whole answer.
- **Empty or blank search query**: A blank or empty query must be handled without error and without an external call.
- **External service failure/timeout**: Web search failures must not throw; the assistant returns whatever it can from other sources.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The assistant MUST answer questions that the site's own data and tools cover — recent blogs, aggregated tech/AI/Spring news, community events, skills, jobs/companies, profile/bio/contact, and code examples — without returning the canned refusal.
- **FR-002**: The on-topic boundary MUST include general questions about technologies, companies, or people connected to Simon's work (e.g. "what is Kafka", "tell me about a company he worked at"), greetings, meta questions ("who are you", "what can you do"), and short conversational follow-ups.
- **FR-003**: The assistant MUST deflect clearly unrelated questions (e.g. weather, cooking, "write my essay", general life advice) that have no connection to Simon or his technical domains, using the existing polite deflection message.
- **FR-004**: The assistant MUST deflect harmful, hateful, jailbreak, and prompt-injection input using the deflection message and MUST NOT follow instructions embedded in user input.
- **FR-005**: When classification is uncertain, the guardrail MUST bias toward allowing the answer rather than deflecting.
- **FR-006**: The guardrail MUST fail open — if the classification check is empty, errors, or is otherwise unavailable, the assistant MUST proceed to answer.
- **FR-007**: The deflection behaviour and message MUST remain unchanged from the current experience (same message, triggered on off-topic and harmful classifications).
- **FR-008**: The classification logic MUST be applied consistently for both non-streaming and streaming responses (no divergence between the two paths).
- **FR-009**: The assistant MUST offer a live web search capability that it can choose to invoke to obtain current external information.
- **FR-010**: Web search MUST be used only to enrich topics grounded in Simon's profile, experience, skills, or content sources — a company he worked at, a technology/skill he lists, or an author/source in his content — and MUST NOT be used for general or unrelated questions.
- **FR-011**: When web search results are used, the assistant MUST cite the sources as inline links within its answer, reusing the existing link rendering rules (no new frontend widget).
- **FR-012**: When web search runs, the assistant MUST surface a progress indicator ("Searching the web") consistent with the presentation of the other assistant tools.
- **FR-013**: Web search MUST degrade gracefully — when the required credential is missing or the external call fails/times out, the capability MUST return a short "unavailable" response, log a warning, and MUST NOT break the overall answer.
- **FR-014**: Web search MUST handle empty or blank queries without performing an external call and without error.
- **FR-015**: The web search credential MUST be supplied via environment configuration and MUST default to unset/blank, so the system runs without web search until a key is provided.
- **FR-016**: The change MUST require no frontend or widget changes.

### Key Entities *(include if feature involves data)*

- **Message classification**: The categorization of an incoming user message as on-topic (answerable), off-topic (deflect), or harmful (deflect), used to gate whether the assistant answers.
- **Web search result**: A single external result surfaced by web search, consisting of a title, a source link, and a short snippet, used by the assistant to enrich and cite its answer.
- **Web search configuration**: The external service credential and result-count settings that enable and bound the web search capability.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the previously-reported false-refusal questions (recent blogs, Spring/tech/AI news, "what does he write about", and the follow-up "I don't think you answered that") are answered rather than deflected.
- **SC-002**: Clearly unrelated questions (e.g. weather, cooking, "write my essay") are deflected in at least 95% of trials across a representative test set.
- **SC-003**: Known jailbreak/prompt-injection attempts are deflected in 100% of trials in the test set, and embedded instructions are never followed.
- **SC-004**: When the guardrail check is unavailable, the assistant still answers in 100% of cases (no hard block on error).
- **SC-005**: For Simon-grounded questions needing current external information, the assistant cites at least one external source as an inline link when web search is used.
- **SC-006**: When the web search credential is unset or the external service is unavailable, the assistant still returns a coherent answer in 100% of cases (no user-visible error).
- **SC-007**: Web search is not invoked for unrelated questions in at least 95% of trials across a representative test set.
- **SC-008**: The automated backend test suite passes with the new and updated tests included.

## Assumptions

- The existing chat assistant, its tools (recent blogs, news, blog search, skills, jobs, profile, code examples), and the current deflection message and behaviour are retained; this feature adjusts the gate and adds one capability.
- The pre-answer classification gate is kept (not removed); its instructions are made domain-aware and biased toward allowing answers.
- Web search uses a free-tier external search service; the model decides when to call it. The credential is user-supplied and blank by default.
- Web search results are cited inline as links using the existing safe link-rendering rules; no new frontend components are introduced.
- A short timeout and a small result count (around five) bound web search latency and cost.
- Deep page-fetching / full-article reading is out of scope for this round.
