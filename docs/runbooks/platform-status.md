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
- **The drift warning is the most useful thing on the page.** A partial deploy, or `deployer`
  left behind because it excludes itself from its own recreate list, shows up here as a commit
  mismatch between services.
- **"Platform components" states what the compose file declares**, not what Docker resolved.
  For pinned tags those match. Floating tags (`alloy`, `searxng`, `minio` — none of them carry
  an explicit version tag, so `ProdImageCatalog` defaults them to `latest` and marks them
  floating) are labelled as such and no version is invented. `software-factory` and `deployer`
  never appear in this table at all, even though their compose entries reference the floating
  `${FACTORY_IMAGE}` variable — both service names are in `ProdImageCatalog.FIRST_PARTY` and
  excluded, because they already self-report a commit SHA in the services table, which is a
  far better answer than an image tag.
- **Changelog entries other than the running one record what was *published*, not deployed.**
  `deploy_runs` is empty while auto-deploy is off (`036-auto-deploy-on-merge`), so deployment
  history does not exist yet; the wording on the page carries that distinction on purpose.

## How the data gets there

| Fact | Source |
|---|---|
| backend SHA / commit time / subject | `springBoot { buildInfo }` in `backend/build.gradle.kts`, read at runtime by `RunningVersion` |
| backend start time | captured in `RunningVersion`'s constructor (`Instant.now()`), **not** from `ApplicationReadyEvent` — `ReleaseRecorder` listens to that event and needs `startedAt` already populated, and coupling the two through listener ordering would only buy a second or two of accuracy |
| frontend SHA / build time | `VITE_GIT_SHA` / `VITE_BUILD_TIME` build args, baked into the bundle by `publish.yml` |
| software-factory / deployer version | their own `GET /api/version` on port **8090**, fetched by `FactoryVersionClient` |
| third-party tags | `ProdImageCatalog` parses `docker-compose.prod.yml`, shipped into the backend image as a `processResources` resource |
| changelog commits | `generateReleaseHistory` (`backend/build.gradle.kts`) bakes `git log -n 50` into a resource at build time |
| release records in Mongo | `ReleaseRecorder`, on every backend startup — not a Mongock change unit (see Gotchas) |
| release notes | `ReleaseSummarySweep`, every 2 minutes, 3 per tick, Embabel `Ai` |

### `platform_releases` fields

`PlatformRelease` (`backend/src/main/java/com/simonrowe/platform/PlatformRelease.java`), one
document per commit SHA:

| Field | Notes |
|---|---|
| `_id` | the full commit SHA — what makes seeding idempotent |
| `shortSha`, `commitTime`, `subject`, `body`, `type`, `filesChanged` | baked from `git log`, immutable once inserted |
| `summary` | the AI-written paragraph, null until `READY` |
| `summaryStatus` | `PENDING` / `GENERATING` / `READY` / `FAILED` (see Gotchas — `GENERATING` is never actually set) |
| `summaryAttempts` | counted by the sweep, gives up at `platform.releases.summaries.max-attempts` |
| `firstSeenAt`, `updatedAt` | bookkeeping |
| `source` | `PUBLISHED_HISTORY` or `RUNNING` — promoted to `RUNNING` the first time a build boots on that SHA |

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
- **`publish.yml` needs `fetch-depth: 0` on the image-building checkouts** — `publish-backend`,
  `publish-frontend` and `publish-software-factory` all carry it, each with its own comment
  explaining why. The default depth-1 checkout makes `git log` return one commit, so the
  changelog ships with a single entry and looks like it worked. The `sbom` job's checkout is
  deliberately left at the default shallow depth: it never calls `generateReleaseHistory` or
  reads `buildInfo`, so `fetch-depth: 0` there would only slow it down for nothing.
- **`buildInfo`'s `time` must stay pinned to the commit timestamp**, not wall-clock, in both
  `backend/build.gradle.kts` and `software-factory/build.gradle.kts`. A wall-clock value
  changes on every build and invalidates `:backend:bootJar` in the Gradle build cache that
  `ci-build-speedup` only just got working.
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
- **`ReleaseSummaryStatus.GENERATING` exists but nothing ever sets it.** `PlatformRelease`'s
  class Javadoc still describes a `PENDING` → `GENERATING` → `READY` lifecycle, and the enum
  constant's own comment says it "guards against two ticks summarising the same release" — but
  `ReleaseSummarySweep.sweep()` reads `findPending()` (a plain `summaryStatus == PENDING`
  query) and calls `summarise()` directly, with no `findAndModify` claim step in between.
  Releases go `PENDING` → `READY` or `FAILED` only. This is safe **only** because production
  runs a single backend instance and `@Scheduled(fixedDelayString = "PT2M")` cannot let a
  second tick start before the first returns — two concurrent instances, or a switch to
  `fixedRate`, would let two ticks summarise (and bill for) the same release. If either of
  those ever changes, `GENERATING` needs to actually be claimed, not just declared.
- **`platform_releases` is in the backup and restore lists** and must stay there — it holds
  paid-for LLM output, and older entries cannot be regenerated by a newer image because the
  history baked into that image only reaches back 50 commits from its own build. It is in
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
curl -s "https://api.simonrowe.dev/api/platform/releases?limit=3" | jq '.[] | {shortSha, running, summaryStatus}'
```

`services` has three entries (`backend`, `software-factory`, `deployer`) — the frontend's own
entry is not part of this response; check the page itself, or `VersionBadge`/`StatusPage` in
the frontend bundle, for what the browser reports about itself.
