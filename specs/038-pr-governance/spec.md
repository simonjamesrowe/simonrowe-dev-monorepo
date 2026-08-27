# Feature Specification: Pull Request Governance

**Feature Branch**: `038-pr-governance`

**Created**: 2026-08-27

**Status**: Draft

**Input**: User description: "Implement the approved PR governance design at `docs/superpowers/specs/2026-08-27-pr-governance-design.md` — resolvable review findings (fingerprinted threads reconciled instead of deleted), a `Code Review` check run that makes CRITICAL findings and reviewer outages hard-red, a committed ruleset for main with four required checks and required conversation resolution, `scripts/classify-change.sh` for auto-merge/ux-review/manual categorisation, auto-merge for backend-only PRs, and screenshots on an orphan `pr-screenshots` branch for UX-affecting PRs."

## Context

Three gaps were found by inspecting the live repository:

1. **Findings are deleted, not resolved.** Every re-review deletes all previously posted
   findings and reposts the survivors. A standing finding reads as brand new on every push,
   the "N resolved" counter is permanently zero, and a thread is destroyed even when a human
   has replied to it. Measured: pull requests 106, 110, 112, 114, 116 and 122 have zero
   review threads; 107 retains one, which is a *declined* finding whose reasoning is stranded
   in a separate comment the interface cannot connect to the thread.
2. **The default branch has no gate.** No branch protection or ruleset exists, auto-merge is
   disabled, and merge-commit and rebase merges are both still permitted despite project
   documentation stating the branch is squash-merged.
3. **The reviewer's verdict is invisible to any gate.** It is published as an ordinary
   comment, so no merge path can consider it — and a failed review commonly publishes nothing
   at all, so silence is the normal presentation of failure. The signal that most needs to
   block a merge is the one that cannot.

The design (approved, dated 2026-08-27) closes all three with two independent blocking
mechanisms doing different jobs: a check run that goes hard-red on `CRITICAL` findings and on
reviewer outages, and required conversation resolution that makes every lower-severity finding
block until it is fixed or explicitly declined. The split is deliberate — a suggestion cannot
be silently ignored, but the gate does not depend on the reviewer grading severity correctly.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A finding survives a push and is resolved when fixed (Priority: P1)

The maintainer pushes a fix for a reported finding. On the next review the finding is gone
from the report, so the reviewer replies to the existing conversation thread and marks it
resolved. Findings that are still present are left exactly as they are — same thread, same
position in the conversation, same reply history. A thread that a human has replied to is
never destroyed.

**Why this priority**: This is the change that makes review state trustworthy. Without it the
conversation-resolution gate (Story 3) cannot be turned on, because every push would reopen
every finding and nothing would ever merge.

**Independent Test**: Open a pull request with two findings, fix one, push, and confirm the
fixed one is resolved with a reply while the unfixed one is untouched and still shows its
original posting time and any replies.

**Acceptance Scenarios**:

1. **Given** a finding reported on the previous run and still present in the new report,
   **When** the review republishes, **Then** the existing thread is left untouched — not
   deleted, not reposted, not duplicated.
2. **Given** a finding reported on the previous run and absent from the new report, **When**
   the review republishes, **Then** the reviewer replies to that thread stating it is no
   longer reported as of the current commit, and marks the thread resolved.
3. **Given** a finding in the new report with no matching thread, **When** the review
   republishes, **Then** a new thread is posted for it.
4. **Given** a thread that was previously resolved and whose finding reappears in the new
   report, **When** the review republishes, **Then** a fresh thread is posted, because the
   issue regressed.
5. **Given** a thread carrying a reply written by a human, **When** the review republishes,
   **Then** that thread is never deleted.
6. **Given** the same underlying finding reported with a re-punctuated or re-cased title,
   **When** the review republishes, **Then** it is recognised as the same finding and not
   duplicated.

---

### User Story 2 - A failed or missing review blocks the merge (Priority: P1)

The reviewer publishes its verdict as a first-class status on the commit, not as a comment.
The status is green when the review approves or comments with no critical finding, and red
when it requests changes, when any critical finding exists, or when the review itself fails.
If the reviewer never runs at all, the status is simply absent — and an absent required status
blocks the merge. Silence now blocks instead of passing.

**Why this priority**: Equal-highest with Story 1. It converts the reviewer from advisory to
blocking and is the prerequisite for any safe automatic merge.

**Independent Test**: Open a pull request that provokes a critical finding and confirm the
merge is blocked by a red status; fix it and confirm the status turns green.

**Acceptance Scenarios**:

1. **Given** a review that approves and reports no critical finding, **When** it completes,
   **Then** the status is green.
2. **Given** a review that comments and reports no critical finding, **When** it completes,
   **Then** the status is green.
3. **Given** a review that approves but reports at least one critical finding, **When** it
   completes, **Then** the status is red — the finding severity is checked independently of
   the stated verdict.
4. **Given** a review that requests changes, **When** it completes, **Then** the status is red.
5. **Given** a review that fails part-way after the commit under review is known, **When** it
   ends, **Then** the status is red and its summary links to the run that failed.
6. **Given** a review that dies before the commit under review is known, **When** it ends,
   **Then** no status is published, and the merge is blocked because a required status is
   absent.
7. **Given** any review outcome, **When** the status is published, **Then** it is only ever
   green or red — never an indeterminate third state.

---

### User Story 3 - The default branch is actually gated (Priority: P1)

Merging into the default branch requires a pull request, all four build-and-review statuses
green, every conversation resolved, and linear history. Force pushes and branch deletion are
blocked. There are no bypass actors: escalating past the gate means editing the gate, which is
visible and reviewable. The gate configuration lives in the repository as a file, so it can be
reviewed, diffed and restored.

**Why this priority**: Stories 1 and 2 produce signals; without this story nothing consumes
them. It is also the prerequisite for Story 4.

**Independent Test**: Attempt to merge a pull request with an unresolved conversation and
confirm it is refused; resolve it and confirm the merge becomes available.

**Acceptance Scenarios**:

1. **Given** a pull request with all statuses green but one unresolved conversation, **When**
   a merge is attempted, **Then** it is refused.
2. **Given** a pull request with every conversation resolved but a red review status, **When**
   a merge is attempted, **Then** it is refused.
3. **Given** the reviewer never published its status, **When** a merge is attempted, **Then**
   it is refused because a required status is missing.
4. **Given** the maintainer is the sole reviewer of their own work, **When** the gate is
   applied, **Then** it requires zero approvals — requiring one would deadlock permanently,
   since self-approval is forbidden.
5. **Given** a merge is performed, **When** it lands, **Then** it is a squash merge; merge
   commits and rebase merges are unavailable.
6. **Given** the gate configuration changes, **When** it is applied, **Then** the change is
   visible as a committed file diff rather than as undocumented interface state.

---

### User Story 4 - Low-risk changes merge themselves (Priority: P2)

A change confined to paths that cannot alter shipped pixels or production infrastructure is
categorised as eligible for automatic merge, and the merge is armed as soon as the pull
request is opened. It fires only once every gate in Story 3 is satisfied. Changes touching
user-visible frontend source are categorised as needing visual review; changes touching
infrastructure, build or workflow paths, and anything on an unrecognised path, are categorised
as needing a human. The categoriser fails closed: an unrecognised path is never eligible for
automatic merge.

**Why this priority**: Real convenience, but only safe once Stories 1–3 hold. It depends on
all of them.

**Independent Test**: Run the categoriser over several path sets — backend-only, frontend
source, an infrastructure file, and a path in a directory that does not exist yet — and
confirm the four expected categories.

**Acceptance Scenarios**:

1. **Given** a change touching only backend, factory, documentation, specification or
   frontend-test paths, **When** it is categorised, **Then** it is eligible for automatic
   merge and marked as not visually affecting.
2. **Given** a change touching frontend application source, markup or public assets, **When**
   it is categorised, **Then** it needs visual review and is marked as visually affecting.
3. **Given** a change touching an infrastructure, script, configuration, workflow or build
   file, **When** it is categorised, **Then** it needs a human — even when it also touches
   backend-only paths, because that category outranks eligibility for automatic merge.
4. **Given** a change touching a path matching none of the known categories, **When** it is
   categorised, **Then** it needs a human.
5. **Given** a pull request armed for automatic merge, **When** a review finding or a red
   status appears, **Then** the merge does not fire and the agent shepherding the pull
   request is still expected to be present and act.
6. **Given** a pull request categorised as needing visual review or a human, **When** it is
   opened, **Then** automatic merge is not armed and the reason is stated in the pull request
   body.

---

### User Story 5 - UX-affecting pull requests carry visual evidence (Priority: P3)

When a change alters what a visitor sees, the agent captures screenshots of the affected
routes at desktop and mobile sizes — plus dark mode when the change touches theming — against
a locally running stack holding realistic content, and posts them so they render inline in the
pull request. Re-running updates the same comment rather than adding another. The comment
lists which routes were captured, so an omission is visible rather than inferred.

**Why this priority**: Valuable but not blocking. These pull requests are merged by a human
anyway, and the human cannot miss a missing screenshot comment.

**Independent Test**: Make a visible change to one page, run the flow, and confirm the pull
request shows an inline before-and-after at both sizes with the route named.

**Acceptance Scenarios**:

1. **Given** a change categorised as visually affecting, **When** the flow runs, **Then**
   screenshots of each affected route at desktop and mobile sizes appear inline in the pull
   request.
2. **Given** the change touches theming, **When** the flow runs, **Then** dark-mode captures
   are included.
3. **Given** the flow is run a second time on the same pull request, **When** it publishes,
   **Then** the existing screenshot comment is updated in place rather than duplicated.
4. **Given** screenshots are published, **When** the images are stored, **Then** the storage
   accumulates no history — it is maintained as a single rewritten snapshot.
5. **Given** the agent publishes screenshots, **When** it does so, **Then** the working tree
   and the feature branch are left untouched.
6. **Given** a pull request is merged, **When** its screenshots are pruned, **Then** live pull
   requests are unaffected; only merged ones lose their images.

---

### Edge Cases

- **The reviewer's own credentials break.** Requesting a permission the installation has not
  been granted causes the whole credential request to be refused, which disables the reviewer
  *and* the feedback loop. The permission must therefore be granted before the software that
  requests it is deployed. This is an operator-ordering hazard, not a code path.
- **The gate is applied before anything publishes the review status.** The required status is
  then permanently absent, blocking every pull request including the one that would fix it.
  With no bypass actors, recovery means hand-editing the gate.
- **A reviewer outage blocks all merging.** Accepted: this is the intended fail-closed cost of
  making silence blocking.
- **The reviewer re-words a finding's title.** It reads as one resolved and one new. Accepted,
  and the reason the resolution reply says "no longer reported as of `<commit>`" rather than
  "fixed" — truthful under both a genuine fix and a re-wording.
- **A finding moves to a different line after a rebase.** It must still be recognised as the
  same finding; position must not be part of its identity.
- **The reviewer re-grades a finding's severity between runs.** It must still be recognised as
  the same finding.
- **A conversation opened by a human or by an unrelated analysis tool.** Conversation
  resolution applies to *all* conversations, not only the reviewer's. This is intended.
- **Every suggestion, however minor, blocks automatic merge** until fixed or declined with a
  stated reason. Unattended merges will therefore be rarer than "backend-only means
  auto-merge" suggests. Intended: the goal is that findings get dealt with, not bypassed.
- **A new top-level directory is added later.** It is categorised as needing a human by
  default rather than silently inheriting merge rights.

## Requirements *(mandatory)*

### Functional Requirements

**Finding identity and reconciliation**

- **FR-001**: Each published finding MUST carry a stable identity derived from the file it
  concerns and its normalised title, where normalisation is case-insensitive,
  whitespace-collapsing and punctuation-stripping.
- **FR-002**: Finding identity MUST NOT incorporate line number (lines move on rebase) or
  severity (the reviewer re-grades between runs).
- **FR-003**: Republishing a review MUST reconcile the new report against the conversations
  already on the pull request rather than deleting them, applying exactly these outcomes:
  present-and-open leaves the conversation untouched; present-and-resolved posts a fresh
  conversation; absent replies and resolves; present-with-no-conversation posts a new one.
- **FR-004**: The system MUST NOT delete a review conversation under any circumstance.
- **FR-005**: The reply posted when a finding is no longer reported MUST state that it is no
  longer reported as of the specific commit, and MUST NOT assert that it was fixed.
- **FR-006**: Reconciliation MUST read conversation *resolution* state, which requires a data
  source that exposes it.

**Review status**

- **FR-007**: The system MUST publish the review outcome as a status on the commit under
  review, named `Code Review`, distinct from any comment it also posts.
- **FR-008**: The status MUST be created in an in-progress state once the commit under review
  is known, which is after the pull request is loaded — not at the point the initial status
  comment is opened, because at that point the commit may be unknown.
- **FR-009**: The status MUST complete green when the verdict approves or comments **and** no
  critical finding exists.
- **FR-010**: The status MUST complete red when the verdict requests changes **or** any
  critical finding exists — both conditions evaluated independently.
- **FR-011**: The status MUST complete red when the review fails, with the failed run linked
  from its summary.
- **FR-012**: The status MUST only ever use green or red; no third, indeterminate conclusion
  may be used, because whether a required gate accepts one is not a guaranteed behaviour.
- **FR-013**: When the review ends before the commit under review is known, no status is
  published, and the absence itself blocks the merge.
- **FR-014**: Published review text MUST no longer describe the reviewer as advisory and
  non-blocking, because that statement becomes false.

**Gate**

- **FR-015**: The default-branch gate MUST be stored in the repository as a committed file so
  it is reviewable, diffable and restorable.
- **FR-016**: The gate MUST require exactly four statuses: the three build-and-test statuses
  plus the review status.
- **FR-017**: The gate MUST NOT require the static-analysis status (it reports success even
  when its scanner is broken), the external code-analysis status (requiring it makes an
  intentionally advisory quality gate blocking, with no legitimate escape hatch), or the
  path-filtered evaluation status (normally absent, and an absent required status blocks
  forever).
- **FR-018**: The gate MUST require a pull request with **zero** required approvals.
- **FR-019**: The gate MUST require every conversation to be resolved.
- **FR-020**: The gate MUST require linear history, block force pushes, and restrict branch
  deletion.
- **FR-021**: The gate MUST define no bypass actors.
- **FR-022**: Repository settings MUST permit automatic merge and permit only squash merging.

**Change categorisation**

- **FR-023**: A categoriser MUST map a set of changed paths to exactly one of three
  categories — eligible for automatic merge, needs visual review, needs a human — and to a
  separate visually-affecting flag.
- **FR-024**: Category precedence, highest first, MUST be: needs-a-human infrastructure paths;
  visual-review frontend source paths; auto-merge-eligible paths; then needs-a-human as the
  default for everything unrecognised.
- **FR-025**: An unrecognised path MUST categorise as needing a human, never as eligible for
  automatic merge.
- **FR-026**: Infrastructure paths MUST outrank auto-merge-eligible paths, because an
  automatic merge triggers publication, which triggers an unattended production deploy.
- **FR-027**: Frontend test and end-to-end paths MUST be auto-merge-eligible (they change no
  shipped pixel), while frontend build configuration MUST need a human (it changes the
  shipped bundle).
- **FR-028**: The categoriser MUST emit its result in a machine-consumable key/value form so
  it remains usable if automation ever consumes it directly.
- **FR-029**: The categoriser MUST be a self-contained, independently runnable and testable
  artefact rather than prose in a document.

**Automatic merge**

- **FR-030**: On opening a pull request the agent MUST run the categoriser and, only for the
  auto-merge-eligible category, arm an automatic squash merge and record that it did so in
  the pull request body.
- **FR-031**: For the other two categories the agent MUST NOT arm automatic merge and MUST
  state why in the pull request body.
- **FR-032**: Arming automatic merge MUST NOT end the agent's watch: it MUST continue to wait
  on every signal and report the outcome regardless.

**Screenshots**

- **FR-033**: For visually-affecting changes the agent MUST capture each affected route at a
  desktop and a mobile viewport, adding dark mode when the change touches theming.
- **FR-034**: Captures MUST be taken against a locally running stack, preferably holding
  restored production content, so they show real content rather than empty states.
- **FR-035**: Images MUST be hosted so they render inline in the pull request, in storage that
  accumulates no history — a single rewritten snapshot.
- **FR-036**: Publishing images MUST NOT modify the feature branch or the working tree.
- **FR-037**: Screenshots MUST be published as one marker-identified comment, edited in place
  on re-runs, listing the routes captured so an omission is visible.
- **FR-038**: Screenshot storage for a pull request MUST be prunable after merge, accepting
  that merged pull requests then have broken image links while live ones always work.

**Rollout**

- **FR-039**: The permission needed to publish the review status MUST be granted and accepted
  before the software that requests it is deployed.
- **FR-040**: The gate MUST be applied only after a real pull request has been observed to
  produce a green review status and to resolve conversations.
- **FR-041**: The deploy step MUST update both containers that run the affected image, since
  one of them never recreates itself.

**Documentation**

- **FR-042**: A runbook MUST document the gate, why three statuses are excluded, the emergency
  bypass procedure, automatic-merge policy and screenshot mechanics.
- **FR-043**: Existing operational documentation MUST record the permission-rollout hazard,
  the status semantics and conversation reconciliation.
- **FR-044**: The pull-request and review-triage guidance MUST be updated to read the review
  status instead of a comment, to cover conversation resolution, categorisation, automatic
  merge and screenshots, to add a red review status as a triage trigger, and to remove the
  stale claim that static analysis fails on every pull request.

### Key Entities

- **Finding**: A single reported issue. Carries a file, a title, a severity and a body. Gains
  a derived, stable identity used to match it across runs.
- **Review conversation**: A thread on a pull request, identified by the embedded identity of
  the finding that opened it, with a resolved/unresolved state and possible replies.
- **Review status**: A commit-attached, green-or-red statement of the review outcome, and the
  only review signal a merge gate can read.
- **Gate**: The committed description of what must be true before the default branch accepts a
  change — required statuses, conversation resolution, history shape, and the explicit absence
  of bypass actors.
- **Change category**: One of three merge dispositions derived from the changed paths, plus a
  visually-affecting flag, defaulting to the most restrictive disposition.
- **Screenshot set**: Images for one pull request, named by route and viewport, stored as a
  history-free snapshot and referenced from a single in-place-edited comment.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a pull request with a standing finding, pushing an unrelated commit leaves
  that finding's conversation at its original posting time with its replies intact — zero
  conversations destroyed across a re-review.
- **SC-002**: Fixing a reported finding and pushing results in its conversation being marked
  resolved automatically, with no human action, on the next review.
- **SC-003**: The "resolved conversations" count on a pull request becomes non-zero for the
  first time, and thereafter reflects real fix history rather than staying at zero.
- **SC-004**: A pull request containing a critical finding cannot be merged, and the reason is
  visible as a red required status rather than having to be inferred from comment text.
- **SC-005**: A pull request whose review never ran cannot be merged — 100% of reviewer
  outages block, where previously 100% of them passed silently.
- **SC-006**: A pull request with an unresolved suggestion cannot be merged until the
  suggestion is fixed or declined with a stated reason recorded on the conversation itself,
  rather than stranded elsewhere.
- **SC-007**: Every merge into the default branch is a squash merge; merge and rebase merges
  are unavailable.
- **SC-008**: The categoriser returns the correct category for every path set in its test
  suite, including at least one path in a directory that does not exist in the repository,
  which returns the most restrictive category.
- **SC-009**: A backend-only pull request that reaches all-green with no open conversations
  merges with no further agent action at the merge step.
- **SC-010**: A pull request that changes a visible page carries inline images of every
  affected route at two viewport sizes, viewable without leaving the pull request page and
  without downloading anything.
- **SC-011**: Repeating the screenshot flow on the same pull request leaves exactly one
  screenshot comment.
- **SC-012**: The gate's full configuration can be reconstructed from the repository alone,
  with a documented command to detect drift between the committed file and what is applied.

## Assumptions

- The reviewer application's identity can be granted permission to publish commit statuses;
  the permission grant and its acceptance are operator actions no pull request can perform.
- The permission needed to resolve conversations is already held, so resolution needs no new
  grant.
- The maintainer works solo, so the gate must never require an approval — self-approval is
  forbidden and would deadlock the repository permanently.
- Reviewer availability is acceptable to make merge-critical. An outage stopping all merges is
  a deliberate trade for making silent failure impossible.
- The repository hosting the pull requests can also host the screenshot images, and images
  served from it render inline in pull request comments.
- Screenshot capture happens on the maintainer's machine against a local stack, not in
  continuous integration: a CI capture would need the full backend, database, search and
  messaging stack, and would produce empty-state pages unless content were also seeded — more
  work for a worse artefact.
- Broken images in already-merged pull requests are an acceptable cost of unbounded-growth
  prevention; live pull requests always work.
- Finding titles are stable enough between runs for identity matching to be useful in the
  common case; a re-worded title degrading to "one resolved, one new" is accepted, and
  reviewer-supplied identifiers were rejected because they cannot be stable across independent
  runs.
- The whole change — code, categoriser, gate file and documentation — lands as a single pull
  request, which the categoriser itself classifies as needing a human, since it touches
  workflow and script paths.
- The gate configuration file being committed does not apply it; applying it is a separate,
  deliberate operator step performed only after the review status has been observed working.
