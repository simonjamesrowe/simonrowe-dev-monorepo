# Quickstart: Pull Request Governance (038)

How to build, test and roll this out. The rollout order is **load-bearing** — two of its steps
will brick the repository if taken early.

---

## Build and test locally

```bash
# software-factory: fingerprint, reconcile, check-run mapping
cd software-factory && ../gradlew check

# the classifier
./scripts/test/run-tests.sh            # auto-discovers test-classify-change.sh

# just the classifier, iterating
bash scripts/test/test-classify-change.sh
```

Try the classifier by hand — it reads paths on stdin, so it needs no repository state:

```bash
printf 'backend/src/main/java/A.java\n' | scripts/classify-change.sh
# category=auto-merge
# ux_affecting=false

printf 'backend/src/main/java/A.java\ndocker-compose.prod.yml\n' | scripts/classify-change.sh
# category=manual        <- rule 1 outranks rule 3
# ux_affecting=false

printf 'newtoplevel/thing.txt\n' | scripts/classify-change.sh
# category=manual        <- the unrecognised-path default
```

Against a real branch:

```bash
scripts/classify-change.sh origin/main
```

---

## Rollout order

Steps 1, 3, 5 and 6 are **operator actions no pull request can perform**. Committing
`.github/rulesets/main.json` does not apply it; step 5 does.

### 1. Grant the App `checks: write` — BEFORE any deploy

GitHub App settings → Permissions → **Checks: Read and write**. Then accept the installation
permission update on the repository (GitHub emails a link; it also appears under the
installation's settings).

> **This step cannot be skipped or reordered.** `GitHubCredentials.mintInstallationToken` sends an
> explicit `permissions` block, and GitHub **422s the entire token request** when it asks for more
> than the installation was granted. That method converts any 4xx into a non-retryable failure. So
> deploying the image that requests `checks: write` before the grant lands breaks **every** token
> mint — taking down code review *and* the feedback loop. Identical in shape to the
> `contents: write` rollout already recorded in `docs/runbooks/software-factory.md`.

Verify the grant took:

```bash
gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/installation \
  --jq '.permissions'      # expect "checks": "write"
```

### 2. Merge this pull request

The ruleset is **not** applied yet. This PR classifies as `manual` (it touches `.github/**` and
`scripts/**`), so it is merged by hand.

### 3. Deploy — both containers

```bash
docker compose -f docker-compose.prod.yml up -d --no-deps software-factory deployer
```

`deployer` runs the same `FACTORY_IMAGE` and **never recreates itself**, so omitting it leaves the
deployer on the old image indefinitely — the same shape as the bug that left `software-factory`
stale for months.

### 4. Confirm on a real pull request

Open any pull request and check:

- a **`Code Review`** check run appears on the head commit and completes `success` or `failure`;
- a finding you then fix is **replied to and resolved** on the next push, rather than deleted;
- a finding you do not fix keeps its original thread, posting time and any replies.

```bash
gh pr checks <n>                        # 'Code Review' present?
gh api graphql -f query='
  query { repository(owner:"simonjamesrowe", name:"simonrowe-dev-monorepo") {
    pullRequest(number: <n>) { reviewThreads(first:20) { nodes { isResolved } } } } }'
```

### 5. Only then, apply the ruleset

```bash
gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets            # find the id, or none yet
gh api --method POST /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets \
  --input .github/rulesets/main.json                                    # first time
# thereafter:
gh api --method PUT /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets/<id> \
  --input .github/rulesets/main.json
```

> **Step 5 following step 4 is not tidiness.** Applying the ruleset while nothing publishes a
> `Code Review` check makes that required check permanently absent, blocking **every** pull request
> — including the one that would fix it. With `bypass_actors: []`, recovery means hand-editing the
> ruleset in the GitHub UI.

### 6. Repository settings, then the skills

```bash
gh api --method PATCH /repos/simonjamesrowe/simonrowe-dev-monorepo \
  -F allow_auto_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false
```

Then update `simonjamesrowe/agent-setup` → `components/skills/` (the **source**, not the
`~/.claude/skills` copy) — a separate repository, so this is follow-up work:

- `pr-review-loop` — read the check run instead of an issue comment; add thread resolution,
  classification, auto-merge and screenshots; **remove the stale claim** that `Static Analysis`
  fails on every PR (the project is on CI-based SonarCloud analysis, and PR 122 shows both green).
- `code-review-triage` — add "red `Code Review` check" as a trigger alongside silence.

---

## Emergency bypass

There are no bypass actors, by design. To merge past a broken gate:

1. Prefer fixing the signal. A red `Code Review` usually means a real finding; an absent one means
   `software-factory` is down — check for a live poller on the `code-review` task queue, not just
   the container healthcheck (`docs/runbooks/software-factory.md`).
2. If the reviewer genuinely cannot be restored, edit the ruleset in the GitHub UI to drop
   `Code Review` from the required contexts, merge, then restore it from
   `.github/rulesets/main.json`. The edit is visible in the repository's rule-insights log.

Drift check between what is committed and what is applied:

```bash
diff <(gh api /repos/simonjamesrowe/simonrowe-dev-monorepo/rulesets/<id> \
        --jq '{name,target,enforcement,conditions,rules}') \
     <(jq '{name,target,enforcement,conditions,rules}' .github/rulesets/main.json)
```

---

## Screenshots for a UX pull request

Only when `classify-change.sh` says `ux_affecting=true`.

1. Bring up the local stack (`local-env` skill), ideally over restored prod data
   (`prod-data-restore`) so the pages show real content rather than empty states.
2. Per affected route, via Playwright MCP: `browser_resize` → `browser_navigate` →
   `browser_take_screenshot`, at **1440×900** and **390×844**; add dark mode when the diff touches
   theming.
3. Push from a **throwaway `git worktree`** onto the orphan `pr-screenshots` branch as a single
   amended commit, force-pushed, at `pr-<n>/<route-slug>-<viewport>[-dark].png`. The feature branch
   and working tree stay untouched.
4. Post/edit one comment marked `<!-- pr-screenshots -->` embedding
   `raw.githubusercontent.com/.../pr-screenshots/pr-<n>/<file>` URLs, listing the routes captured
   so an omission is visible.
5. After merge, delete `pr-<n>/` and force-push again. Merged PRs then have broken image links —
   accepted; live PRs always work.

---

## What to expect afterwards

- Auto-merge fires **less often than "backend-only ⇒ auto-merge" implies**. Conversation resolution
  is required, so *any* `SUGGESTION` blocks the merge until fixed or declined with a stated reason.
  That is intended: the goal is that findings get dealt with, not bypassed.
- The steady state needs no agent action at the merge step: fix → push → re-review → finding
  absent → bot replies and resolves → conversation resolution satisfied → checks green → GitHub
  merges.
- `--auto` is the merge mechanism, not permission to stop watching. The agent still waits on every
  signal and reports.
- On the **first** review after deploy, threads carrying the old bare
  `<!-- temporal-code-review-finding -->` marker match no fingerprint, so they are replied to and
  resolved. That is correct — they are pre-change artefacts — and nothing is destroyed.
