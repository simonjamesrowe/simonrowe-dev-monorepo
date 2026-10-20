# Ticket agent runbook

The ticket agent (`com.simonrowe.factory.ticketagent`, Temporal queue `ticket-agent`, on
`software-factory` only) works Linear tickets a person has approved. It makes the change with
Claude Code, opens a pull request, and lets GitHub Actions and the reviewer judge it. It is the
"Ticket agent" node on `/admin/software-factory` (node key `build`, module key `ticketagent`).

Design: `specs/056-factory-ticket-agent/spec.md`. It replaces the never-built
`specs/045-build-agent/`, which ran tests on a laptop; this one builds nothing at all.

## How a ticket moves

| Linear | What is happening |
| --- | --- |
| any open state, labelled `factory:agent` | approved, waiting for the intake |
| **In Progress** | claimed: the agent is making or repairing the change |
| **In Review** | CI and the reviewer passed; waiting for the merge (auto-merge armed, or a person) |
| **Done** | the pull request merged |
| **Todo**, `factory:agent` removed | handed back; `agent:stuck` added when it failed rather than declined |

Only a person approves. `logwatch`, `cvefix` and `feedback` file plain tickets and never add the
label. The console's **Send to agent** and **Start agent** add the label themselves before
starting, so Linear stays the one record of what was approved.

## One run

1. **Claim**: move to In Progress, comment with the branch (`agent/sim-107`).
2. **Attempt**: shallow clone (of `main` the first time, of the branch afterwards), then Claude
   Code with `Read`, `Glob`, `Grep`, `Edit` and `Write`. No `Bash`, no web, no MCP. The prompt
   fences the ticket and any failure text as data, and breaks every `</` inside them so they
   cannot close their own fence.
3. **Check, then push** (Java): every changed path against the denylist
   (`TicketAgentProperties.DEFAULT_DENIED_PATHS`: `.env*`, compose files, `config/**`,
   `scripts/**`, `.github/**`, Gradle and Docker build files, `frontend/package*.json`). A hit
   hands the ticket back and pushes nothing. The first attempt force-pushes (the namespace is the
   factory's); repairs push plainly, so a person's commit on the branch is never overwritten.
4. **Silencing check**: if the diff adds a log-watch mute rule, lowers a logger, disables a test
   or suppresses a warning (`SilencingDetector`), the pull request gets `no-auto-merge`. The ticket
   quotes production log text a visitor can influence, and "mute this" is exactly what a crafted
   line would ask for.
5. **Wait**: the run sleeps until CI's `workflow_run` webhook signals the commit, or 2 hours pass.
   Either way it then reads both verdicts from GitHub (`readChecks`): the CI workflow and the
   `Code Review` check. When CI is green and the review still running, it re-reads every 2
   minutes for up to 40 minutes, since no webhook this App receives reports the reviewer.
6. **Red**: the failed jobs' failed steps and log tails (and the reviewer's summary and inline
   findings, if its check was red) become the next attempt's brief. **Three attempts in all.**
7. **Green**: the reviewer has already armed auto-merge or said why not. The ticket moves to In
   Review, and the run waits up to 14 days for `pull_request closed`. Merged: Done. Closed
   unmerged: handed back. Still open after 14 days: the run ends and leaves the ticket In Review.

A hand-back is a successful run, not a failed one. Only a crash (the agent itself failing) fails
the Temporal workflow, after handing the ticket back.

## Dry runs

**Dry run ticket** (console) runs the attempt and posts the would-be diff to the ticket as a
comment. It changes no ticket state, pushes nothing and needs no label. Dry runs use their own
workflow id (`ticket-agent-dry-run-SIM-107`), so they never block a real run.

## Switches

- `FACTORY_TICKET_AGENT_ENABLED` (compose default `true`, `application.yml` default `false`):
  registers the activities and the intake schedule. On `software-factory` only;
  `TicketAgentComposeTest` fails the build if any `FACTORY_TICKET_AGENT_*` appears on `deployer`.
- The intake schedule `ticket-agent-intake` (every 5 minutes) is **created paused**. Enabling the
  module starts nothing on its own. Resume it from the console's **Resume intake** after a few dry
  runs. A reconcile at boot keeps whatever pause state the server has.
- `FACTORY_TICKET_AGENT_MAX_IN_FLIGHT` (1): tickets In Progress at once. Runs parked In Review
  waiting for a merge do not count. A claimed ticket whose run died is restarted by the next
  intake rather than blocking it.
- `FACTORY_TICKET_AGENT_MAX_ATTEMPTS` (3), `FACTORY_TICKET_AGENT_MODEL` (`sonnet`),
  `FACTORY_TICKET_AGENT_TIMEOUT` (30m per attempt; the workflow's 45m activity timeout assumes
  it), `FACTORY_TICKET_AGENT_MAX_TURNS` (60).

## One-time setup

1. **Grant the GitHub App `Actions: read`** and accept it on the installation. It is an
   *optional* permission (`GitHubCredentials.OPTIONAL_PERMISSIONS`), so without it nothing breaks:
   the repair brief names the failed job and step but has no log, and says so. GitHub serves job
   logs only with this permission, even on a public repository.
2. The App must be subscribed to `workflow_run` (it already is, for auto-deploy) and
   `pull_request`.
3. Nothing to create in Linear: `factory:agent` and `agent:stuck` are created on first use
   (`LinearGateway.addLabel`). The team needs states named **In Progress**, **In Review** and
   **Todo**.

## Terms of use

This runs on the Claude subscription, like the reviewer. Every run is started by the owner
labelling a ticket, which is the individual use the subscription covers. It shares the 5-hour and
weekly limits with interactive use and with code review; a rate-limited attempt fails, hands the
ticket back as stuck, and can be retried from the console.

## Where to look

- Console: the Ticket agent drawer lists runs from the memo each run upserts (phase, attempt,
  pull request), and offers Stop on a live one. The Linear drawer marks tickets the agent holds
  and offers Send to agent / Retry.
- Temporal: workflow `ticket-agent-SIM-107`; `progress` query; signals `checksCompleted`,
  `pullRequestClosed`, `stop`.
- A run that never wakes: check the `workflow_run` delivery reached the factory (GitHub App →
  Advanced → recent deliveries). The 2-hour fallback read will move it on regardless.
