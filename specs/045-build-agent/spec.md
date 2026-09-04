# Feature Specification: Build agent — closing the ring from a laptop

**Feature Branch**: `045-build-agent`

**Created**: 2026-09-04

**Status**: Draft

**Input**: User description: "I don't have any more capacity left on the Raspberry Pi at this point
but it could be something that I actually run on an image from my computer. I would like to see how
we can actually build stuff. Again I think we build it the same way with Claude Code, with all the
long-running tokens, in a similar image, I guess, to what we run in Software Factory. These issues
will need to be prioritised as we can probably only pick up one or 2 things at a time."

## Summary

A `build-agent` service running on the user's Mac. It takes Linear tickets the user has approved,
implements them with Claude Code, **verifies them locally by actually running the build and the
tests**, opens a pull request, drives it to green, and merges it only when
`scripts/classify-change.sh` says the change is safe to merge unattended.

It is the missing arrow in the factory's ring: everything else observes production and files
tickets, and nothing picks them up. See `specs/044-factory-flow-console/` for the diagram that makes
that gap visible.

## Why it cannot run on the Pi, and why that is the point

The original `cvefix` implementation tried to repair vulnerabilities from inside `software-factory`.
It could not: the agent had no `Bash` tool and the image carried no Gradle, Node or Docker, so CI
was the only build environment and the repair loop was a CI poll. That whole implementation
(~4,300 lines) was deleted in 040 and replaced with a ticket-filer.

Moving the agent to a machine that **can build and test** is not a capacity workaround. It is the
change that makes an autonomous build agent possible at all.

## Scope

**In scope**: `simonjamesrowe/simonrowe-dev-monorepo`. One ticket at a time (capped at two in
flight), branch, implement, verify, pull request, drive to green, conditionally merge.

**Out of scope**, deliberately:

- Any change to production infrastructure. See the denylist.
- Any Temporal involvement. See "No Temporal".
- Any inbound network route to the Mac. The Pi never talks to it.
- Multi-repo builds. `agent-setup` guidance pull requests remain the `feedback` module's job.

## Shape

A separate service, `build-agent`, run from its own image (`Dockerfile.build-agent`) via a small
`docker-compose.build-agent.yml` on the Mac.

It is **not** the `software-factory` image. Requirements are the inverse: this image carries JDK 25,
Node, Gradle and the Docker CLI, and the agent is given the `Bash` tool. It reuses the pinned-binary
pattern from `Dockerfile.software-factory` — `ARG CLAUDE_VERSION`, checksum-verified download in a
throwaway stage, root-owned `0755` in the runtime image so a run cannot swap the agent binary
underneath the next one — and the same `CLAUDE_CODE_OAUTH_TOKEN` long-running token.

### The container is not the security boundary

`./gradlew test` uses Testcontainers (`SharedMongoContainer`, `SharedKafkaContainer`), so the agent
container needs the host Docker socket. That makes it root-equivalent on the Mac.

**Every guardrail in this spec is therefore about what the agent can push and merge, not about what
it can run locally** — because locally it can run anything. Designing as though the container
contained it would be self-deception. The isolation boundary is the laptop.

## No Temporal

The agent is Linear- and GitHub-mediated. There is no workflow engine, and that is safe here for one
specific reason: **there is no in-memory state worth preserving.**

Progress lives entirely in Linear (ticket state, assignee) and GitHub (branch, pull request,
checks), both of which survive the Mac being shut. Kill the agent mid-build and restarting it
re-reads the ticket, finds its own branch, and continues. Durability comes from every step already
being externally recorded, not from a store the agent keeps.

This is also why it does not need to reach the Pi's Temporal, which binds `127.0.0.1:7233`
(`docker-compose.prod.yml:185`) and is not routed by nginx.

## Two new identities

The agent needs Linear write and GitHub write. **Neither reuses the Pi's credentials.**

### A second GitHub App: `simonrowe-build-agent`

Permissions: `contents: write` and `pull_requests: write`. Specifically **not** `checks: write`, and
**not** a ruleset bypass actor.

Three things this buys:

1. The Pi's App private key — which holds `checks: write` and mints tokens for the entire review and
   feedback machinery — never lands on a laptop.
2. Revoking the laptop is one click.
3. **Author is not reviewer.** `simonrowe-software-factory[bot]` reviewing
   `simonrowe-build-agent[bot]`'s pull request is a real review. Reusing one App would work
   mechanically — the `Code Review` gate is a check run, not an approval — but it collapses the only
   independent judgement in the loop.

Provision and verify this App **before** the first run. Per `docs/runbooks/pr-governance.md`,
`mintInstallationToken` sends an explicit `permissions` block and GitHub 422s the *whole* token
request when it over-reaches, so a permission mistake fails totally rather than partially.

### A separate Linear API key and bot user

The claim lock **is** "assigned to the agent user", so it must be a distinct identity to mean
anything. It is also what lets the console tell agent activity from the user's own.

## Intake, claim, concurrency

**Eligible**: label `factory:build`, state `Todo`.
**Ordered by**: Linear priority (Urgent → High → Medium → Low), then `createdAt` ascending.

**The label is applied by a human and only by a human.** `logwatch`, `cvefix` and `feedback` file
plain, unlabelled tickets and are not changed by this feature. The funnel is:

| State | Meaning |
| --- | --- |
| filed by a module, unlabelled | PROPOSED |
| a human adds `factory:build` | READY — **the approve edge** |
| agent claims it | BUILDING |
| pull request opened | IN REVIEW |
| merged | DONE |

**Concurrency capped at 2 in flight**, counted by querying Linear for issues assigned to the agent
user in `In Progress`. Enforced against Linear rather than in memory, so a restart cannot
double-dispatch.

**Claim** is a transition to `In Progress` plus self-assign, followed by a **re-read to confirm it
stuck**. If it did not, abandon and move on.

## One run

1. Poll Linear every 2 minutes. Take the head of the eligible queue, subject to the cap.
2. Claim. Comment on the ticket with the branch name it is about to use.
3. Fresh `git worktree` off `main`, inside a named volume — branches never land on the host
   filesystem, matching `software-factory`.
4. Claude Code, `Bash` enabled, ticket body as the brief, bounded by `CLAUDE_MAX_TURNS` and
   `CLAUDE_TIMEOUT`.
5. **Verify locally**: `./gradlew test check`, `npm test`, `./scripts/test/run-tests.sh`. Iterate on
   failures, bounded. This step is the entire reason the agent runs off the Pi.
6. Push, open the pull request, link it on the ticket, move the ticket to `In Review`.
7. Drive the three signals as `pr-review-loop` does — CI, the `Code Review` check run, SonarQube —
   treating a red `Static Analysis` as a broken scanner rather than an advisory failure. Iterate on
   findings, bounded.
8. **Merge only if** `scripts/classify-change.sh` returns `auto-merge` **and** every signal is green
   **and** every conversation is resolved. Otherwise comment on the ticket naming exactly which
   condition failed, and leave it.
9. Ticket → `Done` on merge. Otherwise it stays `In Review`.

### Expect step 8 to fire rarely

`classify-change.sh` rule 1 sends `scripts/*`, `config/*`, `.github/*`, `docker-compose*.yml`,
`build.gradle*`, `gradle/*` and `frontend/*.config.*` to `manual`. Rule 2 sends anything under
`frontend/src` to `ux-review`. Rule 4 defaults **unrecognised paths** to `manual`. Combined with the
ruleset's conversation-resolution requirement, unattended merges will be mostly backend-only and
test-only changes.

That is the feature working correctly, not underperforming.

## Guardrails

- Never force-pushes. Never commits to `main`. Never bypasses the ruleset — the repo-admin bypass
  belongs to the user, and the agent's App is not an admin, so this is structural rather than a
  promise.
- **Hard file denylist**, checked against the diff *before pushing*: `.env*`,
  `docker-compose*.yml`, `config/nginx/**`, `scripts/restart-prod.sh`, `.github/rulesets/**`. A
  ticket needing those is bounced back with a comment and the label removed.
  This overlaps `classify-change.sh` deliberately: that gates **merging**, this gates **pushing at
  all**, and a change under `config/nginx/` is how the site goes down.
- One ticket = one branch = one pull request. No stacking.
- A wall-clock budget per ticket. On expiry: comment, remove the label, move the ticket back to
  `Todo`. Without this, a confused agent burns tokens indefinitely on work it cannot do.
- Auto-merge behind its own flag, defaulted **off**, so the agent runs review-only until it has been
  watched. Same posture as `FACTORY_CVEFIX_ENABLED` shipping paused-by-default.

## How the console sees it

Entirely derived. **The Pi never talks to the Mac.**

| Node facet | Derived from |
| --- | --- |
| Ready | Linear: labelled `factory:build` and `Todo` |
| Building | Linear: `In Progress`, assigned to the agent user |
| In review | Linear `In Review` with a linked open pull request |
| Liveness | Newest Linear state change **by the agent user** |

If that timestamp is older than ~1 hour **while the Ready queue is non-empty**, the node renders
`OFFLINE` — correct when the laptop is shut, and requiring no heartbeat, no new ingress and no new
secret on the Pi.

An empty Ready queue with no activity renders `IDLE`, **not** `OFFLINE`. These are different facts,
and conflating them is exactly the `SOURCE_UNHEALTHY` versus `NO_FINDINGS` mistake that the
`logwatch` module already exists to avoid.

## Testing

The Claude Code invocation is not testable, so tests go where regressions actually hurt:

- The **denylist**, per path, including near-misses (`config/nginx/nginx-proxy.conf` denied,
  `backend/src/main/resources/config.yml` allowed).
- The **concurrency cap**, including the restart-does-not-double-dispatch case.
- The **budget expiry** path: comment posted, label removed, ticket returned to `Todo`.
- The **eligibility and ordering** query.
- The **claim-did-not-stick** abandon path.
- Linear and GitHub clients via stubbed HTTP. `software-factory` HTTP stub tests are known to flake
  on port and connection reuse — re-run isolated three times before attributing a failure.

## Risks

1. **Docker socket on the Mac.** Named above and mitigated by scoping guardrails to push and merge
   rather than pretending the container isolates the agent.
2. **A second GitHub App is real setup**, and App permission mistakes fail loudly and totally.
   Provision and verify before the first run.
3. **The label is the only gate.** One accidental bulk-label in Linear dispatches everything it
   touches. The cap of 2 limits the damage; nothing else does.
4. **Auto-merge means an agent writes code and ships it to production unattended.** Keep the flag
   off until `specs/044-factory-flow-console/` has shown several weeks of runs.

## Ordering

`044-factory-flow-console` is built first. It is the instrument used to tell whether this agent is
behaving, and it is buildable today against the seven existing modules with no dependency on this
spec.
