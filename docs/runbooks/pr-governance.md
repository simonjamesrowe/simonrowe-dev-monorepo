# Runbook: pull request governance

**Feature:** 038-pr-governance · **Spec:** `specs/038-pr-governance/`

Two independent blocking mechanisms guard `main`, doing different jobs:

- The **`Code Review` check run** makes `CRITICAL` findings and reviewer *outages* hard-red.
- **Required conversation resolution** makes every `WARNING` / `SUGGESTION` block until it is
  either fixed (the bot resolves it) or declined with a stated reason (a person resolves it).

The split is deliberate. A suggestion cannot be silently ignored, but the gate does not depend on
the model grading severity correctly.

```
push → CI (3 build checks) ───────────────┐
                                          ├→ ruleset on main → GitHub merges an armed PR
webhook → software-factory review ────────┤
   ├→ inline threads (reconciled)              → require conversation resolution
   ├→ Code Review check run                    → required check
   └→ arms auto-merge if every path allows it  (MergeDisposition = classify-change.sh rules)
                                          │
classify-change.sh → UX-affecting? ───────┴→ screenshots (manual merge, no gate)
```

---

## The ruleset

Committed as [`.github/rulesets/main.json`](../../.github/rulesets/main.json), because GitHub's UI
state is otherwise undocumented, unreviewable and unrestorable configuration.

**Committing the file does not apply it.** Applying it is an operator step — see
[Rollout](#rollout), where its position in the sequence is load-bearing.

### Required checks — four

| Check | Source |
| --- | --- |
| `Backend Build & Test` | `ci.yml` job `backend` |
| `Frontend Build & Test` | `ci.yml` job `frontend` |
| `Software Factory Build & Test` | `ci.yml` job `software-factory` |
| `Code Review` | the check run published by `software-factory` |

### Excluded, and why

JSON carries no comments, so the reasoning lives here.

| Check | Why it is **not** required |
| --- | --- |
| `Static Analysis` | The `sonar` job is `continue-on-error: true`, so it reports success even when the scanner is broken. Required, it would be purely decorative — a green tick that means nothing. |
| `SonarCloud Code Analysis` | Requiring it makes the quality gate blocking, which contradicts the deliberate unset `sonar.qualitygate.wait` and leaves no legitimate escape hatch for a false positive. Constitution Principle III bans manual overrides of quality gates, so a rule that must sometimes be broken is the wrong rule. |
| `evaluate` | `paths:`-filtered in `evals.yml`, so its normal state on an unrelated pull request is *absent* — and a required check that is absent blocks forever. |

### Other settings

| Setting | Value | Why |
| --- | --- | --- |
| Pull request required | yes | |
| Required approvals | **0** | GitHub forbids approving your own pull request, so requiring one permanently deadlocks a solo maintainer. |
| Conversation resolution | required | This is the `WARNING`/`SUGGESTION` gate. |
| Linear history | required | |
| Force pushes | blocked | |
| Branch deletion | restricted | |
| Bypass actors | **Repository admin** (`actor_id: 5`, `bypass_mode: always`) | Added 2026-08-29, reversing 038's original `bypass_actors: []`. Without it a `software-factory` outage stops **all** merging with no recovery short of hand-editing the gate. Every use is visible in rule insights. |

Repository settings, applied separately: `allow_auto_merge: true`, `allow_merge_commit: false`,
`allow_rebase_merge: false` — making CLAUDE.md's long-standing squash-only claim actually true.

**Conversation resolution applies to *all* threads**, including any you open yourself and any
SonarCloud posts inline. It is a real gate, not a bot gate.

---

## Rollout

Steps 1, 3, 4, 5 and 6 are operator actions no pull request can perform. **This sequence is
load-bearing** — two of its steps break the repository if taken early.

### 1. Grant the App `checks: write` — before any deploy

GitHub App settings → Permissions → **Checks: Read and write**, then accept the installation
permission update.

> `GitHubCredentials.mintInstallationToken` sends an explicit `permissions` block, and GitHub
> **422s the entire token request** when it asks for more than the installation was granted. That
> method turns any 4xx into a non-retryable failure. Deploying the image that requests
> `checks: write` before the grant lands therefore breaks **every** token mint — taking down code
> review *and* the feedback loop, silently, because reporting the failure needs a token too.
> Identical in shape to the `contents: write` rollout already recorded in
> [software-factory.md](software-factory.md).

```bash
gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/installation --jq '.permissions'
# expect "checks": "write"
```

### 2. Merge the pull request

The ruleset is not applied yet. That pull request classifies as `manual` under its own classifier
(it touches `.github/**` and `scripts/**`), so it is merged by hand.

### 3. Deploy — both containers

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps software-factory deployer
```

`deployer` runs the same `FACTORY_IMAGE` and **never recreates itself**. Omitting it leaves the
deployer on the old image indefinitely — the same shape as the bug that left `software-factory`
stale for months.

### 4. Confirm on a real pull request

- a `Code Review` check appears on the head commit and completes `success` or `failure`;
- a finding you fix is replied to and resolved on the next push, not deleted;
- a finding you do not fix keeps its original thread, posting time and replies.

```bash
gh pr checks <n>
gh api graphql -f query='
  query { repository(owner:"simonjamesrowe", name:"simonrowe-dev-monorepo") {
    pullRequest(number: <n>) { reviewThreads(first:20) { nodes { isResolved } } } } }'
```

### 5. Only then, apply the ruleset

```bash
gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets           # find the id, or none yet
gh api --method POST /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets \
  --input .github/rulesets/main.json                                   # first time
gh api --method PUT /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets/<id> \
  --input .github/rulesets/main.json                                   # thereafter
```

> **Step 5 following step 4 is not tidiness.** Applying the ruleset while nothing publishes a
> `Code Review` check makes that required check permanently absent, blocking **every** pull request
> — including the one that would fix it. The repository-admin bypass is what makes that
> recoverable without hand-editing the ruleset in the GitHub UI.

### 6. Repository settings, then the skills

```bash
gh api --method PATCH /repos/simonjamesrowe/simonrowe-dev-monorepo \
  -F allow_auto_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false
```

Then update `simonjamesrowe/agent-setup` → `components/skills/` (the **source**, not the
`~/.claude/skills` copy): `pr-review-loop` and `code-review-triage`. See
[Skills](#skills-separate-repository).

---

## Drift check

```bash
KEYS='{name,target,enforcement,bypass_actors,conditions,rules}'
diff <(gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets/<id> --jq "$KEYS" | jq -S .) \
     <(jq -S "$KEYS" .github/rulesets/main.json)
```

The ruleset is not unit-testable; this command is the substitute. Run it after any manual edit in
the GitHub UI.

**`bypass_actors` is in that key list deliberately.** It was not until 2026-08-29, which meant the
drift check could not see the single field most likely to be widened by hand — silently reporting
"no drift" while someone had granted a standing bypass. `jq -S` sorts keys, so ordering differences
never masquerade as drift.

`dismissal_restriction` and `required_reviewers` are pinned in the committed file purely so this
diff comes back **empty**. GitHub echoes them as empty defaults inside the `pull_request` rule
whether or not you send them; leaving them out made the check report two lines of noise on a
perfectly clean ruleset, which is how a drift check stops being read.

---

## Emergency bypass

**Repository admins can bypass every rule** (`bypass_mode: always`), so an admin can merge past a
broken gate and can push directly to `main`. That is a deliberate reversal of 038's original
`bypass_actors: []`, taken on 2026-08-29.

The original reasoning was sound and still applies as a *norm*: a standing bypass makes the gate
optional for whoever holds it, and the discipline the gate buys is only worth what the holder
declines to bypass. What changed is the assessment of the alternative. With no bypass actor, the
recovery path for an absent `Code Review` check was hand-editing the required contexts in the GitHub
UI and remembering to restore them — a fiddly, easy-to-forget manual step performed under pressure,
on the one repository whose gate had just proven it could wedge itself.

**So the bypass is an escape hatch, not a merge strategy.** Reach for it only after step 1:

1. **Prefer fixing the signal.** A red `Code Review` usually means a real finding. An *absent* one
   means `software-factory` is not running — check for a live poller on the `code-review` task
   queue, not just the container healthcheck, since a container can be `healthy` with no poller
   registered ([software-factory.md](software-factory.md)). The manual trigger on
   `/admin/software-factory` re-reviews a commit the webhook can never repeat.
2. Only if the reviewer genuinely cannot be restored, merge as an admin. **Every bypass is recorded
   in the repository's rule-insights log** — that visibility is now the whole control, since the
   act itself is no longer blocked. Say in the pull request that you bypassed, and why.

Because the hatch exists, the drift check matters more, not less: a bypass leaves the committed
ruleset and the live one identical, so nothing but rule insights will tell you it happened.

**A reviewer outage no longer stops all merging** — an admin can get past it. It still stops
*unattended* merging, which is the part that mattered: previously a failed review commonly posted
nothing at all, so the signal that most needed to block a merge was the one that could not.

---

## Auto-merge policy

**The reviewer arms auto-merge, and GitHub performs the merge.** At the end of every published
review, `software-factory` decides whether the pull request may merge unattended and, if so,
arms GitHub's native squash auto-merge on the reviewed commit. GitHub then merges it the moment
the ruleset is satisfied: all four required checks green and every conversation resolved. No
webhook tells the factory "all checks passed", and none is needed. The reviewer owns the one
moment that matters, because its own `Code Review` check is still in progress while it arms, and
nothing can merge until that check completes.

Switched by `FACTORY_CODEREVIEW_AUTO_MERGE_ENABLED`, declared on `software-factory` in
`docker-compose.prod.yml` with a default of `true` (off in `application.yml`, so tests and local
runs arm nothing). The review summary comment gets one line saying what happened, e.g.
**Auto-merge:** not armed: `` `scripts/x.sh` needs a human to merge ``.

It arms only if **every** rule passes. The first rule to fail is the reason given
(`AutoMergePolicy`):

1. The flag is on.
2. The pull request is not a draft.
3. The head is in this repository, not a fork. The repository is public, so a stranger's pull
   request with a clean review must never merge itself.
4. The author has `write` or `admin` permission on the repository, read from
   `GET /repos/{o}/{r}/collaborators/{login}/permission`. It is **not** read from
   `author_association`: that field is computed for the viewer, and an App token cannot see a
   private organisation membership. So it reported this repository's owner as `CONTRIBUTOR`, and
   the first live pull request (#194) was refused. A permission that cannot be read counts as
   `none`.
5. It carries neither `no-auto-merge` (a person's opt-out) nor `agent-feedback` (feedback-loop
   guidance edits agent instructions, which a human should merge).
6. The `Code Review` conclusion is green: no `REQUEST_CHANGES` and no `CRITICAL`, the same rule as
   the check run.
7. The live head is still the commit that was reviewed.
8. **Every** changed path classifies `auto-merge`, including the old side of every rename, read
   from GitHub's `/pulls/{n}/files`. It is not read from the review workspace, whose list is
   filtered and capped. A listing that falls short of GitHub's `changed_files` count arms nothing.

Open `SUGGESTION` threads are deliberately not a rule. Required conversation resolution already
holds the merge until each one is fixed or declined.

Three mechanisms keep a "yes" from outliving the commit it was about:

- **Withdraw before every review.** GitHub keeps auto-merge armed across pushes from anyone with
  write access. So each published review first withdraws any arm a *bot* made, and fails the
  review, turning its check red, if it cannot. Otherwise a "yes" for a docs-only commit would
  merge a later push that edits `docker-compose.prod.yml`. An arm a *person* made is never
  touched. "A bot" is read from `enabled_by.type`, not a configured login: a mistyped login would
  silently stop the withdraw, and that is the one failure here that merges something unreviewed.
- **`expectedHeadOid`.** The arm names the reviewed commit, and GitHub refuses it if anything has
  been pushed since.
- **Withdraw on failure.** A review that fails after arming (say, publishing the summary 500s)
  withdraws the arm as well as turning the check red.

Switching the flag off stops new arms but still withdraws the reviewer's own arms on re-review.
Adding `no-auto-merge` takes effect at the next review, i.e. the next push. To stop an armed pull
request *now*, disable auto-merge on it in GitHub as well.

A **dry run** from `/admin/software-factory` decides but never withdraws or arms. The run banner
reads "auto-merge would arm (dry run)" or names the rule that failed.

### The classifier has two implementations, held together by one fixture

`scripts/classify-change.sh` is what a human or the `pr-review-loop` skill runs.
`software-factory/.../codereview/domain/MergeDisposition.java` is what the reviewer arms from,
because the container has no checkout to diff. **Both test suites read
`scripts/test/fixtures/merge-disposition-cases.tsv`**, so a rule changed in one and not the other
fails a build. Add new cases there, not in either test. The Java glob copies the script's
`case` semantics exactly: `*` crosses `/`, so `frontend/*.config.*` matches
`frontend/src/theme.config.ts`.

| Category | What happens |
| --- | --- |
| `auto-merge` | the reviewer arms squash auto-merge; GitHub merges when the gate opens |
| `ux-review` | not armed; the skill captures screenshots and a human merges |
| `manual` | not armed; the summary names the path that forced it |

Precedence, highest first:

1. **`manual`** — `docker-compose*.yml`, `scripts/**`, `config/**`, `.github/**`, `gradle*`, root
   build files, `frontend/*.config.*`, `frontend/package*.json`
2. **`ux-review`** — `frontend/src/**`, `frontend/index.html`, `frontend/public/**`
3. **`auto-merge`** — `backend/**`, `software-factory/**`, `docs/**`, `specs/**`,
   `frontend/tests/**`, `frontend/e2e/**`, root `*.md`
4. **`manual`** — anything else

**Rule 4 is load-bearing**: an unrecognised path is `manual`, never `auto-merge`, so a new
top-level directory added later defaults to needing a human rather than inheriting merge rights.

**Rule 1 outranks rule 3** because an auto-merge to `main` triggers Publish, which triggers
auto-deploy — an unattended infrastructure deploy against the Pi, and `036-auto-deploy-rollout-fixes`
is a nine-item catalogue of why those fail in ways no test catches. `frontend/tests/**` and
`frontend/e2e/**` are `auto-merge` (they change no shipped pixel); `frontend/vite.config.ts` is
`manual` (it changes the bundle).

Tested by `scripts/test/test-classify-change.sh`, auto-discovered by `scripts/test/run-tests.sh`.
Try it by hand — it reads paths on stdin, so it needs no repository state:

```bash
printf 'backend/src/A.java\ndocker-compose.prod.yml\n' | scripts/classify-change.sh
# category=manual        <- rule 1 outranks rule 3
```

### Armed is not permission to stop watching

An armed pull request still needs someone watching every signal and reporting the outcome. If
`Code Review` goes red or a thread is open, the merge does not fire and someone must act.

### Expect fewer unattended merges than "backend-only ⇒ auto-merge" implies

Conversation resolution is required, so *any* `SUGGESTION` blocks the merge until it is fixed or
declined with a stated reason on the thread itself. That is intended — the goal is that findings
get dealt with, not bypassed. The steady state needs no action at the merge step: fix → push →
re-review → finding absent → bot replies and resolves → conversation resolution satisfied → checks
green → GitHub merges.

---

## Screenshots for UX-affecting pull requests

Only when the classifier reports `ux_affecting=true`.

### Capture

Local agent via **Playwright MCP**, against the local stack, ideally over restored production data
(`local-env`, `prod-data-restore` skills) so the pages show real content rather than empty states.
`browser_resize` → `browser_navigate` → `browser_take_screenshot`, per affected route at
**1440×900** and **390×844**, plus dark mode when the diff touches theming.

*Rejected: a CI Playwright job.* The frontend needs the backend, which needs Mongo, Elasticsearch
and Kafka — a heavy, flaky new job whose screenshots would show empty-state pages unless fixtures
were also seeded. More work for a worse artefact; `frontend/e2e`'s `local` project already assumes
a full stack for exactly this reason.

### Hosting

GitHub has no public API for uploading an image to a comment; the web UI's endpoint needs a session
cookie. So: an **orphan branch `pr-screenshots`** in this repository, maintained as a **single
amended commit, force-pushed**, so history never accumulates.

- Layout: `pr-<n>/<route-slug>-<viewport>[-dark].png`
- Pushed from a throwaway `git worktree`, leaving the feature branch and working tree untouched
- Comments embed `raw.githubusercontent.com` URLs, which render inline — the entire point

*Rejected:* workflow artifacts (a reviewer who must download a zip to look at a picture will not
use the feature) and gists (raw gist URLs do not reliably serve images for markdown embedding).

### Comment

One comment carrying a `<!-- pr-screenshots -->` marker, edited in place on re-runs — the
find-by-marker pattern `GitHubGateway.statusMarker` already uses. It **lists the routes shot**, so
an omission is visible rather than inferred.

### Pruning

Delete `pr-<n>/` after merge and force-push again. **Accepted:** image links in merged pull
requests break once pruned. Live pull requests always work; historical ones do not.

### Deliberately out of scope

A required `UX Review` check blocking until screenshots exist. It would need a second workflow
re-emitting the check on comment events, and UX pull requests are manual-merge anyway — the human
merging cannot miss a missing screenshot comment.

---

## Skills (separate repository)

Live in `simonjamesrowe/agent-setup` under `components/skills/` — the **source**, not the
`~/.claude/skills` copy.

- **`pr-review-loop`** — reads the `Code Review` **check run** rather than an issue comment; covers
  thread resolution, classification, auto-merge and screenshots. **Outstanding since the reviewer
  began arming auto-merge:** its step 7 still runs `gh pr merge --auto`. That arms as the person
  running the skill, and the reviewer never withdraws a person's arm, so the skill's arm would
  outlive every later push. The skill should read and report the reviewer's decision instead of
  making its own. Also: remove the stale claim that
  `Static Analysis` fails on every pull request because SonarCloud Automatic Analysis is enabled.
  That is out of date — PR 122 shows `Static Analysis` and `SonarCloud Code Analysis` both green,
  so the project is on CI-based analysis.
- **`code-review-triage`** — gains "red `Code Review` check" as a trigger alongside silence.

---

## See also

- [software-factory.md](software-factory.md) — check-run semantics, thread reconciliation, the
  `checks: write` rollout trap, and how to tell a down reviewer from a red one
- [static-analysis.md](static-analysis.md) — why the Sonar checks are advisory
- [deploy.md](deploy.md) — what an auto-merge to `main` sets in motion
