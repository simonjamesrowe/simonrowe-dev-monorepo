# Feature Specification: CoParent Assistant Action Proposals

**Feature Branch**: `simonrowe/feat/coparent-llm-actions`

**Created**: 2026-09-22

**Status**: Approved

**Input**: Turn unstructured text or a single image into private, reviewable CoParent action proposals that the submitting parent can edit, approve, or reject individually.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Turn a note into safe action cards (Priority: P1)

An authenticated parent opens Quick add from anywhere in CoParent, submits a note and optionally one supported image, and receives a private batch of typed action cards without changing family data.

**Why this priority**: Converting unstructured information into concrete, reviewable work is the feature's core value, while keeping the model outside the mutation boundary is its core safety guarantee.

**Independent Test**: Submit a note describing multiple family tasks and verify multiple proposal cards appear while all existing calendar and messaging data remains unchanged.

**Acceptance Scenarios**:

1. **Given** the feature is enabled and a parent belongs to a family, **When** they submit actionable text, **Then** they receive one or more typed proposals visible only to them and no domain record is changed.
2. **Given** a JPEG, PNG, or WebP image of a flyer within 10 MB, **When** the parent submits it, **Then** image-derived proposals are returned and neither the image bytes nor raw extracted content are retained.
3. **Given** content with no actionable family task, **When** it is analysed, **Then** the batch explains that no action was found and contains no executable proposal.
4. **Given** ambiguous content or an uncertain referenced record, **When** it is analysed, **Then** the affected card is blocked with a human-readable hint until the parent selects or supplies an exact valid target.

---

### User Story 2 - Edit and decide every proposal independently (Priority: P1)

The submitting parent reviews each proposal, corrects its type-specific fields, approves valid actions, and rejects unwanted actions without an approve-all shortcut.

**Why this priority**: Human confirmation per action is the trust boundary that makes model-assisted data entry appropriate for sensitive family data.

**Independent Test**: Edit one proposed event, approve it, reject another proposal, and verify only the approved event exists while the rest of the batch remains available.

**Acceptance Scenarios**:

1. **Given** a pending proposal, **When** the parent edits it with a current revision, **Then** the complete replacement payload is validated and the revision advances.
2. **Given** a pending valid proposal, **When** the parent approves it, **Then** the corresponding domain action is performed once and the card links to the resulting record.
3. **Given** a pending or blocked proposal, **When** the parent rejects it, **Then** it becomes terminal without changing domain data.
4. **Given** a terminal proposal or stale revision, **When** an edit or competing decision is attempted, **Then** the request is rejected without changing the action.
5. **Given** an update or delete target changed after analysis, **When** approval is attempted, **Then** the proposal returns to blocked with a stale-target explanation instead of overwriting newer data.

---

### User Story 3 - Recover safely from interruptions (Priority: P2)

A parent can return to recent private batches and safely retry an action after a transient failure without creating duplicate records or communications.

**Why this priority**: Immediate communication and calendar mutations must remain correct across retries, crashes, and unreliable networks.

**Independent Test**: Interrupt approval after the domain mutation, retry it, and verify exactly one event, conversation, message, or request exists and the proposal reconciles to applied.

**Acceptance Scenarios**:

1. **Given** a previous private batch less than seven days old, **When** its submitting parent opens Quick add, **Then** the batch and current action statuses are available.
2. **Given** an action is left applying by an interruption, **When** it is retried or reconciled, **Then** the system detects the prior marker or safely retries and produces exactly one result.
3. **Given** another family member or a member of a different family, **When** they request the batch identifier, **Then** the batch remains undisclosed.
4. **Given** a batch is seven days old, **When** retention cleanup runs, **Then** the batch expires while any already-applied domain record remains under normal retention.

---

### User Story 4 - Use Quick add across device sizes (Priority: P2)

A parent can launch Quick add from the desktop shell or mobile header, understand the provider disclosure, preview/remove an image, and continue reviewing cards without losing failed source input.

**Why this priority**: Capture happens in real-world mobile and desktop contexts, and clear disclosure/error recovery avoids accidental loss or hidden processing.

**Independent Test**: Exercise the launcher at desktop and mobile breakpoints, fail analysis once, retry successfully, and verify source memory and image URL cleanup behavior.

**Acceptance Scenarios**:

1. **Given** desktop navigation, **When** Quick add opens, **Then** it appears as a right-side sheet; on mobile it occupies the full screen.
2. **Given** multiple family memberships, **When** the drawer opens, **Then** the parent selects which family supplies context and receives actions.
3. **Given** analysis fails, **When** the error appears, **Then** text and image remain in browser memory for retry; after success or dismissal they are cleared and image resources are revoked.
4. **Given** the browser is offline, **When** the parent views Quick add, **Then** analysis and approval are unavailable with an explicit offline state.

### Edge Cases

- Text containing prompt-injection instructions is treated as untrusted source data and cannot change the proposal-only system contract.
- A request containing neither non-blank text nor an image is rejected.
- Text over 20,000 characters, images over 10 MB, unsupported extensions, declared MIME mismatches, or invalid magic bytes are rejected before inference.
- Model responses containing malformed arguments, unknown functions, too many actions, or identifiers outside the supplied family context fail safely and do not persist executable proposals.
- Deleted, system-owned, non-pending, non-requester-owned, or otherwise invalid targets become blocked or fail domain validation at edit/approval time.
- Approval retries after a network timeout do not duplicate embedded messages or newly created records.
- Batch expiry during an open drawer is reported cleanly and does not expose or recreate the expired proposal.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a global Quick add launcher to authenticated CoParent users in desktop and mobile navigation when the feature is available.
- **FR-002**: The system MUST report feature availability independently of family membership and MUST default the assistant feature to unavailable until explicitly enabled.
- **FR-003**: A submission MUST contain non-blank text of at most 20,000 characters, one supported image, or both.
- **FR-004**: The system MUST accept at most one JPEG, PNG, or WebP image of at most 10 MB and MUST validate its declared type, extension where present, and file signature.
- **FR-005**: The system MUST disclose that submitted content is sent to the configured AI provider before submission.
- **FR-006**: Analysis MUST return typed proposals only and MUST NOT perform any calendar, category, schedule, messaging, permission, family, child, invitation, approval, denial, or read-state mutation.
- **FR-007**: Supported proposals MUST be limited to event create/update/delete, non-system category create/update/delete, schedule-change creation or caller-owned pending withdrawal, conversation creation, message sending, and permission-request creation.
- **FR-008**: Each proposal MUST expose its type, editable typed payload, status, field errors, revision, target snapshot where applicable, uncertainty guidance, and resulting entity reference where applicable.
- **FR-009**: Proposal status MUST be one of blocked, pending, applying, applied, rejected, or failed.
- **FR-010**: Uncertain targets MUST use human-readable hints and remain blocked until the submitting parent selects an exact permitted record.
- **FR-011**: Each action MUST be editable, approvable, and rejectable independently; the system MUST NOT offer bulk approval.
- **FR-012**: Terminal actions MUST NOT be editable and stale revisions MUST be rejected as conflicts.
- **FR-013**: Membership, proposal ownership, referenced records, requester rules, and complete domain validation MUST be rechecked on edits and approvals.
- **FR-014**: Updates MUST merge edited fields against the current complete domain resource so omitted fields are not erased.
- **FR-015**: Update and delete proposals MUST record the target revision time observed during analysis; a changed target MUST be blocked before mutation.
- **FR-016**: Approval MUST be retry-safe across process and network failures, and each create, message, update, or delete MUST produce at most one domain effect per action.
- **FR-017**: Communication proposals MUST send immediately when individually approved.
- **FR-018**: Successful actions MUST refresh the corresponding calendar, category, schedule-request, or conversation view without closing the remaining batch review.
- **FR-019**: Proposal batches MUST be visible only to the submitting authenticated subject within the submitted family, even to another parent in that family.
- **FR-020**: The system MUST retain normalized proposal data for seven days and MUST NOT retain raw submitted text, image bytes, or prior message bodies.
- **FR-021**: The system MUST intentionally exclude ephemeral proposal batches from backups while recreating their collection and indexes after restore.
- **FR-022**: AI telemetry MUST exclude prompts, images, proposal payloads, and message text even when global content capture is enabled; only operational metadata such as model, token counts, latency, outcome, and action counts may be exported.
- **FR-023**: The model context MUST contain only the current family time, active parent/child identifiers and names, categories, pending requests, conversation metadata without bodies, and at most the nearest 500 events from 90 days before through 18 months after the current day.
- **FR-024**: The model MUST NOT receive medical notes, invitations, audits, historical message bodies, or unrelated family data.
- **FR-025**: Recent unexpired private batches MUST be available within Quick add.
- **FR-026**: Source input MUST remain only in browser memory after failed analysis and MUST be cleared, including revoked image preview resources, after successful analysis or drawer dismissal.
- **FR-027**: Analysis and approval MUST be disabled offline with a clear status; offline analysis or queued approval is out of scope.

### Key Entities

- **Proposal Batch**: A seven-day private review session belonging to one family and one submitting authenticated parent, containing model/run metadata, timestamps, and ordered proposal actions but no raw source content.
- **Proposal Action**: A discriminated, revisioned command candidate with a typed editable payload, lifecycle status, validation errors, optional target snapshot, idempotency marker, and optional resulting entity reference.
- **Target Snapshot**: The referenced record identifier and observed update time used to prevent an assistant proposal from overwriting later human changes.
- **Result Reference**: A safe reference to the domain entity created or changed by an applied action, used for navigation and retry reconciliation.
- **Family Context**: The bounded, privacy-filtered set of identifiers and metadata supplied to inference for one authorised family.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In acceptance tests, a text note can produce multiple review cards with zero domain mutations before an explicit per-card approval.
- **SC-002**: Every supported proposal type can be edited, approved, rejected, blocked, and validated through automated tests, with no unsupported mutation route exposed.
- **SC-003**: Duplicate approvals and simulated interruption windows produce exactly one domain result in 100% of tested create and communication cases.
- **SC-004**: A second parent and a parent from another family receive no proposal batch data in all ownership and identifier-tampering tests.
- **SC-005**: Database and log assertions find no submitted source text or image bytes after analysis, and assistant observations contain no captured content.
- **SC-006**: Desktop and mobile users can submit, review, edit, approve, reject, and follow result links without leaving the drawer; all critical journeys pass automated frontend and browser acceptance tests.
- **SC-007**: Inputs at each documented boundary are either accepted or rejected consistently before inference, including 20,000-character text and 10 MB image limits.
- **SC-008**: Proposal data expires automatically within the database's TTL schedule after seven days, while applied domain records remain available normally.

## Assumptions

- The existing authenticated CoParent membership model, OpenAI credentials, and AI integration are reused.
- The configured default model supports text/image input, strict function calling, parallel calls, structured outputs, and a no-reasoning mode.
- JPEG, PNG, and WebP are the complete v1 image set; GIF, HEIC, PDF, document conversion, OCR-only mode, and multiple images are out of scope.
- No per-parent quota is introduced; normal provider and infrastructure limits still apply.
- Existing Calendar and Messaging public contracts remain compatible, with assistant idempotency metadata kept internal.
- Applied domain records and redacted audits follow their existing retention rules even after their proposal batch expires.
