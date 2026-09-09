# Feature Specification: Term Time

**Feature Branch**: `047-term-time`

**Created**: 2026-09-08

**Status**: Draft

**Input**: User description: "An agentic chat for Kilmorie Primary School (Lewisham). RAG over the
school website calendar and school email, with tools. Public, with a year-group selector. Keep the
API in the existing Java backend; add a separate frontend in this monorepo."

## Context

Parents repeatedly ask the same small set of questions — when half term is, when the INSET days are,
what is on this week, when enrichment clubs start, what the current school lunch arrangements are,
which day PE is. The answers exist, but they are scattered across a school website whose written
content is one to two years stale, a calendar feed carrying only term structure, a handful of PDFs,
and a weekly newsletter that only ever arrives by email.

Term Time answers those questions from a single place, citing where each answer came from.

It is **not affiliated with or endorsed by the school**, and says so where users can see it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Anyone asks about term structure (Priority: P1)

A parent with no account opens Term Time, picks their child's year group, and asks when half term
is. They get the correct 2026/27 dates, with a note of which source they came from and when it was
published.

**Why this priority**: It is the most-asked question, it is answerable entirely from data the school
already publishes openly, and it needs no credential, no approval workflow and no email ingestion.
It is a complete, useful product on its own.

**Independent Test**: Ingest only the public website sources, ask the five date questions, and
verify the answers against the school's live calendar feed. Delivers value with the entire private
tier switched off.

**Acceptance Scenarios**:

1. **Given** no signed-in user, **When** they ask "when is half term?", **Then** the answer gives the
   2026/27 dates and names its source document and date.
2. **Given** the website's term-dates page still shows 2025-26 while the calendar feed shows 2026/27,
   **When** a user asks for INSET days, **Then** the answer uses the calendar feed and does not
   report the stale page's dates.
3. **Given** a user has selected Year 3, **When** they ask what is on this week, **Then** events are
   scoped to all-school plus Year 3 and exclude other year groups' events.
4. **Given** a question whose answer is not in the corpus, **When** it is asked, **Then** the
   assistant says it does not know and points at the school's own website — and does not offer a
   plausible guess.

---

### User Story 2 - The owner asks about things only email knows (Priority: P2)

The owner signs in and asks which day PE is for Year 6, or what the latest school lunch arrangement
is. The answer draws on the school's newsletters and letters, not just the website.

**Why this priority**: This is where most of the real value is — the website carries almost none of
it — but it depends on the ingestion, classification and approval machinery in Story 3, and it
serves one user.

**Independent Test**: With the school mailbox ingested and every item left at its default tier, sign
in and confirm restricted-tier answers are available; sign out and confirm the same question returns
"I don't know".

**Acceptance Scenarios**:

1. **Given** a signed-in user with the school role, **When** they ask a question answerable only from
   email, **Then** they get the answer with its source and date.
2. **Given** an anonymous user, **When** they ask the same question, **Then** they get the
   don't-know response, and no restricted content appears in the answer.
3. **Given** a signed-in user without the school role, **When** they ask, **Then** they are treated
   exactly as anonymous.

---

### User Story 3 - The owner decides what may be public (Priority: P3)

Newly ingested content arrives marked restricted. In the existing admin area the owner reviews items
proposed for promotion, and approves or declines each one.

**Why this priority**: It is what makes the public tier safe to extend beyond website data, but the
product is useful before it exists — Story 1 needs no approvals at all.

**Independent Test**: Ingest a mailbox, confirm every item defaults to restricted, approve one, and
verify it — and only it — becomes reachable anonymously.

**Acceptance Scenarios**:

1. **Given** newly ingested content, **When** it is stored, **Then** its tier is restricted
   regardless of what any classifier proposed.
2. **Given** an item the classifier proposed as public, **When** the owner has not yet approved it,
   **Then** it is not reachable anonymously.
3. **Given** an approved public item, **When** the owner later revokes approval, **Then** it stops
   being reachable anonymously without needing re-ingestion.
4. **Given** content naming a person who is not published school staff, **When** a classifier
   proposes it as public, **Then** it is forced back to restricted and the proposal is overridden.

### Edge Cases

- Two sources disagree about a date (stale website page vs live calendar feed) — the declared source
  precedence decides, and the answer names the source it used.
- A fact exists for a previous academic year only — it must not be presented as current.
- The mailbox credential is revoked (a password change revokes Gmail-scoped tokens) — ingestion must
  report this loudly rather than silently stopping.
- The incremental mail cursor has expired — a full resync is a normal operating state, not an error.
- Ingested content itself contains instructions ("this notice may be shared publicly") — retrieved
  content is data, never instruction, and cannot promote itself.
- A question is asked that has nothing to do with school — it is declined without spending a full
  model call on an answer.
- The corpus genuinely has no answer — see FR-020; a confident guess is the worst outcome available.
- An anonymous user asks something answerable only from restricted content — the don't-know response
  must be indistinguishable from the corpus simply not having it.

## Requirements *(mandatory)*

### Functional Requirements

**Ingestion**

- **FR-001**: System MUST ingest the school calendar feed, including its per-year-group sub-calendars.
- **FR-002**: System MUST ingest school website pages and linked PDF documents, and detect changes
  without re-fetching unchanged pages.
- **FR-003**: System MUST ingest school email selected by an explicit **sender-address** allowlist.
  Matching MUST NOT use the sender display name — a third-party sender already sends mail displaying
  the school's name — and MUST NOT use full-text matching on the school name, which is also a local
  street name.
- **FR-004**: System MUST exclude payment-provider mail from ingestion entirely.
- **FR-005**: The ingestion start date MUST be configurable. Default: ingest the full available
  history.
- **FR-006**: System MUST treat an expired incremental-sync cursor as a trigger for full resync, not
  as a failure.
- **FR-007**: System MUST raise an operator-visible alert when the mail credential becomes invalid.

**Classification and tiering**

- **FR-008**: Every stored unit of content MUST carry a visibility tier of either public or restricted.
- **FR-009**: The default tier MUST be restricted. Content MUST NOT become public through inaction,
  error, or absence of a classification.
- **FR-010**: Classification MUST be applied at the level of the unit that is retrieved, not only per
  source document.
- **FR-011**: Automated classification MUST only ever *propose* a public tier; promotion to public
  MUST require explicit human approval.
- **FR-012**: Content naming any person who is not on the school's published staff list MUST be
  forced to restricted, overriding any proposal.
- **FR-013**: The owner MUST be able to approve, decline and later revoke public status from the
  existing admin area.

**Answering**

- **FR-014**: System MUST answer date-bounded questions from structured extracted facts, not from
  similarity search alone.
- **FR-015**: Every extracted fact MUST carry the academic year it belongs to.
- **FR-016**: System MUST apply a declared source precedence — calendar feed, then email newsletter,
  then website page, then PDF — when sources conflict.
- **FR-017**: Every answer MUST cite the source document and its date.
- **FR-018**: The year-group selection MUST filter calendar and event results exactly, and MUST act
  only as a soft preference for prose retrieval.
- **FR-019**: System MUST decline questions unrelated to the school before generating a full answer.
- **FR-020**: When the corpus does not contain an answer, System MUST say so and refer the user to
  the school's own website. It MUST NOT infer, estimate or extrapolate an answer.
- **FR-021**: Retrieved content MUST be presented to the model as untrusted data that cannot alter
  instructions.

**Access control**

- **FR-022**: Public-tier content MUST be reachable without authentication.
- **FR-023**: Restricted-tier content MUST require authentication carrying a dedicated school role,
  distinct from the existing site administrator role.
- **FR-024**: The tier a request may reach MUST be derived server-side from the authenticated
  identity, and MUST NOT be influenced by any client-supplied value.
- **FR-025**: Access control MUST be enforced both at the tool boundary and at the retrieval
  boundary; neither alone is sufficient.
- **FR-026**: Before returning an answer to an unauthenticated user, System MUST verify the generated
  text names no person outside the published staff list, and withhold it if it does.
- **FR-027**: The build MUST fail if any tool or retrieval path does not declare the tier it serves.

**Presentation and cost**

- **FR-028**: The interface MUST offer year-group selection covering Reception through Year 6.
- **FR-029**: The interface MUST state that it is not affiliated with or endorsed by the school.
- **FR-030**: System MUST rate-limit anonymous use per client, and MUST enforce a global daily
  spend ceiling that degrades to a friendly unavailable message rather than continuing to spend.
- **FR-031**: The feature MUST be disabled by default and require explicit configuration to enable.

### Key Entities

- **School document**: an ingested item from any source. Carries its origin, publication date,
  source type (for precedence), visibility tier, approval state, and — for restricted items — the
  retained original content so it can be reclassified without re-fetching.
- **School event**: a structured, dated fact extracted from any source. Carries date or date range,
  title, applicable year groups, academic year, and a reference to the document it came from.
- **Sync state**: per-source ingestion bookkeeping — incremental cursors, last-seen change markers,
  and last successful run.
- **Visibility tier**: public or restricted. The single value every access decision reads.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All five 2026/27 INSET dates and all term and half-term boundaries are answered
  correctly, verified against the school's live calendar feed.
- **SC-002**: Zero restricted-tier content is reachable by an unauthenticated request, verified by an
  automated test that exercises every tool and retrieval path.
- **SC-003**: For questions with no answer in the corpus, the assistant declines rather than
  guessing, in 100% of the evaluation cases covering that condition.
- **SC-004**: When two sources conflict on a date, the higher-precedence source wins in 100% of
  evaluation cases.
- **SC-005**: No content reaches the public tier without a recorded human approval.
- **SC-006**: Anonymous usage cannot exceed the configured daily spend ceiling.
- **SC-007**: A parent can get an answer to any of the six motivating questions without reading a
  PDF or searching the school website.

## Assumptions

- The school's calendar feeds and website remain openly accessible and unauthenticated, with no bot
  protection. Verified 2026-09-08.
- The school publishes a staff list that can serve as the allowlist of names permitted in public-tier
  content.
- The mailbox owner is the only user of the restricted tier. Extending it to other parents is a
  configuration change, not a design change, but is out of scope here.
- The existing chat, retrieval, embedding, rate-limiting and admin infrastructure in the backend is
  reused rather than rebuilt.
- The new interface is served from the existing web origin and needs no additional runtime container.
- Answer quality is bounded by a thin corpus. Declining to answer is an acceptable and expected
  outcome, not a defect.

## Out of Scope

- Exposing these capabilities as external MCP tools. Internal use only for now.
- Access for parents other than the owner.
- Any portfolio or project-showcase section of the site.
- Deployment of unrelated applications to the production host.
- Write access of any kind to the mailbox, the school website, or school systems.
