# Platform status page (`/status`)

Public page reporting which commit each first-party service is running, which third-party
image tags production declares, and a changelog of recent releases with AI-written notes. Design
and plan: `docs/superpowers/specs/2026-08-27-platform-status-page-design.md` and
`docs/superpowers/plans/2026-08-27-platform-status-page.md` — there is no `specs/037-*`
directory for this feature.

## What it can and cannot evidence

- **"Running now" is evidenced.** Each first-party service reports the commit baked into its
  own artifact. The frontend reports its SHA client-side from its own bundle (`VITE_GIT_SHA` /
  `VITE_BUILD_TIME`, baked in by `publish.yml`), so it cannot be wrong about which bundle you
  loaded — the backend has no way to know that and does not try.
- **`GET /api/platform/status` returns three services, not four.** The backend answers for
  itself, then asks `software-factory` and `deployer` over the Docker network
  (`FactoryVersionClient`, port **8090**, 1s timeout, 60s cache). The frontend adds its own
  entry to the list client-side — see `PlatformStatusResponse`'s Javadoc: "the backend cannot
  know which bundle a browser loaded, and a guess would be wrong exactly when it mattered."
- **The services normally report different commits.** Publish rebuilds only the images whose
  code changed ([build-and-startup.md](build-and-startup.md)), so after a frontend-only merge
  the frontend is newer than the backend, and that is a healthy state. **The drift warning
  therefore compares only `software-factory` and `deployer`**, which run one image: a
  difference there still means the deployer, which never recreates itself, was left behind.
- **"not reporting" and "dev" are not the same failure.** "not reporting" (an unreachable
  service card) means the backend could not reach that service's `/api/version` at all —
  container down, restarting, or running an old image without the endpoint. That is a
  connectivity problem. "dev" (a reachable service whose short commit renders as `dev`, with
  `commit` equal to `unknown`) means the service *was* reached and answered, but was built
  without commit metadata — no `GIT_SHA` build arg and no `.git` in the build context. That is
  a build-pipeline problem, not a connectivity one; see the `software-factory`/`deployer`
  Gotcha below for how it has broken in practice.
- **"Platform components" states what the compose file declares**, not what Docker resolved.
  For pinned tags those match. Floating tags (`alloy`, `searxng`, `minio` — none of them carry
  an explicit version tag, so `ProdImageCatalog` defaults them to `latest` and marks them
  floating) are labelled as such and no version is invented. `software-factory` and `deployer`
  never appear in this table at all, even though their compose entries reference the floating
  `${FACTORY_IMAGE}` variable — both service names are in `ProdImageCatalog.FIRST_PARTY` and
  excluded, because they already self-report a commit SHA in the services table, which is a
  far better answer than an image tag.
- **Changelog entries other than the running one record what was *published*, not deployed.**
  "Running" marks the commit the backend was built from, which after a merge that did not touch
  the backend is older than the newest commit deployed. The page's wording carries that.

## How the data gets there

| Fact | Source |
|---|---|
| backend SHA / commit time / subject | `springBoot { buildInfo }` in `backend/build.gradle.kts`, read at runtime by `RunningVersion` |
| backend start time | captured in `RunningVersion`'s constructor (`Instant.now()`), **not** from `ApplicationReadyEvent` — `ReleaseRecorder` listens to that event and needs `startedAt` already populated, and coupling the two through listener ordering would only buy a second or two of accuracy |
| frontend SHA / build time | `VITE_GIT_SHA` / `VITE_BUILD_TIME` build args, baked into the bundle by `publish.yml` |
| software-factory / deployer version | their own `GET /api/version` on port **8090**, fetched by `FactoryVersionClient` |
| third-party tags | `ProdImageCatalog` parses `docker-compose.prod.yml`, shipped into the backend image as a `processResources` resource |
| changelog commits | `GitHubCommitHistory` reads `main`'s last 50 commits from the GitHub REST API (public repo, no credential) |
| release records in Mongo | `ReleaseRecorder`, polling every 5 minutes (20s after startup) — not a Mongock change unit (see Gotchas) |
| release notes | `ReleaseSummarySweep`, every 2 minutes, 3 per tick, Embabel `Ai` |

### `platform_releases` fields

`PlatformRelease` (`backend/src/main/java/com/simonrowe/platform/PlatformRelease.java`), one
document per commit SHA:

| Field | Notes |
|---|---|
| `_id` | the full commit SHA — what makes seeding idempotent |
| `shortSha`, `commitTime`, `subject`, `body`, `type`, `filesChanged` | from the GitHub API (committer date; files from a per-commit request), immutable once inserted |
| `summary` | the AI-written paragraph, null until `READY` |
| `summaryStatus` | `PENDING` / `READY` / `FAILED` |
| `summaryAttempts` | counted by the sweep, gives up at `platform.releases.summaries.max-attempts` |
| `firstSeenAt`, `updatedAt` | bookkeeping |
| `source` | `PUBLISHED_HISTORY` or `RUNNING` — promoted to `RUNNING` once a backend built from that SHA is running |

There is no `insertions`/`deletions` field — that was dropped from the schema during
implementation as dead weight the page never rendered.

## Gotchas

- **`software-factory`/`deployer` version metadata does not come from git at image build
  time.** `Dockerfile.software-factory` runs `./gradlew :software-factory:bootJar` *inside* the
  build stage, and `.dockerignore` excludes `.git/` from the build context (shipping full repo
  history into a published image is the wrong trade), so there is no `.git` directory for
  Gradle to read from at that point. Instead, `publish.yml`'s `publish-software-factory` job
  resolves `GIT_SHA` (`github.sha`), `GIT_COMMIT_TIME` (`git log -1 --format=%ct`) and
  `GIT_COMMIT_SUBJECT` (`git log -1 --format=%s`, delimited to survive a subject containing
  arbitrary characters) on the runner's full-history checkout, and passes them as
  `docker/build-push-action` build-args. `Dockerfile.software-factory` re-exposes them as `ARG`
  then `ENV` *before* the Gradle invocation. `software-factory/build.gradle.kts` prefers the
  environment variable and only falls back to running `git` directly when it is unset — the
  fallback exists purely for a local `./gradlew build` outside CI, where `.git` is present.
  **This was a genuine bug during implementation**: before the build-arg plumbing existed, the
  image had no way to learn its own commit, and both `software-factory`'s and `deployer`'s
  `/api/version` (they run the identical image) would have permanently reported `"unknown"` —
  silently, since nothing fails when a `buildInfo` property is missing. If the status page ever
  shows `unknown` for either of those two services in production, check that
  `Dockerfile.software-factory`'s `ARG`/`ENV` block and `publish.yml`'s
  `publish-software-factory` job still agree on all three variable names before looking
  anywhere else.
- **The changelog is read from GitHub, not baked into the image.** It used to be
  `git log -n 50` baked into the backend at build time, which only worked while every merge
  rebuilt the backend. Once Publish skipped unchanged images, a frontend-only merge would never
  have reached `/status`. `GitHubCommitHistory` is shaped by the anonymous API limit of
  **60 requests an hour per address**: the listing is sent with `If-None-Match`, and a `304`
  does not count against the limit, so an unchanged branch costs nothing. Files cost one request
  per commit, only for commits not yet stored, capped at 20 a poll
  (`platform.releases.history.max-file-lookups`), so a restore into an empty collection spreads
  its 50-commit backfill over three polls. A failed file lookup ends the poll without storing
  that commit, because a record is never revisited and its release note is written from the
  files. `platform.releases.history.enabled: false` in `application-test.yml` keeps every test
  off the network.
- **The changelog is paged and searched on the server**: `GET /api/platform/releases?page=&size=&type=&q=`
  returns a `ReleasePage` (`items`, `page`, `size`, `totalItems`, `totalPages`, `totalReleases`,
  `typeCounts`). Everything stored is reachable; the page used to fetch the newest 20 and filter
  those in the browser. `size` is clamped to 50. Search follows the news page: every term must
  match, each may match a different field (subject, release note, SHA), and each is a quoted
  literal. `typeCounts` and `totalReleases` ignore the filters, so the pills keep their numbers
  while you type.
- **`buildInfo`'s `time` must stay pinned to the commit timestamp**, not wall-clock, in both
  `backend/build.gradle.kts` and `software-factory/build.gradle.kts`. A wall-clock value
  changes on every build and invalidates `:backend:bootJar` in the Gradle build cache that
  `ci-build-speedup` only just got working. (`build-info.properties` itself is excluded from
  the test tasks' cache key by `normalization`, because the SHA in it changes every commit —
  see [build-and-startup.md](build-and-startup.md).)
- **`/api/platform/**` is deliberately not in `RateLimitInterceptor`.** `WebConfig` registers
  that interceptor against an explicit four-pattern allowlist (`/mcp/**`,
  `/api/blogs/*/narration`, `/api/news/*/summary`, `/api/news/*/summary/narration`) —
  `/api/platform/**` is simply absent, not exempted by a branch inside the interceptor. The
  page issues two requests per view (`/status` and `/releases`); metering it would 429 ordinary
  readers on first load.
- **Do not add authentication to software-factory's `GET /api/version`.** nginx routes only
  `POST /webhooks/github` (`location =`, exact match), so `/api/version` is unreachable from
  the internet and discloses only a public-repo commit SHA. Token-protecting it would mean
  giving the backend a token that also authorises `/api/reviews`. This is also the endpoint
  that makes `deployer` drift visible on the page, since `deployer` never recreates itself.
- **Summaries are generated at ingest, never on view.** Nothing reachable from the two public
  `GET` endpoints may call an LLM — see `PlatformStatusService`'s class Javadoc.
- **Releases go `PENDING` → `READY` or `FAILED` only, with no intermediate "claimed" state.**
  `ReleaseSummarySweep.sweep()` reads `findPending()` (a plain `summaryStatus == PENDING`
  query) and calls `summarise()` directly, with no `findAndModify` claim step in between. This
  is safe **only** because production runs a single backend instance and
  `@Scheduled(fixedDelayString = "PT2M")` cannot let a second tick start before the first
  returns — two concurrent instances, or a switch to `fixedRate`, would let two ticks
  summarise (and bill for) the same release. If either of those ever changes, add a real
  `findAndModify` claim step rather than a status value nothing sets: a crash mid-call should
  leave the row retryable, not stuck.
- **`platform_releases` is in the backup and restore lists** and must stay there — it holds
  paid-for LLM output, and the GitHub poll only reaches back 50 commits, so nothing older could
  ever be re-created after a restore without it. It is in
  `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`.
- **Release records are written by `ReleaseRecorder` on startup, not by a Mongock change
  unit** — a deliberate deviation from the repo's Mongock-first rule. They are derived,
  self-healing data: a restore drops the collection and `ReleaseRecorder` re-establishes it on
  the next boot, and seeding inside a change unit would mean LLM-adjacent I/O running against
  the shared Testcontainers Mongo in every integration test. `V022CreatePlatformReleaseIndexes`
  creates indexes only. A restore drops collections (and their indexes) with them, so
  `RestoreService` calls `V022CreatePlatformReleaseIndexes.createIndexes()` directly — Mongock
  will not re-run a change unit it has already recorded.

## Operations

Turn summary generation off:
`PLATFORM_RELEASE_SUMMARIES_ENABLED=false` in the deploy directory's `.env`, then recreate
the backend.

Regenerate every summary after a prompt change (there is no format-version invalidation,
because the document id is the commit SHA, not a hash that includes the prompt version):

```javascript
// mongosh, against the simonrowe database
db.platform_releases.updateMany(
  { summaryStatus: { $in: ["READY", "FAILED"] } },
  { $set: { summaryStatus: "PENDING", summaryAttempts: 0 } }
)
```

The sweep picks them up within two minutes, three at a time.

Check what production thinks it is running, without SSH:

```bash
curl -s https://api.simonrowe.dev/api/platform/status | jq '.services'
curl -s "https://api.simonrowe.dev/api/platform/releases?size=3" | jq '.items[] | {shortSha, running, summaryStatus}'
```

`services` has three entries (`backend`, `software-factory`, `deployer`) — the frontend's own
entry is not part of this response; check the page itself, or `VersionBadge`/`StatusPage` in
the frontend bundle, for what the browser reports about itself.
