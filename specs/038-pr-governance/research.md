# Phase 0 Research: Pull Request Governance

**Feature**: 038-pr-governance
**Date**: 2026-08-27
**Source**: `docs/superpowers/specs/2026-08-27-pr-governance-design.md` (design approved)

The design document already resolved every open decision, including rejected alternatives. This
file records those decisions against the code as it actually exists today, plus the four points
that needed verifying in the repository before planning.

---

## R1. Where the review verdict must be published

**Decision**: A GitHub **check run** named `Code Review`, created via
`POST /repos/{owner}/{repo}/check-runs` and completed via
`PATCH /repos/{owner}/{repo}/check-runs/{id}`.

**Rationale**: The verdict is currently rendered into an issue comment by
`ReviewMarkdownRenderer.renderSummary` and written by `GitHubGateway.writeStatusComment`. Nothing
in GitHub's merge machinery can read an issue comment, so the reviewer cannot gate anything. A
check run is a first-class commit status that a ruleset can require by name.

**Alternatives considered**:
- *Commit Status API* (`POST /repos/.../statuses/{sha}`) — needs `statuses: write`, same new-grant
  cost, but has no rich summary/output and no run-level UI. Rejected for no benefit.
- *Submitted pull request review with `REQUEST_CHANGES`* — blocks only if approvals are required,
  and the gate deliberately requires **zero** approvals (FR-018). Also, `publishReview`'s own
  javadoc records why submitted reviews were abandoned: they can be neither deleted nor hidden,
  so one per push accumulates. Rejected.

**Verified in repo**: `GitHubGateway` has no check-run method today; `CodeReviewProperties.Github`
has no relevant field; nothing in `software-factory` calls `/check-runs`.

---

## R2. Which token mints the check run, and the rollout hazard

**Decision**: Add `"checks": "write"` to the permission block in
`GitHubCredentials.mintInstallationToken`, and use `accessToken(installationId)` (not
`commentToken`) for check-run calls.

**Rationale**: `mintInstallationToken` sends an explicit `permissions` object
(`contents`/`issues`/`pull_requests`, all `write`). GitHub **422s the entire token request** when
the requested set exceeds the installation's grant, and the method converts any 4xx into a
non-retryable `ApplicationFailure` with reason `GITHUB_TOKEN_REJECTED`. So adding `checks: write`
to the payload *before* the App is granted it breaks **every** `accessToken` call — taking down
code review and the feedback loop together. This is the same failure that took every review down
on 2026-08-11 and is already documented for the `contents: write` rollout in
`docs/runbooks/software-factory.md`.

`commentToken` deliberately mints with **no** `permissions` block (full installation grant, cannot
over-reach), which is why failure reporting survives a drift. Check runs go on `accessToken` so
they share the review path's credential; the check run is not the last-resort failure channel —
the status comment is.

**Consequence for the plan**: The App permission grant is **step 1** of rollout and is an operator
action. No code change can perform it, and no test can cover it.

**Alternatives considered**: minting a third, check-only token with its own no-permissions block.
Rejected — it does not avoid the grant (the installation still needs `checks: write`), and adds a
third cache map for no gain.

---

## R3. How to read thread resolution state

**Decision**: GraphQL. Fetch `reviewThreads(first: 100) { nodes { id isResolved comments(first: 50)
{ nodes { body author { login __typename } } } } }` and resolve with the `resolveReviewThread`
mutation.

**Rationale**: REST's `/pulls/{n}/comments` — what `deletePreviousFindings` walks today — returns
comment objects with no thread identity and **no resolution state**. There is no REST endpoint that
resolves a thread. `ConversationGateway` (feedback module) already queries exactly this shape over
GraphQL for the same reason, so the pattern, the `HttpClient` plumbing and the bot-author detection
(`__typename == "Bot"`) all exist to copy.

`resolveReviewThread` requires only `pull_requests: write`, already in the minted set — so thread
resolution needs **no** new permission. Only the check run does.

**Alternatives considered**:
- *REST + heuristics* (treat "has a reply from a non-bot" as resolved). Rejected: it cannot mark
  anything resolved, which is the whole point.
- *Reusing `ConversationGateway` directly*. Rejected: it lives in `feedback`, takes a
  `FeedbackRequest`, and its javadoc explicitly states the two gateways are kept independently
  evolvable. A new `ReviewThreadGateway` in `codereview/github` follows the same rule.

---

## R4. Finding identity

**Decision**: `sha256(file + "\0" + normalise(title))`, hex, embedded as
`<!-- temporal-code-review-finding:<hash> -->`, replacing the bare
`ReviewMarkdownRenderer.FINDING_MARKER`. `normalise` lowercases (`Locale.ROOT`), strips everything
that is not a letter, digit or whitespace, then collapses runs of whitespace to one space and
trims.

**Rationale**: The marker today carries no per-finding identity, which is precisely why
delete-everything was the only strategy available. Identity must survive the two things that
change every run:
- **Line numbers move on rebase** → line is excluded.
- **The model re-grades severity between runs** → severity is excluded.

The `\0` separator prevents `file`/`title` boundary collisions.

**Accepted limitation**: A re-worded title reads as "one resolved, one new". This is why the
resolution reply says *"No longer reported as of `<sha>`"* rather than "Fixed" — truthful under
both a genuine fix and a re-wording.

**Alternatives considered**:
- *Engine-supplied stable IDs*. Rejected in the design: an LLM cannot emit IDs that are stable
  across independent runs.
- *Including line*. Rejected: every rebase would orphan every finding.
- *Hashing the explanation too*. Rejected: prose varies far more than titles, making identity
  strictly less stable.

**Backward compatibility**: existing threads carry the old bare marker with no hash. They will not
match any new fingerprint, so on the first run after deploy they fall into the "thread exists,
fingerprint absent" branch → replied to and resolved. That is the correct outcome (they are
pre-change artefacts), and no thread is destroyed. Worth noting in the runbook, not worth code.

---

## R5. Reconcile decision table (pure function)

**Decision**: A package-private `static` method taking `(List<ExistingThread>, List<ReviewFinding>,
String headSha)` and returning a list of actions, table-tested without HTTP.

| Existing thread | Fingerprint in new report | Action |
| --- | --- | --- |
| open | yes | `LEAVE` |
| resolved | yes | `POST_NEW` (it regressed) |
| open | no | `REPLY_AND_RESOLVE` |
| — (none) | yes | `POST_NEW` |
| any | — | never `DELETE`; the action does not exist |

**Rationale**: This follows the existing package-private-static pattern
(`GitHubGateway.toPullRequestContext`, `findCommentIds`, `ConversationGateway.toConversation`),
which is what makes the current suite fast and HTTP-free. The "thread has a non-bot reply" row in
the design's table needs no code: nothing is deleted, so a human reply is safe by construction.

---

## R6. Check-run conclusion mapping

**Decision**:

```
failure  ⟸  verdict == REQUEST_CHANGES  ||  any finding.severity == CRITICAL
success  ⟸  otherwise (verdict APPROVE or COMMENT, no CRITICAL)
```

Evaluated as a pure static function over `(Verdict, List<ReviewFinding>)` so all 3 × 2 = 6
verdict/critical combinations are table-testable, including the load-bearing
**`APPROVE` + `CRITICAL` ⇒ `failure`**.

**Rationale**: Both conditions are checked because the engine can emit a verdict inconsistent with
its own severities — the model grades the summary and the findings in the same pass but not
necessarily consistently.

**Only `success` and `failure` are ever used.** Whether `neutral` satisfies a ruleset's required
check is version-dependent GitHub behaviour, and the gate must not rest on it.

**Fail-closed**: the check run is created only after `loadPullRequest` returns, because
`openStatusComment` holds only a `ReviewRequest` and `ReviewRequest.expectedHeadSha` is nullable on
the manual path (`ManualReviewRequest`). A review dying before that point creates no check run —
and an absent required check blocks the merge. This is the fix for "silence is the normal
presentation of failure".

**Accepted cost**: an outage of the `software-factory` container stops all merging until it is
fixed or the ruleset is hand-edited. Recorded in the runbook as the emergency-bypass procedure.

---

## R7. Which checks the ruleset requires

**Decision**: exactly four, by their `name:` in `.github/workflows/ci.yml` — verified against the
file:

| Required | Job | Source |
| --- | --- | --- |
| `Backend Build & Test` | `backend` | `ci.yml:32` |
| `Frontend Build & Test` | `frontend` | `ci.yml:74` |
| `Software Factory Build & Test` | `software-factory` | `ci.yml:125` |
| `Code Review` | — | new check run (R1) |

**Excluded, verified**:

| Check | Why excluded | Verified |
| --- | --- | --- |
| `Static Analysis` | `continue-on-error: true`, so the job reports success even when the scanner is broken. Required, it would be decorative. | `ci.yml:180` job `sonar` |
| `SonarCloud Code Analysis` | Requiring it makes the quality gate blocking, contradicting the deliberate unset `sonar.qualitygate.wait`, and leaves no legitimate escape hatch for a false positive — Constitution Principle III bans manual gate overrides. | `docs/runbooks/static-analysis.md` |
| `evaluate` | `paths:`-filtered, so its normal state on an unrelated PR is *absent*, and an absent required check blocks forever. | `.github/workflows/evals.yml` |

---

## R8. Ruleset representation and application

**Decision**: Commit `.github/rulesets/main.json` in GitHub's rulesets payload shape; apply it with
`gh api --method PUT /repos/{owner}/{repo}/rulesets/{id} --input .github/rulesets/main.json`.

**Rationale**: GitHub's UI state is otherwise undocumented, unreviewable and unrestorable. A
committed file makes the gate diffable and gives the runbook a drift-check command.

**Critical**: committing the file does **not** apply it. Application is an operator step, and is
step 5 of the rollout — after step 4 has confirmed a real `Code Review` check appears. Applying it
first makes the required check permanently absent, blocking **every** pull request including the
one that would fix it. With no bypass actors, recovery means hand-editing the ruleset in the UI.

**Repository settings** (separate `gh api PATCH /repos/{owner}/{repo}` call, also operator):
`allow_auto_merge: true`, `allow_merge_commit: false`, `allow_rebase_merge: false`.

**Zero required approvals** — `required_approving_review_count: 0`. GitHub forbids approving your
own pull request, so requiring one permanently deadlocks a solo maintainer.

**No bypass actors** — `bypass_actors: []`. A standing admin bypass would quietly make the whole
gate optional; escalation is editing the ruleset, which is visible.

**Alternatives considered**: classic branch protection. Rejected — rulesets are the current API,
support `required_linear_history` and conversation resolution in one payload, and are exportable
as JSON.

---

## R9. `scripts/classify-change.sh`

**Decision**: A standalone bash script following Constitution Principle IX (`#!/usr/bin/env bash`,
`set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` resolution), emitting `GITHUB_OUTPUT`-shaped
key/value lines, tested by `scripts/test/test-classify-change.sh` which
`scripts/test/run-tests.sh` auto-discovers (it globs `test-*.sh`).

There is **no `classify` job and no `ci.yml` change**. The second consumer (a CI screenshot job)
was eliminated when screenshots moved to the local agent. The output shape is kept
`GITHUB_OUTPUT`-compatible so it stays usable if CI ever needs it.

**Precedence, highest first** (rule 1 outranks rule 3):

1. `manual` — `docker-compose*.yml`, `scripts/**`, `config/**`, `.github/**`, `gradle*`, root build
   files, `frontend/*.config.*`, `frontend/package*.json`
2. `ux-review` — `frontend/src/**`, `frontend/index.html`, `frontend/public/**`
3. `auto-merge` — `backend/**`, `software-factory/**`, `docs/**`, `specs/**`, `frontend/tests/**`,
   `frontend/e2e/**`, root `*.md`
4. `manual` — anything else

**Rule 4 is load-bearing**: an unrecognised path is `manual`, never `auto-merge`, so a new
top-level directory added later defaults to needing a human rather than inheriting merge rights.

**Rule 1 outranks rule 3** because auto-merge to `main` triggers Publish, which triggers
auto-deploy — an unattended infra deploy against the Pi, and `036-auto-deploy-rollout-fixes` is a
nine-item catalogue of why those fail in ways no test catches.

`frontend/tests/**` and `frontend/e2e/**` are `auto-merge` (they change no shipped pixel);
`frontend/vite.config.ts` is `manual` (it changes the bundle).

**Alternatives considered**: prose in the `pr-review-loop` skill. Rejected — a default-deny path
list is testable as a script and rots invisibly as prose.

**Note**: `run-tests.sh` exports `DRY_RUN=1` for the whole suite. The classifier does not shell out
to anything, so it neither needs nor honours it; the test must not depend on it.

---

## R10. Screenshot hosting

**Decision**: An **orphan branch `pr-screenshots`** in the same repository, maintained as a
**single amended commit, force-pushed**, layout `pr-<n>/<route-slug>-<viewport>[-dark].png`. The
agent pushes from a throwaway `git worktree` so the feature branch and working tree are untouched.
Comments embed `raw.githubusercontent.com` URLs, which render inline.

**Rationale**: GitHub has no public API for uploading an image to a comment; the web UI's endpoint
needs a session cookie. A single amended commit means history never accumulates.

**Alternatives considered**:
- *Workflow artifacts*. Rejected — a reviewer who must download a zip to look at a picture will not
  use the feature.
- *Gists*. Rejected — raw gist URLs do not reliably serve images for markdown embedding.

**Pruning**: the skill deletes `pr-<n>/` after merge. Accepted: image links in merged pull requests
break once pruned. Live PRs always work; historical ones do not.

---

## R11. Screenshot capture

**Decision**: Local agent via **Playwright MCP** (already in the toolchain) against the local
stack, ideally over restored prod data (`local-env`, `prod-data-restore` skills), using
`browser_resize` → `browser_navigate` → `browser_take_screenshot`, per affected route at
**1440×900** and **390×844**, plus dark mode when the diff touches theming.

**Rationale**: A CI Playwright job would need the backend, which needs Mongo, Elasticsearch and
Kafka — a heavy, flaky new job whose screenshots would show empty-state pages unless fixtures were
also seeded. More work for a worse artefact. `frontend/e2e`'s `local` project already assumes a
full stack for exactly this reason.

**Comment**: one comment carrying a `<!-- pr-screenshots -->` marker, edited in place on re-runs —
the find-by-marker pattern `GitHubGateway.statusMarker`/`findCommentId` already uses. It lists the
routes shot, so an omission is visible rather than inferred.

**Deliberately out of scope**: a required `UX Review` check that blocks until screenshots exist. It
would need a second workflow re-emitting the check on comment events, and UX PRs are manual-merge
anyway — the human merging cannot miss a missing screenshot comment.

---

## R12. Auto-merge mechanics

**Decision**: After `gh pr create`, the `pr-review-loop` skill runs the classifier. On `auto-merge`
it runs `gh pr merge --auto --squash` and records that in the PR body. On `ux-review` or `manual`
it does not, and states why.

**It then waits on all signals and reports regardless.** `--auto` is the merge mechanism, not
permission to stop watching: if `Code Review` goes red or a thread is open, the merge does not fire
and the agent must still be present to act.

**Consequence**: because conversation resolution is required, *any* `SUGGESTION` blocks auto-merge
until fixed or declined. Unattended merges will be less common than "backend-only ⇒ auto-merge"
implies. This is intended — the goal is that findings get dealt with, not bypassed.

---

## R13. Documentation and skills scope

**Decision**:
- New `docs/runbooks/pr-governance.md`.
- `docs/runbooks/software-factory.md` gains the `checks: write` rollout trap, check-run semantics
  and thread reconciliation.
- CLAUDE.md *Recent Changes* entry.
- Skills live in `simonjamesrowe/agent-setup` under `components/skills/` (the **source**, not the
  `~/.claude/skills` copy) — a **separate repository**, so those edits are not part of this pull
  request and are tracked as follow-up operator work in the rollout checklist:
  - `pr-review-loop` — thread resolution, classification, auto-merge, screenshots; the
    reviewer-verdict section changes from reading an issue comment to reading the check run.
  - `code-review-triage` — gains "red `Code Review` check" as a trigger alongside silence.
  - In scope because leaving it actively misleads: `pr-review-loop` still claims `Static Analysis`
    fails on every PR because SonarCloud Automatic Analysis is enabled. Stale — PR 122 shows
    `Static Analysis` and `SonarCloud Code Analysis` both green, so the project is on CI-based
    analysis. (Already recorded in memory as `sonarcloud-now-ci-based-analysis`.)

---

## Open items resolved to defaults

| Question | Default taken | Why |
| --- | --- | --- |
| Ruleset id for the `PUT` | Not committed; the runbook documents `gh api /repos/.../rulesets` to discover it, and `POST` to create the first time. | The id is repository state, not source. |
| Check-run `details_url` | The Temporal UI workflow link, same construction as `ReviewMarkdownRenderer.workflowLink`. | Reuses the existing base-URL property and its unconfigured-base fallback. |
| Number of threads fetched | `first: 100`, matching `ConversationGateway`. | Same bound, same rationale; a PR with >100 threads is not a case worth an unbounded loop. |
| Old bare-marker threads | Resolved with a reply on first run after deploy. | Correct outcome, needs no migration code (R4). |
