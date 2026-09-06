# Vulnerability Scan Production Runbook

The `cvefix` module is now an issue-only Dependency-Track scanner. It runs in
`software-factory`, uses the `cve-fix` Temporal queue, and owns the daily `cve-fix-daily` schedule.

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
cancelled or duplicate report is suppressed. The scan never edits dependencies, launches a repair
agent, creates a branch or pull request, or polls CI.

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

Do not repair findings with an ad-hoc factory command. The planned Linear-to-repair workflow is a
separate future feature.
