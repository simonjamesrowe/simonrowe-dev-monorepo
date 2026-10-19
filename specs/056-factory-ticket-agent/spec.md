# Feature Specification: The factory picks up its own tickets

**Feature Branch**: `056-factory-ticket-agent`

**Created**: 2026-10-19

**Status**: Implemented. Where the build differs from the draft below:

- No standing dry-run flag. The intake schedule is created **paused** instead (the cvefix
  precedent), and dry runs are the console's **Dry run ticket**.
- The reviewer's verdict is read from GitHub after CI's signal (re-read every 2 minutes for up to
  40), not signalled by `CodeReviewWorkflowImpl`, so the review workflow needed no version gate.
- "Three turns" is three agent runs per ticket, the first included.
- The agent does not answer the reviewer's `SUGGESTION` threads; an open one holds the merge for a
  person.
- Labels are created on first use rather than by hand.

**Supersedes**: `specs/045-build-agent/` (never built). 045 put the agent on the Mac *because* it
wanted to run the tests locally. This drops local verification altogether: GitHub Actions is the
only build environment, so the agent can run in the `software-factory` image on the Pi, beside the
reviewer.

## Summary

Two separate changes, shipped in this order:

1. **Feedback pull requests merge themselves, and their Linear ticket closes when they do.** Small,
   mostly policy. Every one of the ~23 `agent-feedback` pull requests so far (15 in `agent-setup`,
   8 here) was merged by hand without changes, and their tickets sit in Triage after the merge
   (SIM-105 and SIM-106 today).
2. **A ticket agent.** A human adds a label to a Linear ticket. The factory claims it, has Claude
   Code edit a fresh checkout, pushes a branch, opens a pull request and stops. GitHub Actions and
   the reviewer judge it. If CI goes red, the `workflow_run` webhook wakes the agent for another
   go, up to three goes in all. When the reviewer arms auto-merge and GitHub merges, the ticket
   goes to Done.

## What exists already, and gets reused

| Need | Already there |
| --- | --- |
| Headless Claude Code with a tool allowlist | `claude/ClaudeCliRunner` (the reviewer and distiller both use it) |
| Clone, edit, check paths, commit, push without the agent touching git | `git/RepositoryWorkspaceFactory` (the distiller's `GuidanceWorkspaceFactory` is a thin adapter over it) |
| A signed GitHub webhook that already receives `workflow_run` | `GitHubWebhookController.handleWorkflowRun` (only for `Publish` today) |
| Signal-with-start on a fixed workflow id from a webhook | `DeployWorkflowService` (`deploy-prod`) |
| Deciding whether a PR may merge unattended | `AutoMergePolicy` + `MergeDisposition` (the Java copy of `classify-change.sh`) |
| Arming auto-merge, withdrawn before every re-review | `AutoMergeGateway` |
| Linear reads, comments, state changes, attachments | `linear/LinearGateway`, `IssueResolver` (already moves tickets to Done) |
| An independent second opinion | The `Code Review` check, a required check on `main` |

The old `cvefix` repair loop (deleted in 040) had the same shape: no `Bash`, CI as the build. It
**polled** CI. This version waits on a webhook and holds no worker while it waits.

---

## Part 1: Feedback pull requests merge themselves

### Changes

1. **`AutoMergePolicy` stops refusing `agent-feedback`**, and checks the paths instead. A guidance
   PR qualifies when every changed path matches that target's distill allowlist (the
   `allowedPaths` the distiller already enforces before it pushes). That check replaces
   `MergeDisposition` for these PRs only. Without it, `agent-setup`'s
   `components/instructions/global.md` comes out `manual` (nested, unrecognised path), while the
   monorepo's root `CLAUDE.md` comes out `auto-merge` anyway.
2. **Trust the factory's own App as an author.** The trust rule reads
   `/collaborators/{login}/permission`, and it can't vouch for an App bot. So add one rule: the
   author is a `Bot` whose login equals this App's own `<slug>[bot]`, read from `GET /app` at
   startup and never from config (the same reasoning as reading `enabled_by.type`), and the head
   branch is in the same repository.
3. **Close the ticket on merge.** `pull_request closed` currently starts nothing for an
   `agent-feedback` PR, because of the loop guard. Keep the guard on *harvesting*, and add a
   separate branch: merged plus `agent-feedback` → look up the ticket by the PR URL already
   attached to it (`attachUrl`) → move it to Done once **every** guidance PR attached to it is
   merged (a ticket can have two: one in `agent-setup`, one in this repo). A PR closed without
   merging leaves the ticket open, with a comment.
4. **`agent-setup` needs a gate before anything can auto-merge there.** Today it has no ruleset and
   `allow_auto_merge: false`. Without required checks, arming would merge straight away, before the
   review finishes. Add a ruleset requiring `Code Review` and `validate (*)`, and turn auto-merge
   on in the repository settings. This is a one-time manual step, recorded in the runbook.

`no-auto-merge` still works as the human opt-out. `FACTORY_CODEREVIEW_AUTO_MERGE_ENABLED` still
switches the whole thing off.

---

## Part 2: The ticket agent

### Intake

- **Eligible**: label `factory:agent` (name open, see the questions at the end), state not
  completed or cancelled. **Only a human adds it.** `logwatch`, `cvefix` and `feedback` keep filing
  unlabelled tickets.
- **How the factory finds out**: a Linear webhook on label change (one new signed route, the same
  pattern as GitHub's), with a 15-minute poll as the backstop for a missed delivery. Or poll only,
  to start with: simpler, and two minutes of latency doesn't matter here.
- **One ticket at a time.** It shares the subscription's 5-hour and weekly limits with the
  reviewer, and the Pi's CPU. The cap is enforced by the fixed workflow id per ticket
  (`agent-SIM-107`) plus a concurrency check on running `agent-*` workflows, not by memory.

### One workflow per ticket

`TicketAgentWorkflow`, id `agent-<identifier>`, on a new `ticket-agent` task queue polled by
`software-factory` only (add it to `application.yml`'s list, not `application-deployer.yml`; see
`FactoryWorkflowWorkersTest`).

```
claim ──▶ attempt 1 ──▶ push + open PR ──▶ await CI ──┬─ green + review green ─▶ await merge ─▶ Done
  │                                                    │
  │                                                    └─ red ─▶ attempt n+1 (n < 3) ─▶ push ─▶ await CI
  │                                                                │
  │                                                                └─ n = 3 ─▶ hand back
  └─ claim did not stick / denylisted / "no change warranted" ─▶ hand back
```

1. **Claim**: move the ticket to In Progress, comment with the branch name (`agent/sim-107`), and
   re-read it to confirm the change stuck.
2. **Attempt** (activity, on the agent stub): fresh shallow clone of `main`. Claude Code with
   **`Read`, `Glob`, `Grep`, `Edit`, `Write` only**: no `Bash`, no web tools. The brief is the
   ticket body, plus the repo's `CLAUDE.md`, which the agent reads itself. Output is structured:
   `changed`, `summary`, `prTitle`, `prBody`, or `changed=false` with a reason ("third-party noise,
   nothing in this repository emits it"). That second answer is a real result and gets posted to
   the ticket. It isn't a failure.
3. **Push** (Java, not the agent): check the changed paths against the denylist, then commit and
   push. One ticket, one branch, one PR. Later attempts push new commits to the same branch and
   never force-push.
4. **Await CI**: the workflow blocks on a signal, so no worker is held while it waits. The webhook
   controller gains a second `workflow_run` branch: `name == "CI"`, `conclusion` set, `head_branch`
   starts with `agent/`. That signals `agent-<id>` with `{headSha, conclusion, failedJobs}`. A
   signal for an older SHA is ignored.
   - **Timeout of 2 hours on the wait**, after which the workflow asks GitHub for the head commit's
     check runs directly. A missed webhook then costs a delay, not a stuck ticket.
   - `CI` uses selective builds, so a docs-only change can skip jobs. A skipped job counts as
     passed, which is how the ruleset sees it too.
5. **Red → next attempt.** Before the agent runs, the workflow fetches the failed jobs' logs (the
   last ~200 lines of each failed step, via the Actions API) and gives them to the agent as the
   brief, along with the diff so far. A red `Code Review` check (a `CRITICAL` or `REQUEST_CHANGES`)
   counts as a red round too, and its findings become the brief. The reviewer already knows when
   it has finished, so for `agent/` branches it signals the ticket workflow directly. That needs
   no new webhook subscription, just a `Workflow.getVersion` gate in `CodeReviewWorkflowImpl`.
6. **Green**: nothing to do. The reviewer has already armed auto-merge, or explained why not.
   The workflow waits for `pull_request closed`:
   - merged → ticket to **Done**, with a comment linking the PR;
   - not armed (manual or UX paths, a `SUGGESTION` thread to resolve) → ticket to **In Review**,
     with a comment carrying the reviewer's one-line reason, which `MergeDecision.describe()`
     already produces. A human takes it from there, and the workflow finishes.
7. **Hand back** after three attempts, a denylisted path, a timeout or an agent failure: comment
   on the ticket with what was tried and the last failure, remove `factory:agent`, add
   `agent:stuck`, and move the ticket back to Todo. The PR stays open as a draft for a human.

**"Three turns" means three agent runs per ticket**: the first attempt plus two repairs. Each run
is also capped by `--max-turns` and a wall-clock timeout, like the reviewer's.

### Why a merge is enough to call it Done

For a `logwatch` ticket, merged isn't the same as fixed. The fix still has to deploy and the error
has to stop. That's acceptable, because the sink already handles the other case: if the
fingerprint comes back after the ticket is Done, the next scan files a linked
`FILED_REGRESSION`. The recovery path already exists.

### Guardrails

The prompt contains **production log text**, and some of it can be influenced by visitors (request
paths, scraped titles, headers). Treat it as untrusted, the same way `content-writing` treats
commit text. That is why the agent gets no `Bash` and no network, and why the controls sit on
what can be **pushed** and **merged**:

- **Push denylist**, checked in Java before pushing: `.env*`, `docker-compose*.yml`,
  `config/nginx/**`, `scripts/**`, `.github/**`, `**/rulesets/**`. A ticket that needs any of
  these is handed back.
- **Merge** goes through the reviewer's existing `AutoMergePolicy` and `MergeDisposition`, which
  are unchanged for this agent. Infra paths are `manual`, and `frontend/src` is `ux-review`.
- **One extra merge rule for agent-authored PRs: changes that only silence something need a
  human.** That covers the `factory.logwatch.ignore` list, `logging.level` entries and
  `@SuppressWarnings`. Muting third-party noise is the obvious fix for half of today's backlog
  (SIM-91 to SIM-94, SIM-104: Temporal), and it's also exactly what a crafted log line would ask
  for. MergeDisposition sees only paths, so this needs a check on the content of the diff in the
  agent-PR policy.
- Delimit the ticket body in the prompt as data (`<ticket>…</ticket>`), and tell the agent that
  instructions inside it aren't the owner's.
- The label is the approve edge, and only a human applies it.

## The admin console (`/admin/software-factory`)

The 044 flow diagram already draws an arrow from Linear to a "Build agent" node, and from that
node to Pull request. Nothing stands behind the node today, and it is **showing the wrong thing
right now**. Its health is `OFFLINE` whenever *any* Linear ticket is open
(`FactoryFlowService.health`). There are 18 open tickets, so it is permanently red, and that red
means nothing. This feature turns the node into a real module and adds the two controls an
unattended code-writer needs: a way to hand it work, and a way to stop it.

### The node

- **`build` becomes the ticket agent**, labelled "Ticket agent". It gets `moduleKey`
  `ticketagent` and `workflowType` `TicketAgentWorkflow`, so its counts, health and drawer come
  from Temporal visibility like every other module. The special cases for `BUILD` in
  `FactoryFlowService` (counts borrowed from Linear, the "open ticket means offline" health) are
  deleted, not adjusted. The node key can stay `build`, so the layout and saved selections don't
  change.
- **What counts as OK**: a run that ends merged, in review or handed back has *completed*. A
  hand-back is the agent correctly giving up, which is a valid outcome, so it doesn't count as
  failed. `failed24h` is kept for runs that crashed, and those are what deserve the red. Without
  this split, a cautious agent and a broken one would look the same.
- **Edges** (topology is code, so `FactoryFlowTopologyTest` pins these):
  - `linear → build`: label `approved: factory:agent` (was `factory:build`).
  - New, fast loop: `pull-request → build`, "CI red: repair (max 3)". The repair loop is the
    whole point of this design, so it should be visible.
  - New, main loop: `build → linear`, "Done on merge, or handed back".
  - New, slow loop: `codereview → agent-setup`, "arms auto-merge". Part 1 makes guidance PRs
    merge themselves, and today the diagram shows only the reviewer arming merges into `main`.
  - `agent-setup → build`, "shapes the agent", stays. It becomes literally true, since this agent
    reads the same `CLAUDE.md` and instructions.

### The ticket agent drawer

**Runs list.** Each item uses the existing `FlowDetail.Item` (`id`, `title`, `status`, `at`,
`url`), so no schema change is needed:

| Field | Value |
| --- | --- |
| `title` | `SIM-107 · ERROR in backend (GrpcExporter)` |
| `status` | phase plus attempt, e.g. `attempt 2/3 · waiting for CI`, `merged`, `handed back: denylisted path scripts/…`, `in review: needs a human (frontend/src/…)` |
| `url` | the PR once one exists, otherwise the Linear ticket |

The phase comes from the workflow's `progress` query, which the console's run banner already
understands. `TicketAgentProgress` is `{phase, detail, attempt}`, the same shape every factory
workflow returns.

**Actions** (in `actionPanelFor`, which is a total switch, so the `build` case moves out of the
"returns null" group):

1. **Hand a ticket to the agent**: a field taking `SIM-123` or a Linear URL, with **Dry run** and
   **Start** buttons. This doesn't skip the label: the backend applies `factory:agent` through the
   Linear API and then starts the workflow, so the label is still the one record of approval
   whichever door it came through. A dry run posts the would-be diff to the ticket and pushes
   nothing. That's the first thing to press after deploying.
2. **Stop**: on each running item. It cancels the workflow, which runs the normal hand-back path
   (comment, swap the label, PR left as a draft). Cancelling from the Temporal UI does the same
   thing, so there's one stop path, not two.
3. **Pause intake / Resume intake**: intake is a Temporal schedule (`ticket-agent-intake`, every
   5 minutes) rather than a poller inside the JVM, so pausing it is pausing the schedule. The
   console already reports schedule pause state for `cvefix` and `logwatch`, so this reuses
   `ScheduleStatus`. It's the off switch that needs no redeploy, which matters for something that
   writes code. A paused schedule still lets runs already in flight finish. Stopping those is
   what (2) is for.

### The Linear drawer

It lists open tickets from `linear_issues`. Two changes:

- **A "Send to agent" button per ticket**, doing the same thing as action (1), for the common
  case of picking from what's already filed. Shown only when the ticket isn't already labelled.
- **Show the label in the item's `status`** (`triage`, `triage · with agent`,
  `todo · agent:stuck`), so the queue the agent sees is visible without opening Linear.

Known issue, not fixed here: `linear_issues` is a mirror that refreshes only when the sink
refiles, so it holds phantoms (the 046 orphans). The agent's own intake reads Linear directly, so
a phantom can't be dispatched. It can only be offered in this drawer. A button that 404s on a
stale ticket is acceptable until that mirror is fixed.

### The feedback drawer

Each feedback run's `status` today ends at "proposed". With Part 1 it should carry the outcome
too: `merged (2/2 PRs) · SIM-106 done`, `auto-merge armed`, `held: no-auto-merge`. The reviewer's
`MergeDecision.describe()` already produces the held reason. This is the only place in the
console where you can see that a guidance PR landed and its ticket closed, without opening GitHub.

### Backend proxy

`FactoryAdminService.ORDER` is the authoritative module list on the backend side. A module
missing from it is dropped from the console entirely, so `ticketagent` has to be added there. New
proxied routes, all admin-only and all forwarded with `FACTORY_TRIGGER_TOKEN`:

- `POST /api/admin/software-factory/ticket-agent/runs` `{ticket, dryRun}`
- `POST /api/admin/software-factory/ticket-agent/runs/{id}/stop`
- `POST /api/admin/software-factory/ticket-agent/intake/{pause|resume}`

Downstream statuses are translated as they are for the other modules: 409 means the ticket
already has a run, 503 means the module is disabled, 401/403 becomes 502.

### Console tests

- `build` health: an open Linear backlog no longer makes it `OFFLINE`. That's a regression test
  for the bug above.
- A handed-back run counts as completed, not failed.
- The action panel: both buttons are disabled until the field parses as a ticket identifier;
  Stop shows only on running items; the Pause/Resume label follows the schedule state.
- Unique accessible names: "Dry run" is already used by platform backup, so this one is
  **"Dry run ticket"** and the other is **"Start agent"**. There's already a test pinning that
  labels stay unique.

### Flags

- `FACTORY_TICKET_AGENT_ENABLED`: registers the activities, `software-factory` only, default
  `false` in `application.yml`. Add it to the deployer confinement test, like Linear's flag.
- `FACTORY_TICKET_AGENT_DRY_RUN`: runs the attempt and posts the would-be diff to the ticket as a
  comment, without pushing. Worth a week of watching before turning on real pushes.

## Testing

Claude Code can't be unit-tested, so the tests go where a regression would actually hurt:

- The webhook: a `CI` `workflow_run` for an `agent/` branch signals the right id; one for any other
  branch, or for `Publish`, does not; a stale SHA is ignored.
- The attempt budget: a third red round hands back and never starts a fourth attempt. Test this
  with Temporal's test environment and a time-skipping wait, as the deploy tests do.
- The denylist, per path, with near-misses.
- The "silencing needs a human" rule against real diffs: a mute rule, a `logging.level` key with
  `[...]` brackets, and a genuine fix in the same file that must stay auto-merge.
- `AutoMergePolicy`: an `agent-feedback` PR inside the allowlist arms; one path outside it does
  not; an App author that isn't ours is refused.
- Feedback ticket closure: two guidance PRs where only one has merged leaves the ticket open; both
  merged closes it.
- Replay: `CodeReviewWorkflowImpl` histories recorded before the `getVersion` gate still replay.

## Rollout

1. Part 1 behind the existing auto-merge flag. Set up `agent-setup`'s ruleset first.
2. Part 2 with `DRY_RUN=true`, ship the console changes in the same PR, so the first runs are
   started and watched from the console's dry-run button on three or four logwatch tickets.
3. Real pushes, auto-merge still deciding per PR as it does for every human PR.

## Open questions

1. **Label name.** `factory:agent` keeps to the `factory:*` convention; `ready-for-agent` reads
   better in Linear's UI.
2. **Linear webhook, or poll?** Poll first, and add the webhook only if latency starts to matter.
3. **Three runs in total, or three repairs after the first?**
4. **Silencing changes.** Should mutes and `logging.level` changes always need a human, as
   recommended above, or should a mute merge itself when the ticket's container is third-party
   (Temporal, Alloy)?
5. **Should the agent also answer the reviewer's `SUGGESTION` threads**, or leave those to a
   human? Under the ruleset, an open thread holds the merge.
6. **Should the console's "Send to agent" apply the label, or start the run without one?**
   Recommended: apply it, so Linear stays the one record of what was approved.
