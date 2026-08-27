# Platform Status Page — Design

**Date:** 2026-08-27
**Status:** Approved, ready for planning

## Problem

There is no way to answer "what is actually running in production right now?" without
SSH-ing to the Pi and reading `docker compose ps`. There is also no changelog: the only
record of what shipped is `git log` and the GitHub releases page, and there are **zero git
tags and zero GitHub releases** in this repository.

Two facts shape everything below:

1. **The only version identifier that exists is the commit SHA.** `publish.yml` tags every
   image with `${{ github.sha }}` plus `:latest`. There is no semver scheme to read from.
2. **`main` is squash-merged, one commit per pull request** (`docs: overhaul the README and
   add architecture documentation (#118)`), and Publish runs on every merge to `main`.
   Therefore **one commit == one release**. This collapses "release", "commit" and "SHA"
   into a single concept and is why the changelog needs no notion of "the commits within a
   release".

## Goals

- A public page showing the version of each first-party service running in production.
- When each service was last built and last started.
- The versions of the third-party components the platform is built on.
- A changelog of recent releases, each with an AI-written paragraph explaining what changed.

## Non-goals

- Live container health, CPU or memory. That is Portainer's job and `monitor-prod.sh`'s job.
- Per-container start times for all ~21 containers. See "Rejected alternatives".
- Introducing git tags or a semver release scheme. The SHA is the version.
- Uptime history or incident reporting.

## Approach

Every version fact is **baked into the artifact at build time** and self-reported at runtime.
An artifact carrying its own build SHA cannot lie about what is running, and this requires no
Docker socket access, no new privileged container, and no change to the deploy path.

---

## 1. The page

Route `/status`, page title "Platform Status", lazy-loaded like every non-home route.

Discoverability: **linked from the footer on every page**, via a small version badge showing
the running frontend's short SHA. This deliberately does not take a seventh `TopNav` slot,
which is the change that would hurt mobile, and the badge is itself the single most useful
fact on the page — visible from anywhere on the site.

### 1a. Running now

One card per first-party service:

| Service | Reports | Source of truth |
|---|---|---|
| `backend` | short SHA (links to the GitHub commit), commit subject, commit time, process start time and uptime | its own baked `BuildProperties` |
| `frontend` | short SHA, build time | `import.meta.env.VITE_GIT_SHA`, read client-side |
| `software-factory` | short SHA, commit time, start time | its own `GET /api/version`, fetched by the backend |
| `deployer` | short SHA, commit time, start time | same |

The frontend reports **its own** SHA client-side rather than having the backend assert
something about it. The backend cannot know which bundle a browser loaded, and a page that
guessed would be wrong exactly when it mattered.

**Drift warning.** When the frontend SHA differs from the backend SHA, the page says so
prominently. This is a real and recurring production state, and it is the most valuable
single thing this page can surface. `software-factory` drifting from `backend` gets the same
treatment — that drift once went unnoticed for months.

### 1b. Platform components

A compact table of the third-party image tags parsed from `docker-compose.prod.yml`: mongo 8,
kafka 7.8.0, elasticsearch 8.17.0, temporal 1.31.2 / temporal-ui 2.52.1, langfuse 3.212.0,
dependency-track 5.0.3, postgres 15, clickhouse 26.7.1.1315, redis 7, portainer 2.39.2, and
the rest.

Images pinned to a floating tag (`alloy:latest`, `searxng:latest`, `chainguard/minio`, the
`FACTORY_IMAGE` default) render as **"latest — floating tag"**. They must not be given an
invented version number; the honest answer is that the compose file does not pin them.

This table states what the **compose file declares**, not what Docker has resolved. For the
pinned majority those are the same thing. The page labels the section accordingly.

### 1c. Changelog

Releases newest first, the running one badged "Running now". Each entry shows the short SHA
(linking to the commit), the date, a conventional-commit type badge (`feat` / `fix` / `chore`
/ `docs` / `perf`), the commit subject, and the AI-written paragraph.

The page requests the default `limit=20` and renders every entry it receives. There is no
pagination and no "show more": 50 releases are baked so that the backfill has depth for the
summary sweep, but the page shows the most recent 20. Changing that number is a one-line
change to the query parameter.

Entries older than the currently-running release are labelled as **published**, not
*deployed*. There are no deploy records to derive deployment from — `FACTORY_DEPLOY_ENABLED`
is unset, so `deploy_runs` is empty and deploys are still manual. Historical entries honestly
represent what was *published to ghcr*, and the page says that rather than implying a
deployment history it cannot evidence.

---

## 2. Build-time metadata

| Fact | Mechanism |
|---|---|
| backend SHA, commit time, branch | `springBoot { buildInfo { properties { additional = … } } }` — no new Gradle plugin; Spring auto-configures the `BuildProperties` bean |
| backend start time | an `Instant` captured on `ApplicationReadyEvent` |
| frontend SHA, build time | `VITE_GIT_SHA` / `VITE_BUILD_TIME` build args → `Dockerfile.frontend` `ARG`/`ENV` → the bundle |
| software-factory / deployer SHA | the same `buildInfo` mechanism in that module |
| third-party image tags | a Gradle task parsing `docker-compose.prod.yml` into a backend resource |
| changelog commits | a Gradle task baking `git log -n 50` into a backend resource |

The two Gradle tasks use `providers.exec` (configuration-cache safe) and write into
`build/generated/platform/` wired onto `sourceSets.main.resources`.

Task inputs are chosen so up-to-date checks work: the compose parser's input is the compose
file; the git-log task's input is the resolved `HEAD` SHA, so it re-runs when and only when
`HEAD` moves.

### Two load-bearing details

**`publish.yml`'s backend checkout must gain `fetch-depth: 0`.** All four checkouts in that
workflow are currently shallow (`actions/checkout@v4` defaults to depth 1), so `git log` in
CI returns **exactly one commit**. Without this change the changelog ships with a single
entry and looks like it worked.

**`buildInfo`'s `time` is pinned to the commit timestamp, not wall-clock time.** A wall-clock
timestamp changes on every build, which would invalidate `:backend:bootJar` in the Gradle
build cache that `ci-build-speedup` only just got working for the first time. The commit time
is both deterministic and the more meaningful value.

### Graceful absence

When git is unavailable or the clone is shallow, the tasks emit `"unknown"` and an empty
release list rather than failing the build. A developer running `./gradlew bootRun` outside a
git checkout must not hit a build error, and the page renders "dev build".

---

## 3. Release records and AI summaries

New MongoDB collection **`platform_releases`**, `_id` = the full commit SHA:

```
shortSha, commitTime, subject, body, type, filesChanged[],
summary, summaryStatus (PENDING | READY | FAILED), summaryAttempts,
firstSeenAt, source (RUNNING | PUBLISHED_HISTORY), updatedAt
```

### ReleaseRecorder

On `ApplicationReadyEvent`, two idempotent and insert-only steps:

1. Upsert the running release from the baked `BuildProperties`; `firstSeenAt` is set on
   insert only, `source = RUNNING`.
2. Seed any of the 50 baked commits not already present, as `source = PUBLISHED_HISTORY`,
   `summaryStatus = PENDING`.

Neither step ever overwrites an existing summary. Re-running on an unchanged SHA inserts
nothing and changes nothing, which is what makes it safe to run on every boot.

This satisfies the "backfill from git log so the page is not empty on day one" requirement:
the backfill *is* the baked history, seeded on first boot.

### ReleaseSummarySweep

`@Scheduled` **every 2 minutes**, claims up to 3 `PENDING` releases per tick and calls Embabel
`Ai` the way `ArticleSectionWriter` does. The prompt is fed the subject, body and changed-file
list so it has real material to summarise rather than just a one-line subject.

- Backfill of 50 releases completes roughly 35 minutes after first boot (17 ticks).
- Steady state is one LLM call per merge to `main`.
- Three failures → `FAILED`; the entry still renders from its commit subject.
- Gated by `platform.releases.summaries.enabled` (default true) so it can be switched off.

**Generation happens at ingest, never on view.** A public endpoint that triggers an LLM call
per request is a cost and abuse problem, and `RateLimitInterceptor` would fight the page.

### Deliberate deviation from Mongock-first

Mongock change unit **`V022`** creates the indexes (`{commitTime: -1}`, `{summaryStatus: 1}`)
and nothing else. The release documents themselves are written by `ReleaseRecorder`, not by a
change unit, because they are **derived, self-healing data** that must be re-established
after every restore anyway.

This mirrors the existing precedent where `NarrationRestoreValidator.ensureIndexes()` rather
than Mongock re-establishes narration indexes after a restore drops collections. It also
avoids a change unit performing LLM I/O against the shared Testcontainers Mongo, which is a
known way to pollute the test suite.

### Backup

`platform_releases` must be added to `BackupService.BACKUP_COLLECTIONS` and
`RestoreService.IMPORT_ORDER_INDEPENDENT`. It holds paid-for LLM output, exactly like
`article_summaries`. A test asserts the collection is present in `BACKUP_COLLECTIONS`; this
omission has been made twice before in this repository.

---

## 4. API

Both endpoints are public, cheap and cached. Neither touches an LLM.

**`GET /api/platform/status`**

```json
{
  "services": [
    {
      "name": "backend",
      "commit": "840c311a…", "shortCommit": "840c311",
      "commitSubject": "docs: overhaul the README…",
      "commitTime": "2026-08-26T14:02:11Z",
      "startedAt": "2026-08-24T09:15:03Z",
      "reachable": true
    },
    { "name": "software-factory", "…": "…" },
    { "name": "deployer", "…": "…" }
  ],
  "components": [
    { "name": "mongodb", "image": "mongo", "tag": "8", "floating": false },
    { "name": "alloy", "image": "grafana/alloy", "tag": "latest", "floating": true }
  ]
}
```

The frontend adds its own entry client-side; the backend does not report it.

`components[]` is assembled by **`ProdImageCatalog`**, which reads the resource generated from
`docker-compose.prod.yml` at build.

The `software-factory` and `deployer` entries are fetched by **`FactoryVersionClient`**: a
**1-second timeout** and a **60-second cache**, degrading to `reachable: false` rather than
propagating a failure. The status endpoint must stay fast and must never fail because a
sibling container is restarting.

**`GET /api/platform/releases?limit=20`**

```json
[
  {
    "sha": "840c311a…", "shortSha": "840c311", "type": "docs",
    "subject": "docs: overhaul the README and add architecture documentation (#118)",
    "commitTime": "2026-08-26T14:02:11Z",
    "running": true,
    "summary": "The README was rewritten…",
    "summaryStatus": "READY"
  }
]
```

### Security note

`software-factory` and `deployer` each gain `GET /api/version`, **unauthenticated but
unrouted by nginx** — reachable only from inside the Docker network.

The alternative was token-protecting it and giving the backend `FACTORY_API_TOKEN`, which
would hand the backend credentials for the internal `/api/reviews` endpoints. A commit SHA
from a public repository is not worth that widening of the backend's privileges.

`SecurityConfigTest` asserts the new public posture of the two `/api/platform/*` endpoints,
following the convention established by `035-listen-from-listing`.

---

## 5. Frontend

| File | Purpose |
|---|---|
| `pages/StatusPage.tsx` | the page, lazy route `/status` |
| `components/status/ServiceVersionCard.tsx` | one first-party service |
| `components/status/ComponentTable.tsx` | third-party image tags |
| `components/status/ReleaseList.tsx` / `ReleaseEntry.tsx` | the changelog |
| `components/layout/VersionBadge.tsx` | the footer badge |
| `hooks/usePlatformStatus.ts`, `hooks/useReleases.ts` | data fetching |
| `config/version.ts` | reads `VITE_GIT_SHA` / `VITE_BUILD_TIME` |

`VersionBadge` renders from the bundle's own baked SHA, so it costs **no network request** on
any page — it is on every page via the footer, so a fetch there would be a site-wide cost.

Plain CSS with BEM against the existing custom properties in `styles.css`.

**Mobile:** service cards stack to a single column; `ComponentTable` degrades to a definition
list at narrow widths rather than scrolling horizontally; release entries are already
vertical. The footer badge sits alongside the existing copyright in the single footer bar.

### Degradation

Every one of these renders rather than erroring:

| Condition | Behaviour |
|---|---|
| no build-info (local Gradle run) | "dev build" |
| `platform_releases` empty | running version plus "no release history yet" |
| `summaryStatus` = `PENDING` | commit subject with a subtle "summary pending" note |
| `summaryStatus` = `FAILED` | commit subject only, no error surfaced to visitors |
| software-factory / deployer unreachable | "not reporting" |
| `/api/platform/*` fails | `ErrorMessage` with a retry, per the `McpPage` pattern |

---

## 6. Testing

**Backend**
- `PlatformStatusControllerTest`, `PlatformReleasesControllerTest` — shape and public access.
- `SecurityConfigTest` — the two new endpoints are public.
- `ReleaseRecorderTest` — a second run on an unchanged SHA inserts nothing and preserves
  existing summaries.
- `ReleaseSummarySweepTest` — mocked `Ai`; `PENDING` → `READY`; failure increments attempts;
  three failures → `FAILED`; the flag disables the sweep.
- `ProdImageCatalogTest` — parses the **real** `docker-compose.prod.yml`, so it fails when
  the parser and the compose file drift apart.
- `BackupServiceTest` — `platform_releases` is in `BACKUP_COLLECTIONS`.
- `V022` change unit test, per the repo's existing change-unit test pattern.
- `FactoryVersionClientTest` — unreachable factory yields `reachable: false`, within timeout.

**Frontend**
- `StatusPage.test.tsx` — renders services, components and releases; the **drift-warning
  case**; empty, pending and error states.
- `VersionBadge.test.tsx` — renders the baked SHA, links to `/status`, and renders sanely
  when `VITE_GIT_SHA` is absent.

---

## Rejected alternatives

**A `deployer`-published live snapshot.** `deployer` is the only container holding the Docker
socket, so it could periodically `docker inspect` the stack and write a status document for
the backend to serve. That would be strictly more truthful — real resolved image digests,
per-container start times and health for all ~21 containers. Rejected for this ship because
it needs a new scheduled component inside `software-factory` plus a cross-database read
(`software_factory` vs `simonrowe`), and it puts new recurring load on the one container that
can mutate production. The chosen design's endpoint shape can absorb that data later without
a breaking change: `components[]` gains optional `startedAt` / `digest` / `health` fields.

**Reading deploy history from `deploy_runs`.** `FACTORY_DEPLOY_ENABLED` is unset, so that
collection is empty in production and deploys are manual. It would show nothing.

**A per-commit AI summary rather than per-release.** Moot once one commit equals one release.

**Fetching commits from the GitHub API at runtime.** Would show commits merged but not yet
deployed, which is confusing on a page whose entire purpose is stating what *is* running. It
also adds an unauthenticated 60-requests-per-hour rate limit to a public page.

**Introducing git tags / GitHub releases.** A larger change to how the project ships, and it
would not make this page any more accurate. Worth doing on its own merits, separately.

---

## Consequences

- `publish.yml` gains `fetch-depth: 0` on the backend job and two frontend build args.
- `Dockerfile.frontend` gains two `ARG`/`ENV` pairs.
- `backend/build.gradle.kts` gains `buildInfo` and two generator tasks.
- `software-factory` gains `buildInfo` and a `/api/version` endpoint used by both containers.
- One new collection, one Mongock change unit, two entries in the backup lists.
- One LLM call per merge to `main` in steady state, plus 50 once at backfill.
- `docs/runbooks/` should gain a short note that `/status` exists and what it can and cannot
  evidence (published vs deployed).
