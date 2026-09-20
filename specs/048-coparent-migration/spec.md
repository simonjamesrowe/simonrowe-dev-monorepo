# Feature Specification: CoParent Migration

**Feature Branch**: `simonrowe/plan-coparent-migration`

**Created**: 2026-09-19

**Status**: Draft

**Input**: User description: "Move the existing CoParent application into the simonrowe.dev
monorepo. Replace its Node API with the existing Java backend, keep the product isolated through
its own packaging, migrate the frontend with the same behaviour and presentation, and serve it in
production from coparents.simonrowe.dev, following the Term Time integration pattern."

## Context

CoParent currently has its own repository, browser application, API, authentication, family data,
and test suite. Operating it as a separate stack duplicates build, deployment, monitoring, and
dependency maintenance that simonrowe.dev already provides. The migration consolidates those
operational concerns while keeping CoParent recognisably separate as a product and preserving the
working user journeys that already exist.

This feature migrates the implemented CoParent foundation. It does not pull future roadmap items
such as expenses, document storage, or evidence timelines forward merely because placeholder
screens already exist for them.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Set up and manage a family (Priority: P1)

A parent opens CoParent on its own domain, signs in, completes onboarding, creates a family, adds
children, and manages their profile without needing to know that the application now shares the
simonrowe.dev deployment.

**Why this priority**: Family membership is the tenancy and authorisation root for every other
CoParent capability. The migrated product is not useful or safe until this journey works end to
end.

**Independent Test**: A new authenticated user can complete onboarding, create a family, add and
edit a child, sign out, sign back in, and see the same data.

**Acceptance Scenarios**:

1. **Given** a user with no CoParent profile, **When** they sign in and complete onboarding,
   **Then** a family, primary-parent profile, child profile, and completed onboarding state are
   retained.
2. **Given** a parent belongs to one family, **When** they request another family's record by an
   identifier they obtained elsewhere, **Then** no data from the other family is disclosed or
   changed.
3. **Given** a parent has existing CoParent data before migration, **When** they first sign in after
   cutover, **Then** their family, children, role, and onboarding state are unchanged.

---

### User Story 2 - Invite the other parent (Priority: P2)

A primary parent invites a co-parent by email. The recipient follows the invitation, authenticates,
joins the intended family with the intended role, and can then participate in shared workflows.

**Why this priority**: CoParent only becomes a co-parenting product when both parents can enter the
same isolated family space safely.

**Independent Test**: Create, resend, cancel, and accept invitations against a family containing
one parent, verifying expiry, duplicate prevention, and membership after acceptance.

**Acceptance Scenarios**:

1. **Given** an active primary parent, **When** they invite a valid email address, **Then** the
   recipient receives a link to the canonical CoParent domain that expires after the existing
   validity period.
2. **Given** a valid invitation, **When** the intended recipient accepts it after authentication,
   **Then** they join only the invitation's family and the invitation cannot be accepted again.
3. **Given** a canceled, expired, duplicated, or otherwise invalid invitation, **When** it is used,
   **Then** no membership is created and the user gets a clear outcome.

---

### User Story 3 - Coordinate schedules (Priority: P3)

Parents view the shared calendar, manage family events and categories, and request or respond to
schedule changes using the same day, week, and month experiences available before migration.

**Why this priority**: Scheduling is the primary day-to-day coordination workflow and has the
widest existing browser and API test coverage after family setup.

**Independent Test**: Two parents in one family can create, update, and remove events; one can
request a change and the other can approve or decline it without any other family's data becoming
visible.

**Acceptance Scenarios**:

1. **Given** an authenticated family member, **When** they create or edit an event with valid
   dates, participants, children, category, recurrence, and notes, **Then** the calendar shows the
   same values after refresh.
2. **Given** a pending schedule-change request, **When** the other parent approves or declines it,
   **Then** the decision, responder, response time, and optional note are retained.
3. **Given** a parent who did not create a pending request, **When** they attempt to delete it,
   **Then** the request remains and the operation is refused.

---

### User Story 4 - Communicate and record decisions (Priority: P4)

Two parents exchange messages, see unread state, and create and resolve formal permission requests
for a child.

**Why this priority**: Structured, attributable communication is CoParent's other implemented core
workflow and carries a stronger audit requirement than ordinary chat.

**Independent Test**: Two family members can create a conversation, send a follow-up, mark it read
or unread, create a permission request, and approve or deny it, with every action attributed to the
correct parent.

**Acceptance Scenarios**:

1. **Given** two parents in a family, **When** one sends a message, **Then** both see the same
   timestamped conversation and only the recipient's unread count increases.
2. **Given** a pending permission request, **When** the other parent approves or denies it,
   **Then** the status, responder, response, and resolution time are retained and auditable.
3. **Given** a parent outside the family, **When** they address a conversation, message, or
   permission identifier directly, **Then** no content or existence detail is disclosed.

---

### User Story 5 - Operate CoParent as part of simonrowe.dev (Priority: P5)

The owner deploys, monitors, backs up, restores, and rolls back CoParent through the existing
simonrowe.dev operational workflows, while users continue to experience it as its own product.

**Why this priority**: Consolidation only pays off if the separate runtime can be retired without
losing product-specific recovery and verification.

**Independent Test**: Deploy the consolidated application into a production-like environment,
exercise the public domain and critical journey, take and restore a backup, and prove that disabling
CoParent does not break the portfolio or Term Time.

**Acceptance Scenarios**:

1. **Given** a normal deployment, **When** a user opens `https://coparents.simonrowe.dev`, **Then**
   client-side routes, authentication callbacks, API calls, install metadata, and static assets all
   remain on that origin and load the CoParent product.
2. **Given** a maintenance or upstream outage, **When** the CoParent hostname is requested, **Then**
   it shows the shared operational status page branded for CoParent rather than another product's
   application shell.
3. **Given** a complete platform backup, **When** it is restored into an empty production-like
   environment, **Then** CoParent families, messages, invitations, events, permissions, and audit
   records are restored with their relationships intact.

### Edge Cases

- A valid identity has no CoParent profile yet, has a profile not attached to a family, or has
  profiles in more than one family.
- An identifier is malformed rather than merely absent; it must produce a controlled client error,
  not an internal failure.
- An invitation is accepted concurrently, accepted by an address different from the invitation,
  expires during the flow, or email delivery fails after the invitation is stored.
- A parent tries to demote the only primary parent, change their own role, resolve their own
  request, or act on a child/event/request from another family.
- A recurring or all-day event crosses a daylight-saving transition in the family's time zone.
- The browser is offline, has an older installed version, or retains cached data from before the
  migration.
- The old and new applications see the same production data during validation; only one may accept
  writes at a time.
- The canonical domain is opened with a deep link such as `/calendar`, `/messages`,
  `/invitations/accept`, or `/auth/callback`.

## Requirements *(mandatory)*

### Functional Requirements

**Product and access**

- **FR-001**: CoParent MUST be available from `https://coparents.simonrowe.dev` as a product whose
  navigation, visual identity, install experience, and browser routes remain separate from the
  portfolio and Term Time.
- **FR-002**: CoParent MUST retain its existing external identity provider and MUST require a valid
  authenticated identity for all family data and mutations.
- **FR-003**: The authentication callback, logout return, invitation link, and allowed browser
  origins MUST use the canonical CoParent domain in production.
- **FR-004**: Every read and mutation MUST derive the acting parent from the verified identity and
  verify membership of the addressed family; a client-supplied parent or family identifier alone
  MUST never grant access.
- **FR-005**: The migrated application MUST preserve the existing distinction between primary and
  co-parent roles, including the operations restricted to the primary parent or original requester.

**Feature parity**

- **FR-006**: Users MUST be able to create, list, view, edit, and soft-delete families according to
  the current CoParent behaviour.
- **FR-007**: Users MUST be able to create or update their own profile, list parents in an
  authorised family, and change parent roles where authorised.
- **FR-008**: Users MUST be able to add, list, view, edit, and soft-delete children in an authorised
  family.
- **FR-009**: Users MUST be able to create, list, resend, cancel, expire, and accept family
  invitations without exposing reusable invitation secrets in logs or responses.
- **FR-010**: Users MUST be able to resume, advance, and complete the existing onboarding journey.
- **FR-011**: Users MUST be able to create, list, view, edit, and soft-delete calendar events and
  event categories with the current validation and recurrence behaviour.
- **FR-012**: Users MUST be able to create, list, view, approve, decline, and withdraw schedule
  change requests with the current ownership rules.
- **FR-013**: Users MUST be able to list conversations, start message and permission conversations,
  send messages, update read state, and approve or deny permission requests.
- **FR-014**: Every existing implemented browser route and critical automated journey MUST remain
  available after migration. Existing placeholder pages MAY remain placeholders and MUST NOT be
  expanded as part of this feature.

**Data continuity and audit**

- **FR-015**: Existing entity identifiers, timestamps, soft-deletion markers, embedded message and
  permission records, statuses, and relationships MUST survive migration without semantic change.
- **FR-016**: Each family MUST remain an isolated tenant across parents, children, invitations,
  onboarding, events, categories, schedule changes, conversations, permissions, and audit records.
- **FR-017**: Existing mutation audit behaviour MUST be preserved, and migrated audit records MUST
  remain attributable to the verified identity.
- **FR-018**: Backup and restore MUST include all CoParent data and MUST recreate required indexes
  before the application accepts traffic.
- **FR-019**: If live CoParent data exists at cutover, the system MUST take a recoverable backup and
  reconcile record counts and representative relationships before writes are enabled on the new
  implementation.

**Operability and retirement**

- **FR-020**: CoParent MUST use the existing simonrowe.dev build, deployment, maintenance,
  monitoring, logging, tracing, and rollback workflows without adding a separately deployed API or
  frontend.
- **FR-021**: CoParent MUST be independently switchable so an incomplete or failed migration can be
  disabled without disabling the portfolio or Term Time.
- **FR-022**: The current Node API and separate frontend runtime MUST be retired only after feature,
  authorisation, data, browser, and production smoke checks pass against the consolidated product.
- **FR-023**: The installed/offline browser experience MUST not intercept or cache the portfolio or
  Term Time applications, and stale clients MUST update safely after a deployment.
- **FR-024**: Production monitoring MUST check the CoParent public hostname and distinguish its
  failure from a whole-stack failure.

### Key Entities

- **Family**: The top-level tenant that groups its parents, children, invitations, calendar,
  messages, permissions, and onboarding state, with a name, time zone, lifecycle timestamps, and
  soft-deletion state.
- **Parent**: An authenticated person's membership in a family, including identity reference,
  contact/profile fields, primary or co-parent role, status, colour/avatar, and last sign-in time.
- **Child**: A child attached to one family, including name, birth date, school, medical notes,
  avatar, timestamps, and soft-deletion state.
- **Invitation**: A time-limited, single-use offer to join a family with a nominated email and role.
- **Onboarding state**: One family's progress through account, family, child, invitation, review,
  and completion steps.
- **Event and event category**: A dated family calendar entry and its family-defined presentation
  category, including participants, children, recurrence, location, notes, and soft-deletion state.
- **Schedule change request**: A proposed change to a custody or calendar period, including owner,
  reason, proposed dates, decision, responder, and lifecycle timestamps.
- **Conversation**: A family-scoped message or permission thread between parents, with embedded
  timestamped messages, per-parent unread counts, and optional permission decision state.
- **Audit record**: An append-only attribution of a mutation to an identity, family, entity,
  action, timestamp, and recorded changes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All existing CoParent browser end-to-end journeys for onboarding, child management,
  calendar, messaging, and permissions pass against the consolidated application.
- **SC-002**: All migrated API contract tests pass with equivalent success payloads, validation
  failures, authentication failures, and authorisation failures.
- **SC-003**: Automated cross-family tests exercise every read and mutation area and disclose or
  modify zero records outside the acting parent's families.
- **SC-004**: A migration rehearsal accounts for 100% of source records in every implemented
  CoParent data set and verifies all sampled relationships and embedded records after migration.
- **SC-005**: A parent with pre-existing data can sign in after cutover and complete the dashboard,
  calendar, messaging, and family-management journeys without re-entering data.
- **SC-006**: The CoParent root and every supported deep link load successfully from the canonical
  production hostname, including a fresh browser, an installed browser app, and an updated stale
  client.
- **SC-007**: A backup-and-restore rehearsal restores all CoParent record sets and required indexes
  without affecting portfolio or Term Time data.
- **SC-008**: Disabling CoParent causes its own routes to return a clear unavailable response while
  the portfolio, Term Time, administration, and unrelated APIs continue to pass smoke checks.
- **SC-009**: Normal authenticated reads of a family dashboard or calendar complete within two
  seconds at the expected initial production scale.

## Assumptions

- `coparents.simonrowe.dev` is the canonical new hostname. If the singular hostname has ever been
  published, it will redirect to the plural hostname rather than remain a second canonical origin.
- The current CoParent repository is the behavioural source of truth for implemented flows and its
  automated tests form the starting parity suite.
- Existing CoParent data, if any, uses the current document shapes and identifiers. Migration will
  preserve those shapes rather than force users through a destructive re-seed.
- CoParent continues to use the same Auth0 tenant and API audience as the other simonrowe.dev
  applications, with a dedicated browser client and callback allowlist for its hostname.
- The present visual design is retained. Updating framework versions or translating styling to the
  destination repository's standards must not deliberately redesign the product.
- Expenses, documents, timeline/photos, advanced notifications, data export, and other roadmap
  capabilities that do not have a working API today remain out of scope.
- The consolidated application continues to run on the existing Raspberry Pi production stack and
  must fit within its current backend and frontend containers.
