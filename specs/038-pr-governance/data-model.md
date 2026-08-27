# Phase 1 Data Model: Pull Request Governance

**Feature**: 038-pr-governance

**No persistence.** Nothing in this feature is stored in MongoDB, and no Mongock change unit is
needed. Every entity below is either a transient in-memory value passed between an activity and
GitHub, or a committed configuration file. The state that matters lives in GitHub (thread
resolution, check-run conclusion, ruleset) — which is the point: the design deliberately replaces
"the reviewer remembers what it posted" with "GitHub is the record".

---

## 1. `FindingFingerprint` (new, `codereview/domain`)

A derived, stable identity for one finding, used to match it across independent review runs.

| Field | Type | Notes |
| --- | --- | --- |
| `value` | `String` | Lowercase hex SHA-256 |

**Derivation**: `sha256(file + "\0" + normalise(title))`

**`normalise`**: `Locale.ROOT` lowercase → strip every character that is not a letter, digit or
whitespace → collapse whitespace runs to a single space → trim.

**Deliberately excluded from the hash**:
- `line` — lines move on every rebase
- `severity` — the model re-grades between runs
- `explanation` / `recommendation` — prose varies far more than titles

**Validation**: `file` and `title` must be non-blank; a finding whose normalised title is empty
after stripping falls back to the raw title, so it still gets *an* identity rather than colliding
with every other empty-title finding.

**Rendered form** (in the inline comment body, replacing the bare `FINDING_MARKER`):

```
<!-- temporal-code-review-finding:<value> -->
```

**Accepted limitation**: a re-worded title reads as "one resolved, one new". This is why the
resolution reply is *"No longer reported as of `<sha>`"* and never *"Fixed"*.

---

## 2. `ExistingThread` (new, `codereview/domain`)

One review conversation already on the pull request, as seen over GraphQL.

| Field | Type | Notes |
| --- | --- | --- |
| `nodeId` | `String` | GraphQL node id — the handle `resolveReviewThread` takes |
| `fingerprint` | `String` (nullable) | Parsed out of the root comment's marker; `null` for a human-opened thread, a SonarCloud thread, or a pre-change bare-marker thread |
| `resolved` | `boolean` | `isResolved` |
| `hasNonBotReply` | `boolean` | Any comment after the first whose author `__typename != "Bot"` |

**Source**: `reviewThreads(first: 100)`, matching `ConversationGateway`'s bound.

**Note**: `hasNonBotReply` is carried for the runbook and for logging, not for control flow —
nothing is deleted, so a human reply is safe by construction.

---

## 3. `ThreadAction` (new, `codereview/domain`)

The output of the pure reconcile function. A sealed set of exactly three actions; **there is no
`DELETE`**.

| Action | Payload | When |
| --- | --- | --- |
| `LEAVE` | `nodeId` | fingerprint in new report, open thread exists |
| `POST_NEW` | `ReviewFinding` | fingerprint in new report and no thread, **or** its thread is resolved (it regressed) |
| `REPLY_AND_RESOLVE` | `nodeId` | thread exists, fingerprint absent from the new report |

### Decision table (the reconcile contract)

| Existing thread state | Fingerprint present in new report | Action |
| --- | --- | --- |
| open | yes | `LEAVE` |
| resolved | yes | `POST_NEW` |
| open | no | `REPLY_AND_RESOLVE` |
| resolved | no | `LEAVE` (already resolved; nothing to do) |
| none | yes | `POST_NEW` |
| `fingerprint == null` (human / third-party / legacy) | n/a | `REPLY_AND_RESOLVE` only if it carries the **legacy bare marker**; a thread with no reviewer marker at all is `LEAVE` — the reviewer must never touch conversations it did not open |

**Reply text**: ``No longer reported as of `<shortSha>`.``

**Purity**: implemented as a package-private `static` method over
`(List<ExistingThread>, List<ReviewFinding>, String headSha)`, following
`GitHubGateway.toPullRequestContext` and `ConversationGateway.toConversation`. Table-tested with
no HTTP.

---

## 4. `CheckRunConclusion` (new, `codereview/domain`)

| Value | Condition |
| --- | --- |
| `SUCCESS` | verdict is `APPROVE` or `COMMENT` **and** no finding has severity `CRITICAL` |
| `FAILURE` | verdict is `REQUEST_CHANGES` **or** any finding has severity `CRITICAL` |

**Only these two.** Whether `neutral` satisfies a ruleset's required check is version-dependent
GitHub behaviour the gate must not rest on.

**State transitions**:

```
(no check run)  ──loadPullRequest succeeds──▶  in_progress
                                                   │
                        publishReview ─────────────┼──▶ completed:success
                                                   ├──▶ completed:failure
                        publishFailure ────────────┘──▶ completed:failure

(no check run) ──review dies before head SHA is known──▶ (stays absent → merge blocked)
```

The absent state is a first-class outcome, not an error path: it is how a reviewer outage blocks.

**Derivation is a pure static function** over `(Verdict, List<ReviewFinding>)`, so all six
verdict × has-critical combinations are table-testable — including `APPROVE` + `CRITICAL` ⇒
`FAILURE`, which is the one the engine can get wrong.

---

## 5. `ChangeCategory` (new, `scripts/classify-change.sh` output)

Not a Java type. A pair of `GITHUB_OUTPUT`-shaped lines.

```
category=auto-merge|ux-review|manual
ux_affecting=true|false
```

| Category | `ux_affecting` | Merge disposition |
| --- | --- | --- |
| `auto-merge` | `false` | arm `gh pr merge --auto --squash` |
| `ux-review` | `true` | manual merge; capture screenshots |
| `manual` | `false` | manual merge; state why in the PR body |

**Precedence** (first match wins; rule 1 outranks rule 3):

1. `manual` — `docker-compose*.yml`, `scripts/**`, `config/**`, `.github/**`, `gradle*`, root build
   files, `frontend/*.config.*`, `frontend/package*.json`
2. `ux-review` — `frontend/src/**`, `frontend/index.html`, `frontend/public/**`
3. `auto-merge` — `backend/**`, `software-factory/**`, `docs/**`, `specs/**`, `frontend/tests/**`,
   `frontend/e2e/**`, root `*.md`
4. `manual` — **default for any unrecognised path**

**Invariant**: the function is total and fails closed. There is no input for which the answer is
"unknown" and no input for which an unmatched path yields `auto-merge`.

---

## 6. `Ruleset` (new, `.github/rulesets/main.json`)

Committed configuration, not runtime data. GitHub rulesets payload shape.

| Field | Value | Why |
| --- | --- | --- |
| `target` | `branch` | |
| `enforcement` | `active` | |
| `conditions.ref_name.include` | `~DEFAULT_BRANCH` | |
| `bypass_actors` | `[]` | A standing admin bypass makes the gate optional; escalation must be a visible ruleset edit |
| `pull_request.required_approving_review_count` | `0` | Self-approval is forbidden; requiring one deadlocks a solo maintainer permanently |
| `pull_request.required_review_thread_resolution` | `true` | The `WARNING`/`SUGGESTION` gate |
| `required_status_checks` | 4 contexts (below) | |
| `required_linear_history` | present | |
| `non_fast_forward` | present | blocks force pushes |
| `deletion` | present | restricts branch deletion |

**Required contexts** — exactly four, matching `ci.yml` job `name:` values:
`Backend Build & Test`, `Frontend Build & Test`, `Software Factory Build & Test`, `Code Review`.

**Excluded**: `Static Analysis` (`continue-on-error: true`, so success is meaningless),
`SonarCloud Code Analysis` (would make an intentionally advisory gate blocking with no legitimate
escape hatch, and Constitution III bans manual overrides), `evaluate` (`paths:`-filtered, so
normally absent, and an absent required check blocks forever).

**Companion repository settings** — a separate `PATCH /repos/{owner}/{repo}` call, not part of the
ruleset: `allow_auto_merge: true`, `allow_merge_commit: false`, `allow_rebase_merge: false`.

**Committing this file does not apply it.** Application is operator step 5, after step 4 confirms
a real `Code Review` check appears.

---

## 7. `ScreenshotSet` (new, orphan branch `pr-screenshots`)

Not application data. Files on a git branch.

| Aspect | Value |
| --- | --- |
| Branch | `pr-screenshots`, orphan, **single amended commit, force-pushed** — history never accumulates |
| Path | `pr-<n>/<route-slug>-<viewport>[-dark].png` |
| Viewports | `1440x900` (desktop), `390x844` (mobile) |
| Dark variants | only when the diff touches theming |
| Embed URL | `raw.githubusercontent.com/<owner>/<repo>/pr-screenshots/pr-<n>/<file>` |
| Written from | a throwaway `git worktree`, so the feature branch and working tree are untouched |
| Comment | one, marked `<!-- pr-screenshots -->`, edited in place, listing the routes captured |
| Pruning | `pr-<n>/` deleted after merge; merged PRs then have broken image links (accepted) |

---

## Entity relationships

```
ReviewReport ──has many──▶ ReviewFinding ──derives──▶ FindingFingerprint
                                │                            │
                                │                            │ matched against
                                ▼                            ▼
                        CheckRunConclusion            ExistingThread (GraphQL)
                          (pure fn)                          │
                                │                            ▼
                                ▼                       ThreadAction
                        Code Review check run        (pure fn, no DELETE)
                                │                            │
                                └────────┬───────────────────┘
                                         ▼
                                    Ruleset gate ◀── ChangeCategory ──▶ auto-merge / ScreenshotSet
```
