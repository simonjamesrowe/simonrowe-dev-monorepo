# Selective builds and JVM startup

CI and Publish build only what a change touched, and the two JVM images start from a Project
Leyden AOT cache. This is how both work, what each one depends on, and how to turn them off.

## What a change builds

`scripts/changed-areas.sh` maps changed paths to areas. Both workflows call it; neither has a
`paths:` filter.

| Area | Turns on | Triggered by |
| --- | --- | --- |
| `backend` | CI `Backend Build & Test` | `backend/**`, the shared Gradle build, `config/checkstyle/**`, `docker-compose.prod.yml` |
| `frontend` | CI `Frontend Build & Test` | `frontend/**` |
| `factory` | CI software-factory Gradle checks and image build | `software-factory/**`, `Dockerfile.software-factory`, the shared Gradle build, `config/nginx/**`, `scripts/classify-change.sh`, `scripts/test/fixtures/**`, `docker-compose.prod.yml`, `.dockerignore` |
| `shell` | CI `scripts/test/run-tests.sh` | anything that is not documentation (about 25 seconds) |
| `image_backend` / `image_frontend` / `image_factory` | Publish rebuilds that image | that module, its Dockerfile, the shared Gradle build where it uses it, `.dockerignore`; the compose file for the backend |

Documentation, design material and `evals/` build nothing (`evals/` has its own workflow).
**Any path no rule names builds everything**, as does a base commit that cannot be diffed. A
`.github/workflows/` change therefore always runs the whole pipeline. To make a new directory
cheaper, add a rule and a case to `scripts/test/test-changed-areas.sh`.

The rules follow what each build *reads*, which is why some look odd: the prod compose file is
baked into the backend image (`ProdImageCatalog`) and read by both Java test suites;
`FactoryPublicSurfaceTest` reads the nginx proxy conf; `MergeDispositionTest` reads the
classifier and its fixtures.

### CI

- **Pull requests are filtered; a push to `main` builds everything.** The `main` run writes the
  Gradle cache every pull request restores, and gives Static Analysis a whole-project baseline.
- **Skipping is a job-level `if:`.** A job skipped that way reports as passed, which keeps the
  three required checks satisfiable. A workflow-level `paths:` filter would leave a required
  check missing, and a missing required check blocks the merge forever.
- **If `Detect changes` fails, every job runs.** A job skipped because its `needs` failed also
  reports as passed, so without that rule a broken detector would wave every pull request
  through untested.
- **Static Analysis on a pull request** gets coverage only from the modules whose jobs ran. A
  pull request analysis grades new code, and an untouched module has none. The input check
  requires exactly those reports.

### The Gradle cache now actually caches the backend tests

Unchanged code no longer re-runs its tests: `:backend:test` and `:software-factory:test` come
back `FROM-CACHE`. Before this change they never did, because `bootBuildInfo` writes the commit
SHA into `META-INF/build-info.properties`, which is on the test runtime classpath. Every commit
changed the test task's cache key, so a docs-only pull request spent about 4 minutes re-running
the Testcontainers suite. Both build files now ignore that file in
`normalization.runtimeClasspath`. No test reads the generated file; every test that needs
`BuildProperties` builds its own.

Any backend change still runs the whole backend suite. Gradle caches by task, not by test, and
selecting tests by change would need Develocity's commercial predictive test selection, or
splitting the backend into several Gradle modules.

### Publish

Every merge still publishes all three images under its commit SHA, because the deployer pulls
`:<sha>` for each (`restart-prod.sh`'s `pull` phase). An image whose inputs did not change gets
that tag from `scripts/ci/retag-image.sh`, a registry-side copy of the previous commit's image:
seconds, no pull, **same digest**. The deploy's `up -d` therefore sees an unchanged image and
leaves the container running, so a frontend-only merge no longer restarts the backend.

- **`--prefer-index=false` is load-bearing.** Without it, `imagetools create` wraps
  `bootBuildImage`'s single-platform manifest in a new index with a different digest, and a
  Docker host on the containerd image store would recreate the container anyway.
- **An image is only reused from a commit whose own Publish produced it.** The re-tag needs
  `:<previous-sha>`. When that image does not exist, because its Publish failed or was
  cancelled, the job builds instead, as it also does when the re-tag itself fails.
- **Publish runs are serialised** (`concurrency: publish`, never cancelled). GitHub keeps one
  run pending and cancels an older pending run when a newer one arrives. That is safe: the
  newer run finds no image for the cancelled commit and rebuilds.
- `fetch-depth: 0` is gone from the image jobs. Only the `changes` job needs history. The
  changelog is no longer baked into the backend (see [platform-status.md](platform-status.md)).

### What selective builds changed elsewhere

- **The services report different commits, and that is normal.** `/status` used to warn
  whenever any two differed. It now compares only `software-factory` and `deployer`, which run
  one image, so a difference between them still means the deployer was left behind.
- **Manual redeploy targets the newest commit any service was built from**, not "the commit
  frontend and backend agree on" (they rarely do now). Each service's image at that commit is
  the one running, so nothing moves. Any older commit would roll back whichever service was
  built after it. `RedeployTarget` (backend) is the boundary, and `redeployTarget.ts` mirrors
  it so the console can name the commit. The frontend's commit is accepted only if
  `platform_releases` holds it, and its time is read from there.

## JVM startup: the AOT cache

Both JVM images ship a Leyden AOT cache (JEP 483/514/515), trained at image build time. The
JVM maps the classes the training run loaded and linked instead of loading them again. It holds
class metadata, never the heap, so nothing from the build environment can end up in it.

Measured on arm64 (Apple silicon, Docker, `--cpus=4`), three runs each:

| | Before | Cache off, new image | AOT cache |
| --- | --- | --- | --- |
| backend `Started Application in` | 5.9–6.5s | 5.7–5.9s | **2.7–3.0s** |
| software-factory `Started FactoryApplication in` | 2.0–2.3s | 1.9–2.0s | **0.8s** |

On the Pi the backend took 60–90s and software-factory 38–48s (Loki, early October 2026).
**Re-measure there after the first deploy.** The Mac numbers show the ratio, not the Pi's
absolute times.

### backend (buildpack)

`bootBuildImage` sets `BP_JVM_AOTCACHE_ENABLED=true`. The buildpack starts the app with
`-Dspring.context.exit=onRefresh`, which refreshes the whole context and exits before anything
starts. No datastore exists in the build, so the training run uses the **`aot-training`**
profile (`SPRING_PROFILES_ACTIVE` in the build environment) to replace the few beans that
contact one while being *created*:

| Bean | Why | Replaced by |
| --- | --- | --- |
| Spring AI `ElasticsearchVectorStore` | `afterPropertiesSet()` always asks whether the index exists; `initialize-schema: false` does not avoid the call | an in-memory `SimpleVectorStore` (`AotTrainingConfiguration`), plus excluding its auto-configuration, which backs off only for its own type |
| `BlogSearchRepository` | Spring Data checks the `@Document` index exists | an inert proxy, with ES repository scanning off |
| Mongock's driver | checks its lock/changelog indexes | `mongock.enabled: false` |
| Embabel `OpenAiModelsConfig` | throws without an API key | a literal placeholder key |

The profile replaces the site's search store, so it must never reach a running container.
Build-time variables are not persisted into a buildpack image, and **Publish asserts it** on
every built image: no `SPRING_PROFILES_ACTIVE` in the image config, and a non-empty
`/workspace/application.aot`.

When a new bean starts contacting a datastore during creation, the image build fails at the
training run with that bean's stack trace. Add its stand-in to `AotTrainingConfiguration` or
`application-aot-training.yml`. To iterate without the buildpack, run the jar with
`-Dspring.context.exit=onRefresh -XX:AOTCacheOutput=/tmp/app.aot` and
`SPRING_PROFILES_ACTIVE=aot-training` in an `eclipse-temurin:25-jre` container with no network
neighbours.

### software-factory (Dockerfile)

`Dockerfile.software-factory` extracts the jar (an AOT cache cannot cover jars nested in a fat
jar), trains with `-Dspring.context.exit=onRefresh`, and writes `/app/app.aot`. That needed no
profile: nothing in that context contacts a service while being created. CI's image build runs
the training too, so a regression fails the pull request.

### `-Xshare` and the cache cannot be combined

**The JVM refuses to start when `-XX:AOTCache` meets any `-Xshare` option**:

```
Option AOTCache cannot be used at the same time with -Xshare:on, -Xshare:auto, -Xshare:off, ...
```

All three JVM services carried `JAVA_TOOL_OPTIONS=-Xshare:off` from #55, to avoid a JDK 21.0.11
aarch64 G1 SIGSEGV populating CDS archive regions. The runtime is JDK 25 now.

| Service | Now | Turn the cache off |
| --- | --- | --- |
| `backend` | `BPL_JVM_AOTCACHE_ENABLED: ${BACKEND_AOT_CACHE_ENABLED:-true}` | `BACKEND_AOT_CACHE_ENABLED=false` in `.env`, recreate. **Never** add `-Xshare:off` alone: the buildpack still adds `-XX:AOTCache` and the backend will not start |
| `software-factory` | `JAVA_TOOL_OPTIONS: ${FACTORY_JAVA_TOOL_OPTIONS:-}` | `FACTORY_JAVA_TOOL_OPTIONS=-Xshare:off`, recreate. The image's `/app/start.sh` leaves the cache out whenever it sees `-Xshare` |
| `deployer` | still `-Xshare:off`, so `start.sh` skips the cache | changing it alters the deployer's config hash, and the deployer is outside the recreate allowlist, so every later deploy would be held back. Change it in a maintenance window |

**The one state that crashes:** the new backend image under a compose file that still says
`-Xshare:off`, i.e. a deploy whose `sync-config` was held back but whose images shipped anyway.
`verify` fails and the deploy rolls back. The change that introduced this touched only
allowlisted services, so its own rollout could not be held back. If it ever happens, apply the
held-back config (the decline prints the `manual-command=`). `BACKEND_AOT_CACHE_ENABLED` does
not help there: the old compose file never reads it.
