# Vulnerability Scan Production Runbook

The `cvefix` module is a Dependency-Track scanner. It runs in `software-factory`, uses the
`cve-fix` Temporal queue, and owns the daily `cve-fix-daily` schedule. It files a consolidated
report and, for backend components with a safe fix, repair tickets the ticket agent works on its
own (see [Repair tickets](#repair-tickets)). The scan itself never edits code.

## What it does

Each scheduled or admin-triggered run:

1. Reads every current finding for the configured Dependency-Track projects.
2. Groups findings by Dependency-Track project, then by component within it, for a readable
   report.
3. Creates or updates one Linear issue keyed as the repository's current vulnerability report.
4. Stores the terminal scan result and Linear URL in `cve_fix_runs`.

The report files under `FilingMode.ROLLING`. An existing open report has its description
rewritten to the complete current snapshot and gets **no comment** — a night where the finding set
changed produces no visible activity in the ticket beyond the rewrite itself. A completed report is
reopened into Triage and rewritten, rather than replaced by a linked regression issue: closing the
report once used to cause the next dirty scan to file a second, parallel ticket beside it (SIM-10
beside the completed SIM-9), and `ROLLING` exists specifically so that no longer happens. A
cancelled or duplicate report is suppressed. The scan never edits dependencies, creates a branch or
pull request, or polls CI; repair tickets hand that work to the ticket agent.

## Report grouping and scope

The report is grouped by Dependency-Track project, not just by component: a `##` heading per
project, `###` per component underneath it. Components within a project lead with their most
severe finding, and advisories within a component sort most severe first too. Project order
follows `factory.cvefix.dependency-track.projects` — reordering that list reorders the report's
headings, because the client iterates it in the order it is configured.

Only `simonrowe-dev/backend` and `simonrowe-dev/frontend` are in scope today. CI also publishes
SBOMs for `simonrowe-dev/backend-image`, `simonrowe-dev/frontend-image` and
`simonrowe-dev/software-factory-image` to Dependency-Track, and nothing currently reads them.
Adding them to the `projects` list is a configuration-only change, but their findings are image
SBOMs carrying base-OS packages, which cannot be fixed from this repository — expect a noisier,
less actionable report if they are added.

A project with no current findings gets no heading at all. That can only mean the project is
clean, never that it was silently skipped: `DependencyTrackClient.uuidFor` throws when a
configured project name is absent from Dependency-Track, so a missing project surfaces as a
failed scan, not a quiet gap in the report.

When a scan comes back clean and the previous scan found something, the run posts one comment on
the existing ticket noting the repository is now clean, and still ends `NO_FINDINGS` — it never
creates a ticket for this and never closes one itself. This dirty-to-clean transition is the
**only** case in this module that ever posts a comment: it files under `FilingMode.STATUS_UPDATE`,
not `ROLLING`, specifically because a status update is a comment on an already-open ticket, never a
rewrite. Closing stays a human decision; until that happens, the ticket is still open, but a
**second** consecutive clean scan stays silent — that is the whole point of the transition guard.
A dirty scan, by contrast, never comments at all — it rewrites the report's description under
`ROLLING` instead (see [What it does](#what-it-does)) — so do not read a quiet night, dirty or
clean, as a fault; check the ticket's description and `cve_fix_runs` for what actually happened.

`cve_fix_runs.componentsSeen` now counts `(project, PURL)` pairs, so a component that shows up as
a finding in two configured projects (for example a shared library flagged in both `backend` and
`frontend`) counts twice.

## Required configuration

- `FACTORY_CVEFIX_ENABLED=true` — defaults true on the production `software-factory` service.
- `DEPENDENCYTRACK_API_KEY` with `VIEW_PORTFOLIO` and `VIEW_VULNERABILITY`.
- `FACTORY_LINEAR_ENABLED=true` — defaults true on `software-factory`.
- `LINEAR_API_KEY`, `FACTORY_LINEAR_TEAM_KEY`, and the `factory:cvefix` team label.

Dependency-Track defaults to the internal
`http://dependencytrack-apiserver:8080` endpoint. Missing credentials fail the scan visibly without
preventing code review or the factory web process from starting — and they are visible **before**
a scan is attempted: `ModulePrerequisites` reports "Dependency-Track API key is not set" and
"Linear filing is disabled, so findings have nowhere to go" on `GET /api/factory/status`, on the
admin console, and once in the container log at startup. That distinction matters here more than
anywhere else in the factory, because both of this module's flags now default to `true` while both
of its credentials still default to empty.

Two deliberate leftovers in `CveFixProperties`: the `agent` block and the `ci` block
(`poll-interval`, `repair-budget`, `advisory-checks`) are retained and **unused**. They stay only
so a Temporal history serialized by the former auto-fix implementation still deserializes; nothing
in the issue-only flow reads them. Do not tune them expecting an effect.

## Schedule and manual run

A newly-created schedule is active and runs every 24 hours. On restart the initializer reconciles
its action/spec/policy while preserving the server's existing paused state.

Use `/admin/software-factory` → **Vulnerability report** → **Scan now** for an on-demand real scan.
The backend returns the Temporal workflow and run identifiers immediately. For low-level diagnosis:

```bash
docker run --rm --network simonrowe-dev-monorepo_default \
  temporalio/admin-tools:1.31.2 \
  temporal task-queue describe --address temporal:7233 \
  --namespace default --task-queue cve-fix
```

Expect workflow and activity pollers on `cve-fix` and an activity poller on `linear`. A healthy
container with a missing poller cannot execute the scan.

## Failure diagnosis

- Failure before filing: verify the Dependency-Track URL, key permissions, and configured project
  names.
- Filing timeout: verify `FACTORY_LINEAR_ENABLED`, the `linear` activity poller, API key, team key,
  and label. A scan started with the sink switched off does **not** stall for the activity's
  2-minute `scheduleToCloseTimeout`: the workflow reads a request-level `linearFilingEnabled` flag
  and fails non-retryably with `LINEAR_DISABLED` in milliseconds, because with
  `factory.linear.enabled=false` nothing polls the `linear` queue at all.
- Repeated issue instead of an update: inspect the stable factory fingerprint attachment and the
  `linear_issues` Mongo record.
- No scheduled run: inspect `cve-fix-daily` in Temporal and confirm whether an operator paused it.

## Repair tickets

With `factory.cvefix.auto-repair.enabled` (`FACTORY_CVEFIX_AUTO_REPAIR_ENABLED`, default `true`
on `software-factory` in compose, `false` in `application.yml`), each scan also files one repair
ticket per backend Maven component or frontend npm package it can fix without a person, labelled
`factory:cvefix` and `factory:agent`. The ticket agent's intake picks it up; for Maven the agent
bumps the version, for npm GitHub Actions regenerates the lockfile (no agent involved, see
"npm repairs" below). CI and the reviewer check it, and a version-only change merges and deploys
itself. The post-deploy log scan
then watches what shipped. The whole loop, with nobody in it:

```
Dependency-Track ─▶ scan ─▶ repair ticket ─▶ ticket agent ─▶ PR ─▶ CI + review
   ─▶ auto-merge ─▶ Publish ─▶ deploy ─▶ log scan
```

A component gets a repair ticket only when every check below passes (`RepairPlanner`). Anything
else stays in the consolidated report for a person, as before:

- It is in `factory.cvefix.auto-repair.projects` (`simonrowe-dev/backend` and
  `simonrowe-dev/frontend`) and is a Maven or npm PURL. The image projects stay out: base-OS
  packages are not in any manifest here.
- OSV (`api.osv.dev/v1/query`, by ecosystem, name and version) knows its advisories, and every one
  has a fixed release. Dependency-Track has no fixed-version field, and the agent has no network, so
  the target version has to arrive in the ticket.
- The target is the highest of those fixes, newer than the current version, and in the **same
  major version**. A major bump is an API change and a person's call.
- For npm, the fix is a plain release (`1.2.3`, no pre-release), because the lockfile check
  refuses pre-releases.
- Every value in the brief is pattern-shaped: advisory ids, the coordinate or package name, two
  versions. No advisory prose reaches the agent, because no person reads the ticket before it
  acts.

Each ticket is keyed on the component **at its current version** and filed under `REFRESH`: an open
ticket is rewritten quietly each night. `factory:agent` goes on a brand-new ticket only, never on a
regression. So a bump that merged but did not take effect (the SBOM still shows the old version)
files a linked ticket **without** the agent label, for a person, rather than looping the agent on a
fix that does not work. Cancel a repair ticket to stop it for good; remove `factory:agent` to keep
it but take it off the agent.

What the agent may change, and what merges itself: `ticket-agent.md` ("Dependency edits" and
"npm repairs") and `pr-governance.md` (auto-merge rule 8). In short, only version strings in
`gradle/libs.versions.toml`, root `build.gradle.kts` `ext["…"]` overrides and `backend` constraints,
or a regenerated `frontend/package-lock.json` that only moves existing packages up.

An npm ticket's title is the contract: `Bump npm package <name> to <version> (<advisories>)`
(`NpmRepairTitle`, used by both sides). The ticket agent takes the npm path only for a
`factory:cvefix` ticket whose title parses; a hand-written npm ticket goes to the agent as before.

**The intake schedule must be running.** `ticket-agent-intake` is created paused. Until an
operator resumes it from the console, repair tickets wait in Linear with the label on.

Planning failures (OSV down, a component it cannot answer) never fail the scan: the report is
already filed, and the run's detail says no repair tickets were filed. Runs started by an older
build carry no `autoRepairEnabled` and file only the report (`Workflow.getVersion("repair-tickets")`).

Do not repair findings with an ad-hoc factory command. A person can still hand any finding to the
[ticket agent](ticket-agent.md) by hand: inspect a **Dry run ticket**, then add `factory:agent` or
use **Start agent**. Those runs keep `no-auto-merge` on dependency edits unless the ticket carries
`factory:cvefix`.
