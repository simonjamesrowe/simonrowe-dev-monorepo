---
description: "Task list for 038-pr-governance"
---

# Tasks: Pull Request Governance

**Input**: Design documents from `/specs/038-pr-governance/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: **Included and mandatory.** Constitution Principle III makes automated quality checks
non-negotiable, and the design document carries an explicit Testing table. Every pure decision
function is table-tested; every gateway is tested against the existing
`com.sun.net.httpserver.HttpServer` stub pattern.

**Organization**: Grouped by the five user stories in `spec.md`. US1–US3 are all P1 and together
form the viable slice; US4 and US5 are additive.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: `[US1]`..`[US5]`, mapping to `spec.md`
- Every task names its exact file path

## Path Conventions

Module paths from `plan.md`:

- `software-factory/src/main/java/com/simonrowe/factory/codereview/{domain,github,workflow}/`
- `software-factory/src/test/java/com/simonrowe/factory/codereview/{domain,github,workflow}/`
- `scripts/` and `scripts/test/`
- `.github/rulesets/`
- `docs/runbooks/`

---

## Phase 1: Setup

**Purpose**: Create the two directories that do not exist yet. No dependency installation — this
feature adds none.

- [X] T001 [P] Create `software-factory/src/test/java/com/simonrowe/factory/codereview/domain/` (the package has no test directory today)
- [X] T002 [P] Create `.github/rulesets/` directory
- [X] T003 Verify the baseline is green before touching anything: `cd software-factory && ../gradlew check` and `./scripts/test/run-tests.sh`

**Checkpoint**: Baseline green, directories exist.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The credential change and the marker change that US1 and US2 both sit on.

**⚠️ CRITICAL**: T004 is the single most dangerous change in this feature. It must not be deployed
before the GitHub App grant lands (T036). Merging it is safe; deploying it early is not.

- [X] T004 Add `.put("checks", "write")` to the `permissions` object in `mintInstallationToken` in `software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubCredentials.java`, and extend the existing block comment above it to record that the App grant must precede the deploy (`research.md` R2) — the method 422s the *whole* token request when it over-reaches, which takes down code review and the feedback loop together
- [X] T005 Update the permission assertion in `software-factory/src/test/java/com/simonrowe/factory/codereview/github/GitHubCredentialsTest.java` to expect `checks: write` in the minted payload, and assert `commentToken` still sends **no** `permissions` block (that omission is what keeps failure reporting alive through a permission drift)
- [X] T006 [P] Create `FindingFingerprint` in `software-factory/src/main/java/com/simonrowe/factory/codereview/domain/FindingFingerprint.java` — `sha256(file + "\0" + normalise(title))` as lowercase hex via `MessageDigest`; `normalise` = `Locale.ROOT` lowercase → strip all non-letter/digit/whitespace → collapse whitespace runs → trim; blank-after-normalise falls back to the raw title (`data-model.md` §1)
- [X] T007 [P] Create `FindingFingerprintTest` in `software-factory/src/test/java/com/simonrowe/factory/codereview/domain/FindingFingerprintTest.java` — same fingerprint for re-cased, re-punctuated and re-whitespaced titles; **different** fingerprint for a different file with the same title; unaffected by `line` and `severity` (the two things that change every run); the `\0` separator prevents a file/title boundary collision
- [X] T008 Change `FINDING_MARKER` in `software-factory/src/main/java/com/simonrowe/factory/codereview/github/ReviewMarkdownRenderer.java` from a bare constant to a fingerprinted form — add `findingMarker(String fingerprint)` producing `<!-- temporal-code-review-finding:<hash> -->`, keep the bare constant as `LEGACY_FINDING_MARKER` (pre-change threads on open PRs must still be recognisable), and have `renderFindingComment` emit the fingerprinted marker
- [X] T009 Add a public `FINDING_MARKER_PATTERN` regex (`<!-- temporal-code-review-finding:([0-9a-f]{64}) -->`) to `ReviewMarkdownRenderer` so the thread gateway parses identity from exactly the string the renderer wrote — one definition, not two

**Checkpoint**: Findings carry identity; the token asks for `checks: write`. Nothing yet uses either.

---

## Phase 3: User Story 1 — A finding survives a push and is resolved when fixed (Priority: P1) 🎯 MVP

**Goal**: Stop deleting review threads. Reconcile the new report against what is already on the
pull request, resolving what has gone and leaving what remains.

**Independent Test**: Open a PR with two findings, fix one, push. The fixed one is replied to and
resolved; the unfixed one keeps its original thread, posting time and replies. Zero threads
destroyed.

### Tests for User Story 1 ⚠️ — write these first, confirm they fail

- [X] T010 [P] [US1] Create `ReviewThreadGatewayTest` in `software-factory/src/test/java/com/simonrowe/factory/codereview/github/ReviewThreadGatewayTest.java` covering the **reconcile decision table** as a pure static call, no HTTP: open+present ⇒ `LEAVE`; resolved+present ⇒ `POST_NEW` (regressed); open+absent ⇒ `REPLY_AND_RESOLVE`; resolved+absent ⇒ `LEAVE`; no-thread+present ⇒ `POST_NEW`; **a thread with no reviewer marker at all ⇒ `LEAVE`** (never touch a human's or SonarCloud's conversation); a legacy bare-marker thread ⇒ `REPLY_AND_RESOLVE`; and an assertion that **no input produces a delete action** (`data-model.md` §3)
- [X] T011 [P] [US1] Add GraphQL-mapping tests to the same file: `toExistingThreads(JsonNode)` extracts `nodeId` from `id`, `resolved` from `isResolved`, `fingerprint` from the **first** comment's marker (null when absent), and `hasNonBotReply` from any later comment whose `author.__typename != "Bot"` — mirroring `ConversationGatewayTest`
- [X] T012 [P] [US1] Rewrite the delete-oriented cases in `software-factory/src/test/java/com/simonrowe/factory/codereview/github/GitHubGatewayTest.java`: `publishingDeletesTheFindingCommentsEarlierPushesLeftAndKeepsEveryoneElses`, `reviewWithNoFindingsStillDeletesTheOnesTheLastPushLeft`, `findingCommentAlreadyGoneIsNotTreatedAsFailure` and `findingCommentsAreListedOnThePullRequestAndDeletedByIdOnTheRepository` — each becomes its reconcile equivalent, and add one asserting **no `DELETE` request is ever issued** to the stub server across a full publish
- [X] T013 [P] [US1] Update `software-factory/src/test/java/com/simonrowe/factory/codereview/github/ReviewMarkdownRendererTest.java` for the fingerprinted marker: the rendered finding comment matches `FINDING_MARKER_PATTERN`, two different findings render two different markers, and the same finding renders the same marker twice

### Implementation for User Story 1

- [X] T014 [P] [US1] Create `ExistingThread` record in `software-factory/src/main/java/com/simonrowe/factory/codereview/domain/ExistingThread.java` — `nodeId`, nullable `fingerprint`, `resolved`, `hasNonBotReply` (`data-model.md` §2)
- [X] T015 [P] [US1] Create `ThreadAction` in `software-factory/src/main/java/com/simonrowe/factory/codereview/domain/ThreadAction.java` — a sealed/record set of exactly `LEAVE(nodeId)`, `POST_NEW(ReviewFinding)`, `REPLY_AND_RESOLVE(nodeId)`. **Do not add a `DELETE` case**; its absence is the guarantee
- [X] T016 [US1] Create `ReviewThreadGateway` in `software-factory/src/main/java/com/simonrowe/factory/codereview/github/ReviewThreadGateway.java` — its own compact `HttpClient` plumbing copied from `ConversationGateway` (the two gateways are deliberately not shared; see that class's javadoc), a `fetchThreads(PullRequestContext)` running the `reviewThreads(first: 100)` query from `contracts/github-api.md` §B1, and non-retryable `ApplicationFailure` on a GraphQL `errors` array
- [X] T017 [US1] Add the package-private `static reconcile(List<ExistingThread>, List<ReviewFinding>, String headSha)` to `ReviewThreadGateway`, returning `List<ThreadAction>` per the decision table — pure, no I/O, following the `toPullRequestContext` / `toConversation` pattern so T010 can call it directly
- [X] T018 [US1] Add `replyAndResolve(ExistingThread, String headSha)` to `ReviewThreadGateway` using the GraphQL `addPullRequestReviewThreadReply` mutation followed by `resolveReviewThread` (`contracts/github-api.md` §B2/§B3). Keep both on GraphQL so the pair needs no REST comment id. **Reply before resolving**, so a resolution never lands without its explanation. Reply body is exactly ``No longer reported as of `<shortSha>`.`` — never "Fixed" (`research.md` R4)
- [X] T019 [US1] In `software-factory/src/main/java/com/simonrowe/factory/codereview/github/GitHubGateway.java`, **delete the `deletePreviousFindings` method entirely** and delete `findingCommentIds` and `findingCommentPath` if nothing else uses them — no code path may delete a review comment after this change
- [X] T020 [US1] Rewrite `GitHubGateway.publishReview` to: fetch existing threads via the injected `ReviewThreadGateway`, call `reconcile`, then execute the actions — `LEAVE` does nothing, `POST_NEW` posts the inline comment through the existing `findingCommentPayload` path (keeping the `422 ⇒ unanchored fallback` behaviour untouched), `REPLY_AND_RESOLVE` calls `replyAndResolve` — and finally `publishSummary` as today. Constructor-inject `ReviewThreadGateway`
- [X] T021 [US1] Update the class-level javadoc on `GitHubGateway` and the method javadoc on `publishReview`: the current text describes deleting and reposting, which becomes false. Record why deletion was wrong (a deleted thread root takes a human's reply with it, and the "N resolved" counter stays permanently zero)

**Checkpoint**: Re-reviews reconcile instead of deleting. Shippable alone — no gate change needed,
and it is the prerequisite for turning conversation resolution on.

---

## Phase 4: User Story 2 — A failed or missing review blocks the merge (Priority: P1)

**Goal**: Publish the verdict as a `Code Review` check run that a ruleset can require, and let a
reviewer outage present as an absent check rather than as silence.

**Independent Test**: Open a PR that provokes a `CRITICAL` finding; the check is red. Fix it; the
check is green. Kill the reviewer mid-run before the head SHA is known; no check appears.

### Tests for User Story 2 ⚠️ — write these first, confirm they fail

- [X] T022 [P] [US2] Create `CheckRunConclusionTest` in `software-factory/src/test/java/com/simonrowe/factory/codereview/domain/CheckRunConclusionTest.java` — **all six** `Verdict` × has-`CRITICAL` combinations from `contracts/github-api.md` §A2, explicitly including **`APPROVE` + a `CRITICAL` finding ⇒ `FAILURE`** (the engine can emit a verdict inconsistent with its own severities), plus an assertion that the enum has exactly two constants so a future `NEUTRAL` cannot be added without failing a test
- [X] T023 [P] [US2] Create `CheckRunGatewayTest` in `software-factory/src/test/java/com/simonrowe/factory/codereview/github/CheckRunGatewayTest.java` against the `HttpServer` stub — create posts `name: "Code Review"`, `head_sha`, `status: in_progress` and returns the id; complete patches `status: completed` with `conclusion` `success` or `failure` **and never any other value**; a failed create yields a null id without throwing; completing with a null id issues no request at all
- [X] T024 [P] [US2] Extend `software-factory/src/test/java/com/simonrowe/factory/codereview/workflow/CodeReviewWorkflowTest.java` — the check run opens **after** `loadPullRequest` (not at `openStatusComment` time), completes on the success path, completes `failure` on the `publishFailure` path, and **is never created when the workflow dies inside `loadPullRequest`** (the fail-closed path that makes silence block)
- [X] T025 [P] [US2] Update `ReviewMarkdownRendererTest` lines asserting `"_Advisory only"` / `"Advisory only"` (currently lines 38 and 95) to the replacement wording — the advisory claim becomes false the moment the check run gates merges

### Implementation for User Story 2

- [X] T026 [P] [US2] Create `CheckRunConclusion` in `software-factory/src/main/java/com/simonrowe/factory/codereview/domain/CheckRunConclusion.java` — `SUCCESS`, `FAILURE`, a `toJson()` returning `success`/`failure`, and a `static from(Verdict, List<ReviewFinding>)` implementing `FAILURE ⟸ REQUEST_CHANGES || any CRITICAL`. Javadoc why `neutral` is never used (whether it satisfies a required check is version-dependent GitHub behaviour the gate must not rest on)
- [X] T027 [US2] Create `CheckRunGateway` in `software-factory/src/main/java/com/simonrowe/factory/codereview/github/CheckRunGateway.java` — `open(PullRequestContext, String workflowId)` doing `POST /repos/{owner}/{repo}/check-runs` and `complete(PullRequestContext, String checkRunId, CheckRunConclusion, String title, String summary)` doing the `PATCH`, both on `credentials.accessToken(...)`, both per `contracts/github-api.md` §A. Build `details_url` with the same Temporal-link construction and unconfigured-base fallback `ReviewMarkdownRenderer.workflowLink` uses
- [X] T028 [US2] Add `String openCheckRun(PullRequestContext, String workflowId)` and `void completeCheckRun(PullRequestContext, String checkRunId, ReviewReport report)` plus `void failCheckRun(PullRequestContext, String checkRunId, ReviewFailure failure)` to `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/ReviewActivities.java`
- [X] T029 [US2] Implement those three in `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/ReviewActivitiesImpl.java`, delegating to `CheckRunGateway` and injecting it alongside the existing `GitHubGateway`
- [X] T030 [US2] Wire the lifecycle into `software-factory/src/main/java/com/simonrowe/factory/codereview/workflow/CodeReviewWorkflowImpl.java`: open the check run **immediately after `loadPullRequest` returns** and only when `request.publish()`; complete it on the success path after `publishReview`; complete it `failure` in the `catch` block alongside `reportFailure`. Wrap the open in the same best-effort try/catch `openStatusComment` uses (returning null), since the *absence* of the check is itself the blocking signal and needs no compensation
- [X] T031 [US2] Guard the failure path: `failCheckRun` must be skipped when the check-run id is null, so a review that died inside `loadPullRequest` leaves the check **absent** rather than creating one just to fail it — absent is the state that blocks
- [X] T032 [US2] Rewrite `ReviewMarkdownRenderer.ADVISORY` — it currently reads *"Advisory only; this reviewer does not approve or block merges."*, which becomes false. Replace with wording stating that critical findings and reviewer failures block the merge via the `Code Review` check, and that other findings block until their conversation is resolved
- [X] T033 [US2] Register `CheckRunGateway` and `ReviewThreadGateway` as `@Component`s and confirm the `software-factory` context still starts — both are constructor-injected into existing beans, so a missing annotation surfaces as a context-load failure in the existing test suite rather than at runtime

**Checkpoint**: Every review publishes a green-or-red `Code Review` check, and an outage publishes
nothing. Still inert — no ruleset requires it yet, which is exactly the state rollout step 4 verifies.

---

## Phase 5: User Story 3 — The default branch is actually gated (Priority: P1)

**Goal**: Commit the gate so it is reviewable, diffable and restorable. Applying it is an operator
step, deliberately not automated.

**Independent Test**: Attempt to merge a PR with an unresolved conversation; it is refused. Resolve
it; the merge becomes available.

- [X] T034 [US3] Create `.github/rulesets/main.json` in GitHub's rulesets payload shape per `data-model.md` §6 — `target: branch`, `enforcement: active`, `conditions.ref_name.include: ["~DEFAULT_BRANCH"]`, **`bypass_actors: []`**, a `pull_request` rule with `required_approving_review_count: 0` and `required_review_thread_resolution: true`, a `required_status_checks` rule listing exactly the four contexts `Backend Build & Test`, `Frontend Build & Test`, `Software Factory Build & Test`, `Code Review`, plus `required_linear_history`, `non_fast_forward` and `deletion` rules
- [X] T035 [US3] Add a comment block at the top of `docs/runbooks/pr-governance.md` (created in T044) recording the three deliberate exclusions and their reasons — `Static Analysis` (`continue-on-error: true`, so success is meaningless), `SonarCloud Code Analysis` (would make an intentionally advisory gate blocking with no legitimate escape hatch, and Constitution III bans manual overrides), `evaluate` (`paths:`-filtered, normally absent, and an absent required check blocks forever) — since JSON cannot carry comments
- [ ] T036 [US3] **OPERATOR** — grant the GitHub App `Checks: Read and write` and accept the installation permission update. **Before any deploy.** Verify with `gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/installation --jq '.permissions'`
- [ ] T037 [US3] **OPERATOR** — after this PR merges, deploy both containers: `docker compose -f docker-compose.prod.yml up -d --no-deps software-factory deployer`. `deployer` runs the same `FACTORY_IMAGE` and never recreates itself
- [ ] T038 [US3] **OPERATOR** — on a real pull request, confirm a `Code Review` check appears and completes, and that a fixed finding is replied to and resolved rather than deleted (`quickstart.md` step 4)
- [ ] T039 [US3] **OPERATOR** — only after T038 passes, apply the ruleset: `gh api --method POST /repos/.../rulesets --input .github/rulesets/main.json` (or `PUT /rulesets/{id}` thereafter). Applying it before T038 makes the required check permanently absent and blocks **every** pull request including the one that would fix it; with no bypass actors, recovery is a hand edit in the GitHub UI
- [ ] T040 [US3] **OPERATOR** — `gh api --method PATCH /repos/simonjamesrowe/simonrowe-dev-monorepo -F allow_auto_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false`, making CLAUDE.md's squash-only claim true

**Checkpoint**: `main` is gated. US1–US3 together are the viable slice.

---

## Phase 6: User Story 4 — Low-risk changes merge themselves (Priority: P2)

**Goal**: A tested, fail-closed path classifier, and the skill changes that arm auto-merge from it.

**Independent Test**: Run the classifier over backend-only, frontend-source, infrastructure, and
unrecognised-directory path sets; confirm the four expected categories.

### Tests for User Story 4 ⚠️ — write these first, confirm they fail

- [X] T041 [P] [US4] Create `scripts/test/test-classify-change.sh` implementing all 20 cases in `contracts/classify-change.md`, feeding paths on **stdin** so no repository state is needed. Cases 15–17 (precedence), 18 (the unrecognised-path default) and 20 (empty input) are the load-bearing ones. The classifier shells out to nothing, so the test must not depend on the suite's exported `DRY_RUN=1`. `scripts/test/run-tests.sh` auto-discovers it — no registration needed

### Implementation for User Story 4

- [X] T042 [US4] Create `scripts/classify-change.sh` per `contracts/classify-change.md` — Constitution IX shape (`#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` via `$(cd "$(dirname "$0")" && pwd)`); reads paths from stdin when present, otherwise `git diff --name-only <base-ref>...HEAD` defaulting `<base-ref>` to `origin/main`; emits `category=` then `ux_affecting=`; exits `0` for every classification including `manual` (needing a human is an answer, not an error)
- [X] T043 [US4] Implement the four precedence rules in that script with rule 1 (`manual`: `docker-compose*.yml`, `scripts/**`, `config/**`, `.github/**`, `gradle*`, root build files, `frontend/*.config.*`, `frontend/package*.json`) outranking rule 3 (`auto-merge`), and rule 4 defaulting anything unmatched to `manual`. Comment both load-bearing choices in the script itself: rule 1 outranks rule 3 because auto-merge triggers Publish which triggers an unattended prod deploy, and rule 4 exists so a new top-level directory added later needs a human rather than inheriting merge rights

**Checkpoint**: `./scripts/test/run-tests.sh` passes with the new file discovered. Auto-merge is
armed by the skill (T047), not by this repository.

---

## Phase 7: User Story 5 — UX-affecting pull requests carry visual evidence (Priority: P3)

**Goal**: Screenshots rendered inline on UX pull requests.

**Independent Test**: Change one visible page, run the flow, see inline desktop and mobile captures
with the route named.

**Note**: This story has **no code in this repository**. It is entirely skill procedure, delivered
in `simonjamesrowe/agent-setup`. The tasks below are documentation and follow-up work.

- [X] T044 [P] [US5] Document the screenshot mechanics in `docs/runbooks/pr-governance.md` — orphan `pr-screenshots` branch as a **single amended commit, force-pushed**; layout `pr-<n>/<route-slug>-<viewport>[-dark].png`; pushed from a throwaway `git worktree` so the feature branch and working tree stay untouched; `raw.githubusercontent.com` embeds; one `<!-- pr-screenshots -->`-marked comment edited in place and listing the routes captured; pruning after merge with the accepted cost that merged PRs then have broken links
- [X] T045 [P] [US5] Document the capture procedure in the same runbook — Playwright MCP against the local stack over restored prod data (`local-env`, `prod-data-restore`), `browser_resize` → `browser_navigate` → `browser_take_screenshot`, per affected route at 1440×900 and 390×844, dark mode when the diff touches theming; record why a CI Playwright job was rejected (needs Mongo, Elasticsearch and Kafka, and would shoot empty-state pages unless fixtures were seeded — more work for a worse artefact)

**Checkpoint**: The procedure is written down where the skill can cite it.

---

## Phase 8: Polish & Cross-Cutting

- [X] T046 [P] Create `docs/runbooks/pr-governance.md` covering the ruleset, the three excluded checks and why, the emergency bypass (there are no bypass actors — escalation is a visible ruleset edit), the drift-check command, auto-merge policy, and the screenshot mechanics from T044/T045
- [X] T047 [P] Extend `docs/runbooks/software-factory.md` with the `checks: write` rollout trap (beside the existing identical `contents: write` entry), the check-run semantics including that an absent check is the outage signal, thread reconciliation, and the note that legacy bare-marker threads are replied to and resolved on the first run after deploy
- [X] T048 Add a `038-pr-governance` entry to the *Recent Changes* section of `CLAUDE.md` **by hand** — do **not** re-run `.specify/scripts/bash/update-agent-context.sh`, which corrupted the file during planning (a `grep: repetition-operator operand invalid` error stripped the lead line from eight existing entries). Record: the `checks: write` rollout hazard, that committing the ruleset does not apply it, that nothing is deleted any more, and that rule 4 of the classifier is the fail-closed default
- [X] T049 Run `cd software-factory && ../gradlew check` — Checkstyle (Google Java Style) plus the full test suite plus the JaCoCo floor
- [X] T050 Run `./scripts/test/run-tests.sh` and confirm `test-classify-change.sh` is discovered and passes alongside the three existing files
- [X] T051 Walk `quickstart.md` end to end and correct anything that does not match what was built
- [ ] T052 **FOLLOW-UP, separate repository** — update `simonjamesrowe/agent-setup` `components/skills/pr-review-loop` (the source, not the `~/.claude/skills` copy): read the `Code Review` check run instead of an issue comment; add thread resolution, classification via `scripts/classify-change.sh`, `gh pr merge --auto --squash` on `auto-merge` only, and screenshots on `ux-review`; state that `--auto` is the merge mechanism, not permission to stop watching; **remove the stale claim** that `Static Analysis` fails on every PR (the project is on CI-based SonarCloud analysis and PR 122 shows both checks green)
- [ ] T053 **FOLLOW-UP, separate repository** — update `simonjamesrowe/agent-setup` `components/skills/code-review-triage` to add "red `Code Review` check" as a trigger alongside silence

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)** — no dependencies
- **Phase 2 (Foundational)** — blocks US1 and US2. T004/T005 are independent of T006–T009 and can run in parallel
- **Phase 3 (US1)** — needs T006–T009 (fingerprint + marker)
- **Phase 4 (US2)** — needs T004 (the token) only; **independent of US1** and can be built in parallel
- **Phase 5 (US3)** — T034/T035 can be written any time; **T036–T040 are operator steps in strict order** and depend on US1 and US2 being deployed
- **Phase 6 (US4)** — the script and its test are independent of everything; arming auto-merge depends on US3 being applied
- **Phase 7 (US5)** — independent of all other phases
- **Phase 8 (Polish)** — after the code phases

### The load-bearing ordering constraint

```
T036 (grant checks:write)  ──must precede──▶  T037 (deploy)
T038 (observe a real check) ──must precede──▶  T039 (apply ruleset)
```

Both are irreversible-in-practice if taken out of order: an early deploy 422s every token mint
(killing code review *and* the feedback loop); an early ruleset apply blocks every pull request
including its own fix, with no bypass actor to recover with.

### Within each user story

- Tests first, confirmed failing, then implementation
- Domain records before the gateways that use them
- Gateways before the workflow wiring

---

## Parallel Opportunities

```bash
# Phase 2 — two independent groups
Task: "T004+T005 GitHubCredentials checks:write"
Task: "T006+T007 FindingFingerprint and its test"

# Phase 3 tests — four different files
Task: "T010 ReviewThreadGatewayTest reconcile table"
Task: "T011 ReviewThreadGatewayTest GraphQL mapping"
Task: "T012 GitHubGatewayTest reconcile assertions"
Task: "T013 ReviewMarkdownRendererTest fingerprinted marker"

# Phase 4 tests — four different files
Task: "T022 CheckRunConclusionTest all six combinations"
Task: "T023 CheckRunGatewayTest HttpServer stub"
Task: "T024 CodeReviewWorkflowTest check-run lifecycle"
Task: "T025 ReviewMarkdownRendererTest advisory wording"

# US1 and US2 implementation are independent of each other and can run concurrently

# Phase 8 documentation — three different files
Task: "T046 docs/runbooks/pr-governance.md"
Task: "T047 docs/runbooks/software-factory.md"
```

---

## Implementation Strategy

### MVP — US1 alone

Phases 1–3. Re-reviews stop destroying threads, the "N resolved" counter starts working, and a
declined finding's reasoning can finally live on the thread it concerns. No gate change, no
operator step, no rollout hazard. Ship and validate here.

### Then US2, still inert

Phase 4 publishes the `Code Review` check but nothing requires it — which is precisely the state
T038 needs in order to verify the check before T039 makes it load-bearing.

### Then US3 — the gate goes live

Phase 5's operator steps, in order. This is the point of no easy return.

### Then US4 and US5

Additive. US5 needs no code in this repository at all.

---

## Notes

- **Nothing is ever deleted after this change.** `ThreadAction` has no delete case, and
  `deletePreviousFindings` is removed rather than left unused.
- **Both check-run conditions are evaluated independently** — verdict *and* severities. The engine
  can emit a verdict inconsistent with its own findings; `APPROVE` + `CRITICAL` must be red.
- **Only `success` and `failure`.** `neutral`'s behaviour against a required check is
  version-dependent and the gate must not rest on it.
- **Expect fewer unattended merges than "backend-only ⇒ auto-merge" implies.** Conversation
  resolution is required, so any `SUGGESTION` blocks until fixed or declined. That is intended.
- Commit after each task or logical group; conventional-commit prefixes, no Jira ticket in this org.
- This pull request classifies as `manual` under its own classifier — it touches `.github/**` and
  `scripts/**`.
