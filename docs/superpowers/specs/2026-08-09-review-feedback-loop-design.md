# Review Feedback Loop — Design

**Date:** 2026-08-09
**Status:** Approved for planning
**Module:** `software-factory` (new `feedback` module + changes to `codereview`)

## Problem

The software factory reviews pull requests but nothing closes the loop: review
feedback — from Simon or from the automated reviewer — evaporates when the PR
is merged. Agents repeat the same mistakes because the guidance that would
prevent them (agent-setup instructions, monorepo `CLAUDE.md`) never learns
from review outcomes.

Additionally, the reviewer today publishes one top-level issue comment, so
there are no per-finding threads: human replies can't be linked to specific
findings and there is no resolved/unresolved signal to harvest.

## Goals

- When a PR closes, harvest its review conversation (human comments weighted
  highest; automated-reviewer findings counted only when human-confirmed) and
  extract durable lessons.
- Propose guidance changes as PRs against `simonjamesrowe/agent-setup` and the
  monorepo — human-reviewed, never auto-merged.
- Log every harvest to a MongoDB collection (`review_learnings`) for a future
  UI. Log-only at this stage.
- Publish reviews as real GitHub Reviews with inline per-finding comments so
  the conversation is structured enough to harvest.
- Launch Claude with stage-appropriate models: Haiku for extraction, Sonnet
  for review and distillation.

## Non-goals

- No UI over the learnings collection (later).
- No auto-merge of proposed guidance PRs.
- No re-harvest of reopened-then-reclosed PRs.
- No recurrence-gated promotion pipeline (revisit if instruction bloat or PR
  noise becomes a problem).

## Decisions (from brainstorming)

| Question | Decision |
| --- | --- |
| Learning signal | Human comments + reviewer findings, human weighted higher |
| Trigger | `pull_request` `closed` webhook action |
| Output | Direct PRs against agent-setup and the monorepo, plus Mongo log |
| Review publishing | Switch to GitHub Reviews with inline comments |
| Models | Review: Sonnet (unchanged); harvest: Haiku; distill: Sonnet |
| Scope | All repos the App covers; config allowlist as safety valve |
| Architecture | New `feedback` module inside software-factory (modular monolith) |

## 1. Review publishing change (`codereview` module)

`GitHubGateway.publishReview` switches from a single upserted issue comment to
`POST /repos/{owner}/{repo}/pulls/{number}/reviews` with `event: COMMENT`:

- Review **body**: summary, verdict, advisory footer, and the existing hidden
  marker `<!-- temporal-code-review:{headSha}:{promptVersion} -->`.
- One **inline comment per finding**: `path`, `line`, `side: RIGHT`. Findings
  that cannot be anchored to the diff fall back into the review body as a
  bulleted list (findings are already restricted to changed files, so this is
  rare).
- `ReviewMarkdownRenderer` splits into a body renderer and a per-finding
  renderer.

The upsert behavior is dropped — GitHub Reviews are not editable the way
issue comments are. The workflow ID (`code-review-{owner}-{repo}-{pr}-{headSha}`,
`REJECT_DUPLICATE`) already guarantees exactly one review per head SHA, so
each push produces one fresh review, which is normal reviewer behavior.

## 2. Trigger

- `GitHubWebhookController` action allowlist gains `closed`. A `closed` action
  routes to the feedback workflow service (not the review service), carrying
  owner, repo, PR number, merged flag, and installation id.
- **Loop guard:** PRs labeled `agent-feedback` (the label the factory puts on
  its own proposal PRs) are skipped at the webhook, so the factory never
  learns from its own feedback PRs.
- Workflow ID `review-feedback-{owner}-{repo}-{pr}` with `REJECT_DUPLICATE`.
  A reopened-then-reclosed PR deliberately does not re-harvest (idempotency
  over completeness).

## 3. `feedback` module

New package `com.simonrowe.factory.feedback` beside `codereview`, same JVM and
worker, new Temporal task queue `review-feedback` (per-capability queues per
the factory roadmap). Package layout mirrors `codereview`: `workflow`,
`agent`, `github`, `config`, `domain`, plus `persistence`.

Shared plumbing: the Claude CLI launcher (argv construction, env allowlist
stripping, timeout handling, JSON-schema output validation) is extracted from
`ClaudeCliReviewEngine` into a shared `ClaudeCliRunner` used by both modules.
`HarvestEngine` and `DistillEngine` interfaces mirror the `ReviewEngine` seam.

### Workflow: `ReviewFeedbackWorkflow`

1. **fetchConversation** — network activity, retryable (3 attempts, backoff
   as per existing network stub). One GraphQL query per PR: reviews (author,
   state, body), review threads (`isResolved`, comments with author, body,
   `path`, `line`, `diffHunk`), issue comments, merge status, PR author. No
   repo clone — the `diffHunk` on each thread carries enough code context.
2. **Early exit** — if there is no human activity beyond the bot's own review
   (no human comments, no replies to bot threads), record a `NO_SIGNAL`
   document to Mongo and complete.
3. **harvestLessons** — agent activity, **Haiku**, effort low, maxAttempts=1
   (consistent with the existing cost-bearing agent stub). Input: transcript
   JSON written to a temp workspace. Output validated against
   `lessons-schema.json`:

   ```json
   {
     "lessons": [{
       "title": "...",
       "guidance": "...",
       "scope": "org-wide | repo-specific",
       "evidence": ["<comment URL>", "..."],
       "source": "human | reviewer | both",
       "confidence": "high | medium | low"
     }]
   }
   ```

   The prompt weights human comments highest; automated-reviewer findings
   count only when human-confirmed (thread resolved / fix committed / human
   agreement). Lessons must be durable guidance, not PR-specific nitpicks.
4. **logLearnings** — network activity, retryable. Writes the learning record
   to Mongo. Always runs, even with zero lessons (coverage evidence).
5. **distillAndPropose** — agent activity, **Sonnet**, maxAttempts=1, only
   when at least one lesson was harvested. See §5.

A `FeedbackProgress` `@QueryMethod` mirrors `ReviewProgress`:
`ACCEPTED → FETCHING → HARVESTING → LOGGING → DISTILLING → PROPOSING →
COMPLETED | NO_SIGNAL | FAILED`.

## 4. Mongo logging

- software-factory gains `spring-data-mongodb`, pointing at the existing
  Mongo container but its **own database** `software_factory`, collection
  `review_learnings`.
- Document shape: `{owner, repo, prNumber, prTitle, prUrl, merged,
  workflowId, harvestedAt, promptVersion, lessons[], distillation:
  {status: SKIPPED_NO_LESSONS | PROPOSED | NO_CHANGE | FAILED | DRY_RUN,
  prUrls[]}}`.
- Unique index on `{owner, repo, prNumber}`, created programmatically at
  startup. Mongock remains backend-owned; this database belongs to the
  factory. (Auto-index-creation is a backend concern; the factory ensures its
  own indexes in code.)

## 5. Distill & propose

Deterministic Java owns everything with side effects; the agent only edits
files in a throwaway workspace.

1. Java clones the target repo(s) at the default branch (blobless, reusing
   `GitWorkspaceFactory` patterns and installation-token Basic auth).
2. Claude (**Sonnet**, effort medium) runs with `Read`, `Glob`, `Grep`,
   `Edit`, `Write` scoped to the workspace — **no Bash, no git, no network,
   no MCP**. Prompt: the harvested lessons + instructions to integrate them
   minimally, dedupe against existing guidance, and keep instruction text
   terse. Output: a schema-validated summary (`{prTitle, prBody, changes[]}`)
   or an explicit no-change with reason.
3. Java then: diffs the workspace → validates every touched file against a
   hard allowlist → commits as `simonrowe-code-reviewer[bot]` → pushes branch
   `feedback/{source-repo}-pr-{number}` → opens a PR labeled `agent-feedback`.
   Empty diff → record `NO_CHANGE`.

File allowlist (deterministic guard, not prompt-enforced):

- agent-setup: `components/instructions/global.md`,
  `components/instructions/monorepo-additions.md`, `components/skills/**`
- monorepo: `CLAUDE.md`

Routing honors the existing convention that canonical guidance text lives in
agent-setup:

- **Org-wide lesson** → one PR against agent-setup (`global.md` or the
  relevant skill).
- **Monorepo-specific lesson** → two PRs kept in sync: monorepo `CLAUDE.md`
  (Manual additions section) + agent-setup
  `components/instructions/monorepo-additions.md`.

## 6. Configuration

`factory.feedback.*` (`@ConfigurationProperties`), env prefix
`FACTORY_FEEDBACK_*`:

| Property | Default | Notes |
| --- | --- | --- |
| `enabled` | `false` | Master switch; compose flips to `true` at rollout |
| `repos` | empty (= all) | Allowlist safety valve, `owner/name` slugs |
| `skip-label` | `agent-feedback` | Loop-guard label |
| `harvest.model` | `haiku` | + `effort: low`, `max-turns`, `timeout: 5m` |
| `distill.model` | `sonnet` | + `effort: medium`, `max-turns`, `timeout: 15m` |
| `agent-setup-repo` | `simonjamesrowe/agent-setup` | Distillation target |

Plus `spring.data.mongodb.uri` (from `MONGODB_URI`-style env, database
`software_factory`). Review-stage model config (`CLAUDE_MODEL` → `sonnet`)
is unchanged. The service keeps its no-`env_file` rule — every variable is
passed explicitly in compose.

## 7. Error handling

- Network activities: existing retry profile (3 attempts, exponential
  backoff). Agent activities: single attempt, heartbeat, generous
  start-to-close (harvest 5m, distill 15m+).
- Distill failure leaves the Mongo record holding the harvested lessons with
  `distillation.status = FAILED` — signal is never lost; the run can be
  re-driven manually.
- Stale/edge inputs (PR deleted, threads gone) surface as non-retryable
  application failures, mirroring `STALE_PULL_REQUEST`.
- Manual trigger: token-guarded `POST /api/feedback` (same `X-Factory-Token`
  scheme as `/api/reviews`, unrouted by nginx) with `dryRun: true` → harvest
  + Mongo log (`distillation.status = DRY_RUN`), no clone, no PRs.

## 8. Ops & rollout

GitHub App (`simonrowe-code-reviewer`):

- Permission bump: `contents: read` → `read & write` (branch pushes).
  `pull_requests: write` already covers PR creation and inline reviews.
- Install the App on `simonjamesrowe/agent-setup`.
- Event subscriptions unchanged (Pull request already covers `closed`).

Accepted risk: the internet-facing process now holds a write-capable
credential. Existing mitigations apply (single exact-match nginx route,
token-guarded internal API, allowlisted child env) plus: the agent never
holds git credentials (Java pushes), and pushed branches are constrained by
the file allowlist and land only as human-reviewed PRs.

Rollout order:

1. Ship the inline-reviews change (§1) — the harvester needs threads to exist.
2. Ship the feedback module with `FACTORY_FEEDBACK_ENABLED=false`.
3. Bump App permissions, install on agent-setup, dry-run via `POST
   /api/feedback` against a real closed PR, verify the Mongo record, then
   enable.

Runbook: new section in `docs/runbooks/software-factory.md` (verification
steps, failure modes, how to re-drive a failed distillation).

## 9. Testing

- **Unit:** webhook routing for `closed` + skip-label, GraphQL→domain
  mapping, lessons-schema validation, inline-comment anchoring + body
  fallback, file-allowlist guard, PR title/body rendering, per-stage model
  argv construction in `ClaudeCliRunner`.
- **Workflow:** Temporal `TestWorkflowEnvironment` with fake
  `HarvestEngine`/`DistillEngine` (same pattern as `ReviewEngine`): happy
  path, no-signal early exit, harvest failure, distill failure leaving the
  Mongo record intact.
- **Persistence:** learning repository against Testcontainers Mongo,
  consistent with backend conventions.
- **Manual:** dry-run endpoint against a real closed PR in prod before
  enabling.
