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
   `scripts/**`, `.github/**`, `gradlew`, `gradlew.bat`, `gradle/wrapper/**` and Dockerfiles). A hit
   hands the ticket back and pushes nothing. The first attempt force-pushes (the namespace is the
   factory's); repairs push plainly, so a person's commit on the branch is never overwritten.
   The pull request title, which becomes the squash commit on `main`, is made conventional in
   Java (`ConventionalTitle`): a title with no type gets `fix:`. The agent is asked for one, but
   on 2026-10-24 it titled #287 without.
4. **Silencing check**: if the diff adds a log-watch mute rule, lowers a logger, disables a test
   or suppresses a warning (`SilencingDetector`), the pull request gets `no-auto-merge`. The ticket
   quotes production log text a visitor can influence, and "mute this" is exactly what a crafted
   line would ask for. Build/dependency edits (`gradle/**`, root and module `build.gradle.kts`,
   `settings.gradle.kts`, `frontend/package*.json`) also always get `no-auto-merge`, with the reason
   added to the PR body. This checks the full PR, including rename sources, on every attempt so a
   later repair cannot remove the label while an earlier dependency edit remains.
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

## Dependency edits and the accepted CI tradeoff

The agent may edit dependency versions and build scripts; it still cannot run Gradle, npm, tests
or downloads. GitHub Actions is the build machine. Every build/dependency change needs a person's
merge, including `backend/build.gradle.kts`, which the path classifier otherwise permits, with
one exception: **a vulnerability repair that only moves versions merges itself.**

On a ticket labelled `factory:cvefix` (the scan's repair tickets, `cvefix.md` "Repair tickets"),
`markMergePolicy` reads the full pull request's patches. When every dependency file is a pin file
and `DependencyPins` finds nothing but version changes, it leaves `no-auto-merge` off and notes
why in the PR body. The reviewer re-checks the same diff before it arms (`pr-governance.md`, rule
8), so the agent's answer only stops it vetoing a merge the reviewer's rules allow. Anything more
than a version move, any silencing, and any `package.json` change still gets `no-auto-merge`.
The npm lockfile is judged by `NpmLockPins` (see "npm repairs" below). The activity
is new, behind `Workflow.getVersion("vulnerability-merge-policy")`; runs started before it keep
calling `markSilencing`.

**Accepted tradeoff:** CI executes an edited build script on the PR branch with the PR's repository
secrets (`SONAR_TOKEN`) before a person reads it. For an ordinary ticket this is accepted because
a person approves the ticket, a person merges the change, and the exposed analysis token is
low-value. A repair ticket has no person approving it, so the scan's brief carries no third-party
prose at all: only advisory ids, a Maven coordinate and two versions, each pattern-checked
(`RepairPlanner`). The merge label protects the merge decision; it does not prevent that earlier
CI execution.

## npm repairs

An npm vulnerability repair needs `frontend/package-lock.json` regenerated, integrity hashes and
all, and the agent has no npm and no network. So a `factory:cvefix` ticket titled
`Bump npm package <name> to <version> (…)` skips the agent entirely
(`TicketAgentActivitiesImpl.npmAttempt`, `NpmLockfileRepair`):

1. Clone `main`. If every copy of the package is already at the fixed release, hand back: there is
   nothing to change.
2. Start `.github/workflows/npm-lockfile-repair.yml` on **`main`** (never a branch, so the workflow
   that runs is the one the ruleset protects), with a request id and `name@version`. It has
   `contents: read`, no secret, takes its inputs only through `env`, and runs
   `npm update <name> --package-lock-only --ignore-scripts`: no package code runs and nothing is
   installed. It uploads the lockfile and a `result.json` saying whether every copy reached the
   fix, as a one-day artifact.
3. Find the run by its name (`npm lockfile repair <request id>`), poll it (10s, up to 15 minutes),
   download the artifact (the redirect is followed only to GitHub's artifact storage, without the
   token) and read exactly two entries from it.
4. Check before pushing, never on the workflow's word alone: the report must be for this request
   and say `ok`; `NpmLockPins.check` against the cloned lockfile (nothing added or removed, every
   moved entry goes up within its major to a release, from the registry's own tarball URL, with a
   SHA-512 integrity, no other field changed, at most 25 entries); and `NpmLockPins.below` must find
   no copy of the package under the fixed release.
5. Push the lockfile alone to `agent/<ticket>` and open the pull request. From there it is the
   ordinary loop: CI (`npm ci` verifies every integrity hash against the registry), the reviewer,
   auto-merge, deploy, log scan.

`npm update postcss` also moves postcss's own dependencies within their ranges (on 2026-10-24 it
moved nanoid and source-map-js too), so a pull request can list several packages. That is why the
check caps the count instead of allowing only the named package.

**One attempt.** Regenerating gives the same lockfile, so a red CI on it is handed back as stuck
rather than retried. A fix outside a dependent's declared range cannot be reached by `npm update`;
the workflow reports it and the ticket comes back to a person, as does a `package.json` change.
Two npm repairs in flight at once can conflict in the lockfile only when they move the same entry
to different versions; a conflicting pull request runs no CI and is handed back after the checks
wait.

**It needs the GitHub App's `actions: write`** to start the workflow (`GitHubCredentials` asks for
it as an optional permission, and falls back to `read`, which still serves job logs). Without it
every npm repair is handed back saying so. Dry runs start the workflow too (it writes nothing) and
post the would-be lockfile diff. Settings: `factory.ticket-agent.npm.*` (`NpmRepairProperties`).

Backend CVE changes in `gradle/libs.versions.toml` or `backend/build.gradle.kts`'s
`constraints {}` need no lockfile and go through the agent.

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

1. **Grant the GitHub App `Actions: read and write`** and accept it on the installation. It is
   an *optional* permission (`GitHubCredentials.OPTIONAL_PERMISSIONS`), asked for at `write` and
   at `read` when only that is granted, so a missing grant never stops a token minting. At `read`
   the repair brief carries the failed job's log (GitHub serves job logs only with it, even on a
   public repository); at `write` the factory can also start the npm lockfile workflow ("npm
   repairs"). Without `write`, npm repair tickets are handed back saying so.
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
