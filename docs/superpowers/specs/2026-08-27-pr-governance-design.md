# Pull request governance: resolvable findings, a real gate, and auto-merge

**Date:** 2026-08-27
**Status:** design approved, not yet implemented
**Scope:** `software-factory` (Java), GitHub repository configuration, `scripts/`,
and the `pr-review-loop` / `code-review-triage` skills in `simonjamesrowe/agent-setup`.

## Problem

Three related gaps, found by inspecting the live repository rather than assumed:

1. **Findings are deleted, not resolved.** `GitHubGateway.publishReview()` calls
   `deletePreviousFindings()` unconditionally on every re-review and reposts the
   survivors. Deletion is not resolution: there is no record of what was fixed, a
   standing finding reads as brand new on every push, GitHub's "N resolved" counter
   is permanently zero, and a thread root is deleted even when a human has replied
   to it — taking the reply with it. Measured state: PRs 106, 110, 112, 114, 116 and
   122 have **zero** review threads; PR 107 retains one unresolved thread, which is
   the finding that was deliberately *declined*, with the reasoning stranded in a
   separate issue comment the UI cannot connect to the thread.

2. **`main` has no gate at all.** No classic branch protection, no rulesets,
   `allow_auto_merge: false`, and `allow_merge_commit` / `allow_rebase_merge` both
   still true despite CLAUDE.md stating that main is squash-merged.

3. **The reviewer's verdict is invisible to any gate.** It is published as an *issue
   comment*, so no merge path can consider it. Worse, a failed review commonly posts
   nothing at all — silence is the normal presentation of failure — so the signal
   that most needs to block a merge is the one that cannot.

Consequently there is no safe basis for auto-merging anything, and UX-affecting
changes carry no visual evidence for review.

## Design

Two independent blocking mechanisms doing different jobs:

- A **`Code Review` check run** makes `CRITICAL` findings and reviewer *outages*
  hard-red.
- **Require conversation resolution** makes every `WARNING` / `SUGGESTION` block
  until it is either fixed (bot resolves it) or declined with a stated reason (agent
  resolves it).

The split is deliberate: a suggestion cannot be silently ignored, but the gate does
not depend on the model grading severity correctly.

```
push → CI (3 build checks) ───────────────┐
                                           ├→ ruleset on main → auto-merge (backend-only)
webhook → software-factory review ─────────┤
   ├→ inline threads (reconciled)               → require conversation resolution
   └→ Code Review check run                     → required check
                                           │
classify-change.sh → UX-affecting? ────────┴→ screenshots (manual merge, no gate)
```

### 1. `Code Review` check run

`POST /repos/{owner}/{repo}/check-runs`, from a new gateway method.

- Created `in_progress` once the head SHA is known — after `loadPullRequest`, not at
  `openStatusComment` time, because `openStatusComment` holds only a `ReviewRequest`
  and `expectedHeadSha` is nullable on the manual-review path.
- Completed `success` when the verdict is `APPROVE` or `COMMENT` **and** no
  `CRITICAL` finding exists.
- Completed `failure` when the verdict is `REQUEST_CHANGES` **or** any `CRITICAL`
  finding exists. Both conditions are checked, not just the verdict: the engine can
  emit a verdict inconsistent with its own severities.
- `failure` on the `publishFailure` path too, with the Temporal UI link in the check
  summary.
- Only `success` and `failure` are ever used. Whether `neutral` satisfies a
  ruleset's required check is version-dependent behaviour the gate must not rest on.

**Fail-closed, by design.** If the review dies before the head SHA is known, no
check run is created, and a required check that is absent blocks the merge. This is
the fix for "silence is the normal presentation of failure": silence now blocks.
Accepted cost — an outage of the `software-factory` container stops all merging
until it is fixed or the ruleset is hand-edited.

**Rollout trap — the most dangerous item in this change.**
`GitHubCredentials.mintInstallationToken` requests an explicit permission set, and
GitHub **422s the entire token request** if it asks for more than the installation
was granted; that method converts any non-2xx into an exception. Adding
`"checks": "write"` to the payload before granting it on the App therefore breaks
*every* token mint, taking down code review **and** the feedback loop. The App
permission must be granted and the installation update accepted **before** the image
requesting it is deployed. Identical in shape to the `contents: write` rollout
already recorded in `docs/runbooks/software-factory.md`.

`ReviewMarkdownRenderer.ADVISORY` currently reads *"Advisory only; this reviewer does
not approve or block merges."* That becomes false and is rewritten, with its test.

### 2. Thread reconciliation

`FINDING_MARKER` is today a bare constant with no per-finding identity, which is why
delete-everything was the only available strategy. It gains a fingerprint:

```
<!-- temporal-code-review-finding:<hash> -->
```

where `hash = sha256(file + "\0" + normalise(title))`, and `normalise` lowercases,
collapses whitespace and strips punctuation. Deliberately **not** line (lines move on
every rebase) and **not** severity (the model re-grades).

`deletePreviousFindings` is replaced by a reconcile against existing threads fetched
over GraphQL — REST cannot see thread resolution state, which is why
`ConversationGateway` already uses GraphQL for this same data.

| State | Action |
| --- | --- |
| Fingerprint in new report, open thread exists | leave untouched |
| Fingerprint in new report, thread resolved | post a fresh thread (it regressed) |
| Thread exists, fingerprint absent from report | reply, then `resolveReviewThread` |
| Fingerprint in new report, no thread | post new thread |
| Thread has a non-bot reply | never deleted — nothing is deleted now |

Resolution requires only `pull_requests: write`, already held.

**Accepted limitation.** The fingerprint is only as stable as the model's phrasing of
the title, so a re-worded title for the same underlying issue reads as "one resolved,
one new". The reply text is therefore **"No longer reported as of `<sha>`"**, not
"Fixed" — truthful under both a genuine fix and a re-wording. Stable engine-supplied
IDs were considered and rejected: they cannot be stable across independent runs.

### 3. Ruleset and repository settings

Committed as `.github/rulesets/main.json`, applied with
`gh api PUT /repos/.../rulesets/{id}`. Kept in the repository because GitHub's UI
state is otherwise undocumented, unreviewable and unrestorable configuration.

**Required checks — four:** `Backend Build & Test`, `Frontend Build & Test`,
`Software Factory Build & Test`, `Code Review`.

**Excluded, with reasons:**

| Check | Why excluded |
| --- | --- |
| `Static Analysis` | `continue-on-error: true` means the job reports success even when the scanner is broken. Required, it would be decorative. |
| `SonarCloud Code Analysis` | Requiring it makes the quality gate blocking, contradicting the deliberate unset `sonar.qualitygate.wait` and leaving no legitimate escape hatch for a false positive — the constitution bans manual gate overrides. |
| `evaluate` | `paths:`-filtered, so its normal state on an unrelated PR is *absent*. A required check that is absent blocks forever. |

**Other settings:** PR required before merging; **zero** required approvals (GitHub
forbids approving your own PR, so requiring one permanently deadlocks a solo
maintainer); require conversation resolution; require linear history; block force
pushes; restrict deletions; **no bypass actors** — a standing admin bypass would
quietly make the whole gate optional, so escalation is editing the ruleset, which is
visible.

**Repository settings:** `allow_auto_merge: true`, `allow_merge_commit: false`,
`allow_rebase_merge: false` — making CLAUDE.md's squash-only claim true.

**Consequence:** conversation resolution applies to *all* threads, including any you
open yourself and any SonarCloud might post inline. It is a real gate, not a bot gate.

### 4. `scripts/classify-change.sh`

One consumer only — `pr-review-loop`. There is **no `classify` job and no `ci.yml`
change**: the second consumer (a CI screenshot job) was eliminated when screenshots
moved to the local agent. It remains a script rather than skill prose because a
default-deny path list is testable as a script and rots invisibly as prose.

Output is `GITHUB_OUTPUT`-shaped so it stays usable if CI ever needs it:

```
category=auto-merge|ux-review|manual
ux_affecting=true|false
```

Precedence, highest first:

1. **`manual`** — `docker-compose*.yml`, `scripts/**`, `config/**`, `.github/**`,
   `gradle*`, root build files, `frontend/*.config.*`, `frontend/package*.json`
2. **`ux-review`** — `frontend/src/**`, `frontend/index.html`, `frontend/public/**`
3. **`auto-merge`** — all paths within `backend/**`, `software-factory/**`,
   `docs/**`, `specs/**`, `frontend/tests/**`, `frontend/e2e/**`, root `*.md`
4. **`manual`** — anything else

Rule 4 is load-bearing: **an unrecognised path is `manual`, never `auto-merge`**, so
a new top-level directory added later defaults to needing a human rather than
inheriting merge rights. Rule 1 outranks rule 3 because auto-merge to `main` triggers
Publish, which triggers auto-deploy — an unattended infra deploy against the Pi, and
`036-auto-deploy-rollout-fixes` is a nine-item catalogue of why those fail in ways no
test catches. `frontend/tests/**` and `frontend/e2e/**` are `auto-merge` (they change
no shipped pixel); `frontend/vite.config.ts` is `manual` (it changes the bundle).

### 5. Auto-merge

After `gh pr create`, the skill runs the classifier. On `auto-merge` it enables
`gh pr merge --auto --squash` and records that in the PR body. On `ux-review` or
`manual` it does not, and states why.

It then waits on all signals and reports **regardless**. `--auto` is the merge
mechanism, not permission to stop watching: if `Code Review` goes red or a thread is
open, the merge does not fire and the agent must still be present to act.

The intended steady state needs no agent action at the merge step: fix → push →
re-review → finding absent → bot replies and resolves → conversation resolution
satisfied → checks green → GitHub merges.

**Consequence:** because conversation resolution is required, *any* `SUGGESTION`
blocks auto-merge until fixed or declined. Unattended merges will therefore be less
common than "backend-only ⇒ auto-merge" implies. This is intended — the goal is that
findings get dealt with, not bypassed — but it is stricter than "green checks ⇒
merged".

### 6. Screenshots for UX-affecting pull requests

**Hosting.** GitHub has no public API for uploading an image to a comment; the web
UI's endpoint needs a session cookie. So: an **orphan branch `pr-screenshots`** in the
same repository, maintained as a **single amended commit, force-pushed**, so history
never accumulates. Layout `pr-<n>/<route-slug>-<viewport>[-dark].png`. The agent
pushes from a throwaway `git worktree`, leaving the feature branch and working tree
untouched. Comments embed `raw.githubusercontent.com` URLs, which render inline —
the entire point of the feature.

Rejected: workflow artifacts (a reviewer downloading a zip to look at a picture will
not use the feature) and gists (raw gist URLs do not reliably serve images for
markdown embedding).

**Capture.** Local agent via **Playwright MCP** — already in the toolchain — against
the local stack, ideally over restored prod data (`local-env`, `prod-data-restore`),
so screenshots show real content. `browser_resize` → `browser_navigate` →
`browser_take_screenshot`, per affected route at 1440×900 and 390×844, plus dark mode
when the diff touches theming.

Rejected: a CI Playwright job. The frontend needs the backend, which needs Mongo,
Elasticsearch and Kafka — a heavy, flaky new job whose screenshots would show
empty-state pages unless fixtures were also seeded. More work for a worse artifact;
`frontend/e2e`'s `local` project already assumes a full stack for this reason.

**Comment.** One comment carrying a `<!-- pr-screenshots -->` marker, edited in place
on re-runs — the find-by-marker pattern `GitHubGateway.statusMarker` already uses. It
lists the routes shot, so an omission is visible rather than inferred.

**Pruning.** The skill deletes `pr-<n>/` after merge. **Accepted:** image links in
merged pull requests break once pruned. Live PRs always work; historical ones do not.

**Deliberately out of scope:** a required `UX Review` check that blocks until
screenshots exist. It would need a second workflow re-emitting the check on comment
events, and UX PRs are manual-merge anyway — the human merging cannot miss a missing
screenshot comment.

## Testing

| What | How |
| --- | --- |
| Fingerprint normalisation | unit test over re-worded and re-punctuated titles |
| Reconcile decision table | pure static `(existing threads, new report) → actions`, table-tested, following the package-private-static pattern of `toPullRequestContext` / `findCommentIds` |
| Check-run conclusion mapping | every `Verdict` × severity combination, including `APPROVE` + a `CRITICAL` (must be `failure`) |
| `classify-change.sh` | `scripts/test/test-classify-change.sh` — path set → expected category, including the unrecognised-path default. `run-tests.sh` auto-discovers `test-*.sh`. |
| Ruleset | not unit-testable; a `gh api` drift-check command documented in the runbook |

No test can cover the App permission grant, the ruleset application or the screenshot
push. Those are operator steps, which is why the rollout order is part of the
deliverable.

## Documentation

- New `docs/runbooks/pr-governance.md` — the ruleset, why three checks are excluded,
  emergency bypass, auto-merge policy, screenshot mechanics.
- `docs/runbooks/software-factory.md` — the `checks: write` rollout trap, check-run
  semantics, thread reconciliation.
- CLAUDE.md *Recent Changes* entry.
- `simonjamesrowe/agent-setup`, `components/skills/` (the source, not the
  `~/.claude/skills` copy):
  - `pr-review-loop` — resolving threads, classification, auto-merge, screenshots;
    the reviewer-verdict section changes from reading an issue comment to reading the
    check run.
  - `code-review-triage` — gains "red `Code Review` check" as a trigger alongside
    silence.
  - In scope because leaving it actively misleads: `pr-review-loop` still claims
    `Static Analysis` fails on every PR because SonarCloud Automatic Analysis is
    enabled. Stale — PR 122 shows `Static Analysis` and `SonarCloud Code Analysis`
    both green, so the project is on CI-based analysis.

## Rollout order

The code, script, ruleset JSON and documentation land as **one pull request** — which
`classify-change.sh` itself categorises as `manual`, since it touches `.github/**` and
`scripts/**`. The steps below interleave that merge with **operator actions that no
pull request can perform**: granting an App permission, deploying, and applying the
ruleset. Committing `.github/rulesets/main.json` does not apply it; step 5 does.

This sequence is load-bearing.

1. **Grant the GitHub App `checks: write`** and accept the installation permission
   update. Before any deploy.
2. Merge the `software-factory` change (check run + reconcile). Ruleset **not**
   applied yet.
3. Deploy: `docker compose -f docker-compose.prod.yml up -d --no-deps software-factory`
   **and `deployer`** — both run `FACTORY_IMAGE`, and `deployer` never recreates
   itself.
4. Confirm on a real pull request that a `Code Review` check appears and that threads
   resolve.
5. **Only then** apply the ruleset and repository settings.
6. Enable `allow_auto_merge`; update the skills.

Step 5 following step 4 is not tidiness. Applying the ruleset while nothing publishes
a `Code Review` check makes that required check permanently absent, blocking **every**
pull request — including the one that would fix it. With no bypass actors, recovery
means hand-editing the ruleset in the GitHub UI.
