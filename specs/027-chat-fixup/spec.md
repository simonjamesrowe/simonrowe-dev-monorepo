# Feature Specification: Chat Drawer Fix-Up

**Feature Branch**: `027-chat-fixup`

**Created**: 2026-07-17

**Status**: Draft

**Input**: Design doc: `docs/superpowers/specs/2026-07-17-chat-fixup-design.md`

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One clean, correctly-ordered answer (Priority: P1)

A visitor opens the "Ask me anything" chat drawer and asks a question such as
"what software development skills does he have on the front end and the back end?".
They receive exactly one assistant response bubble whose text reads correctly —
no duplicated answer, no scrambled words, no broken markdown.

**Why this priority**: The double-answer and text-scramble defects make the chat
look broken and untrustworthy. This is the most visible, most damaging problem and
must be fixed first; every other improvement is undermined if the core answer is wrong.

**Independent Test**: Ask any question in the chat and confirm a single assistant
bubble appears, containing coherent, correctly-ordered prose that matches the
model's output verbatim.

**Acceptance Scenarios**:

1. **Given** the chat drawer is open, **When** the visitor sends a prompt, **Then**
   exactly one assistant bubble is rendered for that prompt.
2. **Given** a streamed answer arrived with messy/interleaved intermediate chunks,
   **When** the stream ends, **Then** the final rendered text matches the server's
   authoritative full response with no transpositions or broken links.
3. **Given** the visitor opens the drawer via an initial query or after a reconnect,
   **When** the prompt is processed, **Then** the prompt is sent and answered only once.

---

### User Story 2 - Clear tool-activity labels (Priority: P2)

While the assistant works on an answer, the visitor sees a clear, contextual label for
each tool being used (e.g. "Looking up Simon's skills"), both while running and once
finished — never a generic placeholder such as "Used 1 tool".

**Why this priority**: Meaningful progress feedback builds trust and explains latency,
but the answer itself (P1) matters more. Low implementation risk.

**Independent Test**: Ask a question that triggers a tool, and confirm the running and
finished states both display the friendly tool label rather than "Used 1 tool".

**Acceptance Scenarios**:

1. **Given** a tool is running, **When** the activity block renders, **Then** it shows a
   spinner plus the contextual label.
2. **Given** a tool has finished, **When** the activity block renders, **Then** it shows
   a checkmark plus the same contextual label (not "Used 1 tool"), with no expander to open.
3. **Given** the assistant uses multiple tools in one turn, **When** the activity renders,
   **Then** one labelled line appears per tool, stacked in order.

---

### User Story 3 - Rich, safe links and images in answers (Priority: P2)

Answer prose can link to relevant site content and embed relevant images. Internal links
navigate within the site without a full reload; external links open in a new tab; images
render inline. Crucially, the assistant can never produce a fabricated or unsafe link or
image — anything not backed by a real, provided URL renders as plain text and is never a
live/dangerous link.

**Why this priority**: Rich rendering makes answers far more useful, but it depends on the
answer being clean (P1) and must be safe by construction. High value, moderate complexity.

**Independent Test**: Trigger an answer that references a blog/role/skill/news item and
confirm links navigate correctly and images render; craft/verify a fabricated URL case and
confirm it renders as plain text.

**Acceptance Scenarios**:

1. **Given** an answer mentions a blog post, **When** the link is rendered, **Then** it
   navigates in-site to that blog's page without a full page reload.
2. **Given** an answer references a specific role or skill group, **When** the link is
   clicked, **Then** the site opens `/experience` with that exact drawer open.
3. **Given** an answer references a news article or event, **When** the link is rendered,
   **Then** it opens the external source in a new tab with safe rel attributes.
4. **Given** an answer contains a fabricated or non-allowlisted URL, **When** it is
   rendered, **Then** it appears as plain text (or a safe section link), never as a broken
   or dangerous live link.
5. **Given** an answer contains an image URL, **When** it is rendered, **Then** the image
   displays inline only if its source is allowlisted or from our own uploads origin;
   otherwise it is dropped.
6. **Given** an answer contains a non-`http(s)` scheme link (e.g. `javascript:`), **When**
   it is rendered, **Then** it is always stripped to plain text.

---

### User Story 4 - Item-level deep links open the right drawer (Priority: P3)

When an answer links to a specific job or skill group, following the link lands on the
experience page with that specific drawer already open. Section-level links scroll to the
correct section of the relevant page.

**Why this priority**: Enables the precise navigation promised by User Story 3, but is a
refinement layered on top of it. Requires backend id plumbing plus frontend URL wiring.

**Independent Test**: Navigate to `/experience?job=<id>` and `/experience?skillGroup=<id>`
and confirm the matching drawer auto-opens; navigate to a section hash and confirm the page
scrolls to that section.

**Acceptance Scenarios**:

1. **Given** a URL of the form `/experience?job=<jobId>`, **When** the experience page
   loads, **Then** the job drawer for that id opens automatically.
2. **Given** a URL of the form `/experience?skillGroup=<groupId>`, **When** the experience
   page loads, **Then** the skill-group drawer for that id opens automatically.
3. **Given** the auto-opened drawer is closed, **When** it closes, **Then** the query param
   is cleared so back/refresh behave correctly.
4. **Given** a URL with a section hash (e.g. `/experience#roles`, `/news-events#events`),
   **When** the page loads, **Then** it scrolls to that section.
5. **Given** a link references a stale or unknown id, **When** the page loads, **Then** it
   degrades gracefully in-site (no drawer opens / listing shown) with no error.

---

### User Story 5 - End-to-end tests lock in chat behaviour (Priority: P3)

Automated end-to-end tests drive the real chat drawer against a running stack and assert
the corrected behaviour (single bubble, coherent ordering, contextual tool label, and
link/image rendering rules), plus a read-only smoke check against production.

**Why this priority**: Protects the fixes from regression, but delivers no direct
user-facing value and depends on the other stories being implemented first.

**Independent Test**: Run the e2e suite against a local full stack and confirm the chat
flows pass; run the prod smoke check and confirm the drawer connects and returns a
non-empty answer.

**Acceptance Scenarios**:

1. **Given** a running local stack, **When** the e2e test asks a skills question, **Then**
   exactly one assistant bubble appears, the text is coherent and ordered, the contextual
   tool label renders, and the skills widget renders.
2. **Given** an answer containing an internal link, **When** the e2e test clicks it,
   **Then** navigation happens in-site (blog page, or experience with the correct drawer),
   and an answer image renders.
3. **Given** an answer containing a fabricated/non-allowlisted URL, **When** the e2e test
   inspects it, **Then** it is not rendered as a live link.
4. **Given** the deployed production site, **When** the read-only smoke test opens the chat
   drawer, **Then** it connects over WebSocket and returns a non-empty answer, with no data
   mutation.

---

### User Story 6 - Langfuse observability works and is verifiable (Priority: P3)

The site owner logs in to the Langfuse observability tool and sees a project; sending a
chat message produces a visible trace within about a minute, provisioned deterministically
so it works reliably after any redeploy.

**Why this priority**: Operational/observability value for the owner rather than the
visitor; important for maintaining the chat but not user-facing.

**Independent Test**: Log in as the admin account and confirm a project is visible; send a
chat message and confirm a corresponding trace appears in the project.

**Acceptance Scenarios**:

1. **Given** the stack has started, **When** the admin logs in to Langfuse, **Then** they
   see an organization and project associated with their account.
2. **Given** the project exists, **When** a visitor sends a chat message, **Then** a
   corresponding trace appears in the project within roughly one minute.
3. **Given** the stack is redeployed, **When** startup runs, **Then** the org, project,
   admin membership, and fixed project keys are provisioned idempotently (only created if
   absent), so observability keeps working without manual steps.

---

### Edge Cases

- A prompt sent during a WebSocket reconnect must not produce a duplicate generation or
  duplicate bubble.
- An answer that references an item whose id is stale/deleted must degrade gracefully
  (no error, no broken drawer) rather than failing.
- An answer with no usable URL for a mentioned item must link nothing (plain text), not a
  guessed URL.
- Multiple tools invoked in a single turn must each get their own labelled line.
- Intermediate stream chunks that arrive scrambled must still resolve to clean final text
  once the stream ends.
- An image URL that is not allowlisted and not from our uploads origin must be dropped, not
  rendered broken.
- Because a real language model produces the answer text, e2e assertions must target
  structure/behaviour (bubble count, ordering, label presence, rendering rules), not exact
  wording.

## Requirements *(mandatory)*

### Functional Requirements

#### Tool activity display

- **FR-001**: The chat MUST display a contextual label for each tool the assistant uses,
  both while the tool is running and after it finishes.
- **FR-002**: The finished tool state MUST show the contextual label with a completion
  indicator and MUST NOT show a generic placeholder such as "Used 1 tool" or require the
  visitor to expand anything to see it.
- **FR-003**: When multiple tools are used in a single turn, the chat MUST show one
  labelled line per tool, in order.

#### One clean answer

- **FR-004**: The chat MUST render exactly one assistant response bubble per user prompt.
- **FR-005**: When a response stream ends, the system MUST reconcile the rendered answer
  text to the server's authoritative full response, so the final text is clean even if
  intermediate chunks were messy.
- **FR-006**: Text reconciliation MUST preserve non-prose blocks (tool activity and content
  widgets) and only reconcile the streamed prose text.
- **FR-007**: The system MUST ensure a single generation/subscription per prompt (no
  duplicate backend generation and no duplicate send on the initial-query/reconnect path).
- **FR-008**: Once duplicate generations are eliminated, the temporary prompt-level
  band-aid that instructs the model not to start a new answer MUST be removed.

#### Rich, safe rendering

- **FR-009**: The assistant MUST be guided to link content it references to the correct
  destination: blog mentions to their blog page, role/skill-group mentions to their
  item-level deep link, and news/events to their external source.
- **FR-010**: The assistant MUST embed an image only from a URL it was explicitly given and
  MUST NOT invent, guess, or fabricate any URL; if it has no URL for something, it links
  nothing.
- **FR-011**: The chat MUST render an internal link that matches a known site route as an
  in-site navigation (no full page reload). Recognized patterns include the home, profile,
  experience, blogs, blog-detail, and news-events routes, plus item-level query forms and
  section hashes for experience and news-events.
- **FR-012**: The chat MUST render an external `https` link as a new-tab link with safe rel
  attributes only if the URL is allowlisted; otherwise the link MUST be stripped to plain
  text.
- **FR-013**: The chat MUST strip any link using a scheme other than internal routes or
  allowlisted `https` (e.g. `javascript:`, `data:`) to plain text.
- **FR-014**: The chat MUST render an inline image only if its source is allowlisted or
  served from our own uploads origin; non-allowlisted images MUST be dropped.
- **FR-015**: Rendered images MUST be lazy-loaded, constrained to the container width with
  automatic height, and include alternative text.
- **FR-016**: The per-message allowlist MUST be built from the content widget payloads
  already streamed for that message (blog url and image, news source and image, event
  source, code/profile image URLs); internal route/anchor/query patterns are always allowed
  by pattern match.

#### Item-level deep links

- **FR-017**: The system MUST provide item identifiers for jobs and skill groups in both
  the streamed widget payloads and the corresponding tool return values, so the assistant
  can construct item-level deep links.
- **FR-018**: The experience page MUST read job/skill-group query parameters on load and
  parameter change and open the corresponding drawer automatically.
- **FR-019**: When an auto-opened drawer closes, the system MUST clear the associated query
  parameter so browser back/refresh behave correctly.
- **FR-020**: The experience and news-events pages MUST scroll to the referenced section
  when a section hash is present, and MUST expose stable section identifiers (roles, skills,
  news, events).
- **FR-021**: A link referencing a stale/unknown id MUST degrade gracefully in-site (no
  drawer opens / listing shown) without error.

#### End-to-end testing

- **FR-022**: The project MUST include an end-to-end test harness that drives the real chat
  drawer against a running local full stack.
- **FR-023**: The e2e tests MUST assert, for a skills question: exactly one assistant
  bubble, coherent/ordered text, the contextual tool label present, and the skills widget
  rendered.
- **FR-024**: The e2e tests MUST assert internal-link navigation (in-site), image
  rendering, and that a fabricated/non-allowlisted URL is not rendered as a live link.
- **FR-025**: The project MUST include a read-only production smoke check confirming the
  chat drawer opens, connects over WebSocket, and returns a non-empty answer, with no data
  mutation.
- **FR-026**: E2e assertions MUST target structure/behaviour rather than exact answer
  wording, to remain deterministic despite a real language model.

#### Observability

- **FR-027**: The deployment MUST deterministically provision the Langfuse organization,
  project, an admin membership for the owner account, and fixed project keys at startup,
  creating resources only if they do not already exist (idempotent).
- **FR-028**: The observability exporter keys and the provisioned project keys MUST be
  sourced so they agree by construction (same source values).
- **FR-029**: The system MUST evaluate enabling prompt/completion content capture in traces,
  weighing usefulness against the privacy trade-off of storing visitor chat content, and
  document the decision.
- **FR-030**: The project MUST document (and where possible script) a verification that
  sending a chat message produces a corresponding trace in the Langfuse project.

### Key Entities *(include if feature involves data)*

- **Tool activity block**: A record of one tool invocation in a turn, with a status
  (running/done) and a contextual human-readable label.
- **Chat message**: An assistant response composed of ordered blocks — prose text, tool
  activity blocks, and content widget blocks — reconciled to the server's authoritative
  full response on stream end.
- **Content widget payload**: The structured data streamed alongside an answer (blog,
  skills, employment, news, events, code), carrying identifiers and canonical/image URLs;
  the per-message link/image allowlist is derived from these.
- **Skill group / Job**: Experience items that lack their own page and open via a drawer;
  each must expose a stable identifier to support item-level deep links.
- **Observability trace**: A record of a chat generation (spans and, optionally, prompt/
  completion content) visible in the Langfuse project.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of user prompts produce exactly one assistant bubble (zero
  double-answers across the e2e and manual reproduction suite).
- **SC-002**: The final rendered answer text matches the model's output verbatim — zero
  transpositions or broken markdown in the reproduction and e2e runs.
- **SC-003**: Every finished tool block shows its contextual label; zero occurrences of the
  "Used 1 tool" placeholder.
- **SC-004**: 100% of internal links in answers navigate to the correct destination in-site
  (blog page or experience with the correct drawer open); section anchors scroll to the
  correct section.
- **SC-005**: 100% of fabricated or non-allowlisted URLs render as plain text (or a safe
  section link) and never as a live/broken/dangerous link; 100% of non-allowlisted images
  are dropped.
- **SC-006**: The end-to-end suite runs deterministically and passes on a local full stack,
  and the production smoke check confirms connection and a non-empty answer.
- **SC-007**: The owner account sees a project on login, and a chat message produces a
  visible trace within roughly one minute; provisioning survives a redeploy with no manual
  steps.

## Assumptions

- The existing chat visual style, widgets, and streaming transport are retained; this
  feature changes behaviour and rendering rules, not the transport or visual redesign.
- No new content pages are created; existing routes, sections, and drawers are reused.
- Authentication, rate limiting, and session limits are unchanged.
- The backend already accumulates the full answer and sends it on stream end; the fix
  relies on making that authoritative text win on the client.
- The backend already produces contextual tool labels; only the display of the finished
  state changes on the frontend.
- The retrieval context and widget payloads already carry canonical/image URLs; the
  allowlist is derived from data already streamed, requiring no new streaming channel.
- The production-touching Langfuse `.env` reconciliation and service restart are performed
  by the site owner outside this workspace and documented as a runbook step, not automated
  here.
- The exact observability content-capture configuration property names must be verified
  against the pinned observability library version before enabling.
