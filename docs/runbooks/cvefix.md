# Vulnerability Scan Production Runbook

The `cvefix` module is now an issue-only Dependency-Track scanner. It runs in
`software-factory`, uses the `cve-fix` Temporal queue, and owns the daily `cve-fix-daily` schedule.

## What it does

Each scheduled or admin-triggered run:

1. Reads every current finding for the configured Dependency-Track projects.
2. Groups findings by component for a readable report.
3. Creates or updates one Linear issue keyed as the repository's current vulnerability report.
4. Stores the terminal scan result and Linear URL in `cve_fix_runs`.

An existing open report receives a comment containing the complete current snapshot. A completed
report produces a regression issue under the shared Linear filing policy; a cancelled or duplicate
report is suppressed. The scan never edits dependencies, launches a repair agent, creates a branch
or pull request, or polls CI.

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
