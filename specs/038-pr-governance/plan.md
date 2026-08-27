# Implementation Plan: Pull Request Governance

**Branch**: `038-pr-governance` (implemented on the workspace branch `simonrowe/pr-review-automation`) | **Date**: 2026-08-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/038-pr-governance/spec.md`, derived from the approved
design at `docs/superpowers/specs/2026-08-27-pr-governance-design.md`.

## Summary

Make pull request review state real and enforceable. Three code deliverables and one configuration
deliverable, plus operator steps no pull request can perform.

1. **Findings become resolvable.** `ReviewMarkdownRenderer.FINDING_MARKER` gains a per-finding
   fingerprint — `sha256(file + "\0" + normalise(title))` — and `GitHubGateway.publishReview`'s
   unconditional `deletePreviousFindings` is replaced by a reconcile against existing threads
   fetched over GraphQL (REST cannot see thread resolution state). Nothing is ever deleted again.
2. **The verdict becomes a check run.** A new `Code Review` check run is created `in_progress`
   once the head SHA is known and completed `success`/`failure` — `failure` when the verdict is
   `REQUEST_CHANGES` **or** any `CRITICAL` finding exists, both checked independently. Only
   `success` and `failure` are ever used. A review that dies before the head SHA is known creates
   no check run, and an absent required check blocks the merge: silence now blocks.
3. **The default branch gets a gate.** `.github/rulesets/main.json` requires four checks, zero
   approvals, conversation resolution, linear history, no bypass actors.
4. **`scripts/classify-change.sh`** maps changed paths to `auto-merge` / `ux-review` / `manual`,
   failing closed on anything unrecognised, so the `pr-review-loop` skill can arm auto-merge for
   backend-only work and capture screenshots for UX work.

The two blocking mechanisms are deliberately independent: the check run makes `CRITICAL` findings
and reviewer outages hard-red; conversation resolution makes every `WARNING`/`SUGGESTION` block
until fixed or explicitly declined. A suggestion cannot be silently ignored, but the gate does not
depend on the model grading severity correctly.

## Technical Context

**Language/Version**: Java 21 (`software-factory`), Bash (`scripts/`), JSON (ruleset). No frontend
or backend module change.

**Primary Dependencies**: None added. Existing `java.net.http.HttpClient`, Jackson,
`java.security.MessageDigest` (JDK), Temporal SDK, Spring Boot 3.5.x. GitHub REST
(`/check-runs`, `/pulls/*/comments`) and GraphQL (`reviewThreads`, `resolveReviewThread`,
`addPullRequestReviewThreadReply`).

**Storage**: **None.** No MongoDB collection, no index, no Mongock change unit. The state that
matters — thread resolution, check-run conclusion, ruleset — lives in GitHub, which is the point of
the design.

**Testing**: JUnit 5 + AssertJ + `com.sun.net.httpserver.HttpServer` stub (the existing
`GitHubGatewayTest` pattern); package-private `static` pure functions for the two decision tables
(the `toPullRequestContext` / `toConversation` pattern); plain bash for
`scripts/test/test-classify-change.sh`, auto-discovered by `scripts/test/run-tests.sh`.

**Target Platform**: `software-factory` container (ARM64, Raspberry Pi prod); `scripts/` runs on
the maintainer's macOS machine and on the Pi.

**Project Type**: Monorepo module change + repository configuration + documentation.

**Performance Goals**: Not a factor. The reconcile adds two GraphQL round trips and the check run
two REST calls per review — negligible against a 25-minute agent budget.

**Constraints**:
- `checks: write` must be granted on the App **before** the image requesting it is deployed, or
  every token mint 422s and both code review and the feedback loop go down.
- The ruleset must be applied **after** a `Code Review` check has been observed, or every pull
  request is permanently blocked with no bypass actor to recover with.
- The check run must be created after `loadPullRequest`, not at `openStatusComment` time:
  `openStatusComment` holds only a `ReviewRequest`, whose `expectedHeadSha` is nullable on the
  manual-review path.

**Scale/Scope**: One repository, one maintainer. ~6 new Java classes, ~4 modified, 1 new script, 1
new script test, 1 new JSON file, 1 new runbook, 2 edited runbooks, 1 CLAUDE.md entry. Skill edits
live in a **separate repository** (`simonjamesrowe/agent-setup`) and are tracked as follow-up.

## Constitution Check

Checked against Constitution 2.0.0.

| Principle | Status | Note |
| --- | --- | --- |
| I. Monorepo with separate containers | ✅ PASS | No container topology change. `software-factory` gains no route; nginx unchanged. |
| II. Modern Java & React stack | ✅ PASS | Java 21, Spring Boot 3.5.x, no new dependency, no new LLM provider. **No `ProcessBuilder` in `backend/src/main/java`** — nothing in this feature touches the backend at all. Deploy orchestration untouched. |
| III. Quality gates (NON-NEGOTIABLE) | ✅ PASS — and materially strengthened | This feature *is* a quality gate. Note the explicit alignment: `SonarCloud Code Analysis` is **excluded** from required checks precisely because requiring it would leave no legitimate escape hatch for a false positive, and this principle bans manual gate overrides. Excluding it keeps the advisory gate advisory rather than creating a rule that must be broken. `bypass_actors: []` for the same reason. |
| IV. Observability & operability | ✅ PASS | The check run and its `details_url` make review outcome observable from the pull request without SSH or log tailing — a net improvement over "silence means failure". |
| V. Simplicity & incremental delivery | ✅ PASS | Five independently testable increments (spec User Stories 1–5), P1 being the viable slice. No new abstraction beyond two pure decision functions, each justified by a table test. The new `ReviewThreadGateway` duplicates `ConversationGateway`'s `HttpClient` plumbing rather than extracting a shared base — matching that class's own recorded decision to keep the two gateways independently evolvable. |
| VI. Admin CMS UX standards | n/a | No admin UI change. |
| VII. Interactive site tour | n/a | |
| VIII. Backup & restore | n/a | No new persisted data to back up. |
| IX. Shell scripting standards | ✅ PASS | `classify-change.sh` uses `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` resolution. Not a MongoDB migration, so the `.js`+wrapper rule does not apply. Tested by `scripts/test/test-classify-change.sh`. |

**Development Workflow**: conventional-commit prefixes, no Jira ticket (this org). CI must be green
before merge.

**Post-Phase-1 re-check**: ✅ PASS, unchanged. Phase 1 introduced no new dependency, no persistence
and no new container capability. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/038-pr-governance/
├── plan.md                      # This file
├── spec.md                      # /speckit.specify output
├── research.md                  # Phase 0 — R1..R13 decisions against the live code
├── data-model.md                # Phase 1 — 7 entities, no persistence
├── quickstart.md                # Phase 1 — build, test, and the load-bearing rollout order
├── contracts/
│   ├── github-api.md            # check runs, review threads, finding comments, operator commands
│   └── classify-change.md       # CLI contract + the 20-case test table
├── checklists/
│   └── requirements.md          # spec quality validation
└── tasks.md                     # /speckit.tasks output
```

### Source code (repository root)

```text
software-factory/src/main/java/com/simonrowe/factory/codereview/
├── domain/
│   ├── CheckRunConclusion.java          # NEW  SUCCESS | FAILURE, and the pure mapping
│   ├── ExistingThread.java              # NEW  nodeId, fingerprint, resolved, hasNonBotReply
│   ├── FindingFingerprint.java          # NEW  sha256(file \0 normalise(title))
│   └── ThreadAction.java                # NEW  LEAVE | POST_NEW | REPLY_AND_RESOLVE (no DELETE)
├── github/
│   ├── CheckRunGateway.java             # NEW  POST/PATCH /check-runs
│   ├── ReviewThreadGateway.java         # NEW  GraphQL query + reply + resolve; reconcile table
│   ├── GitHubCredentials.java           # EDIT add "checks": "write" to the permission block
│   ├── GitHubGateway.java               # EDIT publishReview reconciles; deletePreviousFindings GONE
│   └── ReviewMarkdownRenderer.java      # EDIT fingerprinted marker; ADVISORY text rewritten
└── workflow/
    ├── ReviewActivities.java            # EDIT + openCheckRun / completeCheckRun
    ├── ReviewActivitiesImpl.java        # EDIT delegate to CheckRunGateway
    └── CodeReviewWorkflowImpl.java      # EDIT open after loadPullRequest; complete on both paths

software-factory/src/test/java/com/simonrowe/factory/codereview/
├── domain/
│   ├── CheckRunConclusionTest.java      # NEW  all 6 verdict x critical combinations
│   └── FindingFingerprintTest.java      # NEW  re-worded / re-punctuated / re-cased titles
├── github/
│   ├── CheckRunGatewayTest.java         # NEW  HttpServer stub
│   ├── ReviewThreadGatewayTest.java     # NEW  reconcile decision table + GraphQL mapping
│   ├── GitHubGatewayTest.java           # EDIT delete-assertions become reconcile-assertions
│   └── ReviewMarkdownRendererTest.java  # EDIT ADVISORY wording; fingerprinted marker
└── workflow/
    └── CodeReviewWorkflowImplTest.java  # EDIT check-run lifecycle on success and failure paths

.github/rulesets/main.json               # NEW  committed gate (applying it is operator step 5)

scripts/
├── classify-change.sh                   # NEW
└── test/test-classify-change.sh         # NEW  auto-discovered by run-tests.sh

docs/runbooks/
├── pr-governance.md                     # NEW
└── software-factory.md                  # EDIT checks:write trap, check-run semantics, reconcile

CLAUDE.md                                # EDIT Recent Changes entry
```

**Structure Decision**: This is a `software-factory` + repository-configuration change. It touches
neither `backend/` nor `frontend/`, so the monorepo's web-application split is not engaged. New
Java types go in the existing `codereview/domain` and `codereview/github` packages rather than a
new package, because they are the same bounded context — the feedback module's `ConversationGateway`
stays untouched and unshared, per its own recorded decision.

**Not in this repository**: the `pr-review-loop` and `code-review-triage` skills live in
`simonjamesrowe/agent-setup` under `components/skills/`. They are part of the deliverable but not
of this pull request; they are rollout step 6.

## Approach by user story

| Story | Deliverable | Independently shippable? |
| --- | --- | --- |
| **US1** — findings resolve | fingerprint + reconcile + `ReviewThreadGateway` | Yes. Ship alone and re-reviews stop destroying threads, with no gate change. |
| **US2** — failure blocks | `CheckRunGateway` + workflow wiring + `checks: write` | Yes, but inert until US3 requires the check. Publishing it early is exactly what rollout step 4 verifies. |
| **US3** — the gate | `.github/rulesets/main.json` + operator apply | Depends on US1 and US2 being live. Applying it first bricks the repository. |
| **US4** — auto-merge | `classify-change.sh` + skill changes | Depends on US3. |
| **US5** — screenshots | skill changes only; no code in this repository | Independent of everything else. |

## Risks

| Risk | Mitigation |
| --- | --- |
| `checks: write` requested before granted ⇒ **every** token mint 422s, killing code review and the feedback loop | Rollout step 1 precedes any deploy; `quickstart.md` carries the verification command; recorded in `software-factory.md` beside the identical `contents: write` incident |
| Ruleset applied before a `Code Review` check exists ⇒ every PR permanently blocked, including the fix | Rollout step 5 gated on step 4's observation; `bypass_actors: []` means recovery is a UI edit, documented as the emergency bypass |
| Reviewer outage stops all merging | Accepted by design — it is the fix for "silence is the normal presentation of failure". Bypass procedure documented. |
| Re-worded finding title reads as one resolved + one new | Accepted. Reply text is "No longer reported as of `<sha>`", never "Fixed" |
| Legacy bare-marker threads on open PRs | Replied to and resolved on the first run after deploy. Correct outcome; no migration code; noted in the runbook |
| `deployer` left on the old image | Rollout step 3 names both services; same failure shape as the months-stale `software-factory` |

## Complexity Tracking

No constitutional violations. Table intentionally empty.
