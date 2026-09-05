# simonrowe-dev-monorepo Development Guidelines

Last updated: 2026-04-12

## Technology Stack

- **Backend**: Java 21, Spring Boot 3.5.x, Gradle, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Security (OAuth2 Resource Server)
- **Frontend**: TypeScript, React (latest stable), Vite, MDXEditor, Lucide React, react-markdown
- **Persistence**: MongoDB 8 (primary), Elasticsearch (search), Kafka (async messaging)
- **Auth**: Auth0 (OAuth2/JWT)
- **CSS**: Plain CSS with BEM naming, single `styles.css` file, CSS custom properties for theming

## Project Structure

```text
backend/           # Spring Boot application
  src/main/java/   # Java source (com.simonrowe.*)
  src/test/java/   # Tests with Testcontainers
  uploads/         # Media asset storage
frontend/          # React + Vite application
  src/             # TypeScript source
  tests/           # Vitest tests
scripts/           # Bash scripts for backup, restore, migration
```

## Commands

```bash
# Start/stop applications (sources env vars from .env files)
./scripts/start.sh                      # Start both backend and frontend together
./scripts/stop.sh                       # Stop both backend and frontend
./scripts/start-backend.sh              # Start backend only (port 8080)
./scripts/start-frontend.sh             # Start frontend only (port 5173)

# Tests
cd backend && ../gradlew test           # Run backend tests
cd frontend && npm test                 # Run frontend tests (vitest)

# Backup & Restore
./scripts/backup.sh                     # Create backup to /Users/simonrowe/backups/
./scripts/restore.sh                    # Restore latest backup

# Environment setup (run automatically by Conductor on workspace creation)
# Copies ~/workspace/simonjamesrowe/env to backend/.env and frontend/.env
```

## Code Style

- Java: Google Java Style Guide, enforced via Checkstyle
- TypeScript: Standard conventions, ESLint
- CSS: BEM naming, plain CSS with custom properties

## Key Design Decisions

- Blog tags/skills use MongoDB `@DBRef` references; admin API uses DTO pattern converting between `@DBRef` entities and string IDs for the frontend
- Admin CMS uses Lucide React icons for actions/status, right-side drawer for Media Library, two-column layout for blog editor
- Uploads served via Spring `ResourceHandlerRegistry` at `/uploads/**`, path configurable via `UPLOADS_PATH` env var (default: `uploads/` relative to backend CWD)
- `scripts/backup.sh` and `scripts/restore.sh` are the canonical data management scripts (legacy Strapi migration scripts retained for reference)

## Production Deployment

Production runs the full stack via `docker-compose.prod.yml` (project name `simonrowe-dev-monorepo`),
deployed from `~/workspace/simonjamesrowe/simonrowe-dev-monorepo` (needs a `.env` in that directory).
It is exposed to the internet by the `pinggy` service, which tunnels `nginx:80` out to Cloudflare
(Cloudflare → pinggy tunnel → the `nginx` container), so the stack can run on any Docker host.

- **Single `nginx:alpine` reverse proxy** (`config/nginx/nginx-proxy.conf`) fronts every public hostname:
  `www/simonrowe.dev → frontend:80`, `api.simonrowe.dev → backend:8080`,
  `console.simonrowe.dev → portainer:9000` (Portainer has **no** published port — only reachable through nginx),
  `langfuse.simonrowe.dev → langfuse:3000`, `temporal.simonrowe.dev → temporal-ui:8080`,
  `dependency-track.simonrowe.dev → dependencytrack-frontend:8080`
  (with `/api/` routed to `dependencytrack-apiserver:8080`).
- **nginx resolves upstreams at runtime, not just at boot** (fixed in commit `62d26cc`): the proxy conf sets
  `resolver 127.0.0.11 valid=10s ipv6=off;` (Docker's embedded DNS) and every `proxy_pass` target is a variable
  (e.g. `set $upstream_frontend frontend; proxy_pass http://$upstream_frontend:80;`), which forces nginx to defer
  DNS resolution instead of caching it at startup. As a result, **nginx now boots regardless of which upstreams
  are running**, and simply returns `502` for any hostname whose upstream is down or not yet started — restarting
  nginx no longer risks taking the whole stack (including Portainer, which sits behind the same nginx) offline.
  Historical context: before this fix, the proxy conf used static `proxy_pass http://<name>` with no resolver, so
  nginx resolved all upstream hostnames once at startup and aborted (`host not found in upstream`) if any of
  them were not running — that failure mode is what older incident reports referring to a "nginx restart gotcha"
  describe; it no longer applies.
- **nginx's healthcheck must stay upstream-independent.** It hits `/healthz`, served by a `default_server`
  block in `config/nginx/nginx-proxy.conf` that proxies to nothing. Do not point it back at `/`: with no
  `default_server`, a `Host: localhost` request falls through to the first block (`simonrowe.dev`) and proxies
  to `frontend`, so a stopped frontend marked nginx unhealthy — and because `pinggy` waits on nginx being
  `service_healthy`, the tunnel never started and *every* public hostname, Portainer included, went offline.
  Adding `default_server` does not change which block serves the five public hostnames: `server_name` matching
  takes precedence, and the default block's `server_name _` cannot match a real `Host` header.
- **`langfuse-db` (Postgres) is now a shared dependency of two tools**: it hosts both the
  `langfuse` database and, since Dependency-Track was added, a `dtrack` database (see
  `docs/runbooks/dependency-track.md`). Stopping or restarting `langfuse-db` takes down both
  Langfuse and Dependency-Track, not just Langfuse.
- **`software-factory`** (formerly `reviewer-api` + a `temporal-reviewer-worker` host service) is
  one container running the GitHub webhook receiver and the Temporal code-review worker in one JVM,
  with `git` and a pinned Claude Code binary baked into the image. There are no host prerequisites
  and no systemd unit — it is reconciled by `docker compose up -d` like everything else.
  Only `POST /webhooks/github` is routed by nginx (exact-match `location =`); the internal
  `/api/reviews` endpoints are unrouted *and* token-protected. It deliberately has no `env_file`.
  A container can be `healthy` while having registered no Temporal poller, in which case webhooks
  return `202` and nothing ever reviews — check pollers on the `code-review` task queue, not just
  the healthcheck. See `docs/runbooks/software-factory.md`.
  The same container also hosts the `cve-fix` task queue and, behind `FACTORY_CVEFIX_ENABLED`
  (default `false`), a **paused-by-default** 24-hour Temporal schedule (`cve-fix-daily`) declared in
  code so a deploy reconciles it; enabling the flag deliberately does not start opening pull
  requests until an operator unpauses it after a dry run. That flow builds nothing locally — the
  agent has no `Bash` tool and the image carries no Gradle/Node/Docker, so CI is the only build
  environment and the repair loop is a CI poll. It adds no HTTP route. See
  `docs/runbooks/cvefix.md`.
- **Self-healing watchdog:** `scripts/monitor-prod.sh` runs from cron every minute
  (installed by `scripts/install-prod-monitoring.sh`, logs to `/var/log/prod-health/monitor.log`).
  It checks the site, then every container's Docker health, then each public hostname, and
  remediates at the narrowest level that fits. **Test changes to it with `DRY_RUN=1` and a
  throwaway `STATE_DIR`** — every remediation path shells out to `docker compose`, so merely
  running it performs real restarts and can recreate containers if the compose file has been
  edited since the last deploy. Key fact it exists to work around: **Docker never restarts an
  `unhealthy` container** — `restart: unless-stopped` only fires on process *exit*, so a
  container that is up but failing its healthcheck stays broken forever unless something
  external restarts it. See `docs/runbooks/prod-monitoring.md`.
- **A `healthy` container is not proof a service is serving.** On 2026-08-14 a host reboot
  cold-started all 21 containers at once and two came back broken *and invisible* for 10 days:
  `dependencytrack-apiserver` reported `healthy` while its API port was dead (its healthcheck
  probed only the management port on 9000; the JVM survived a `NoClassDefFoundError` that
  killed the Jetty listener, so it never exited and was never restarted), and `langfuse` had
  **no healthcheck at all** while every page 500'd on a broken `next-auth` module — its API
  routes stayed 200, so a health-endpoint probe would have missed it. Both were fixed by a
  plain `docker restart`; neither was an image problem. `langfuse`, `langfuse-worker`,
  `temporal-ui` and `dependencytrack-frontend` now have healthchecks, and the apiserver probes
  its API port too. **After any reboot, curl the public hostnames — do not trust a green
  `docker compose ps`.**
- **The single-node Kafka broker needs every internal topic at replication-factor 1.**
  `docker-compose.prod.yml` set no `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`, so the broker
  used Kafka's default of **3**. With one broker registered, `__consumer_offsets` can never
  be created, so there is **no group coordinator**: `FIND_COORDINATOR` times out and *no
  consumer group can ever join*. Producers are unaffected, so the symptom is messages
  published and silently never consumed — on 2026-08-25 all 13 `@KafkaListener` consumers in
  prod were inert (13 subscribed, **zero** broker rebalances), which surfaced as narration
  stuck on "Preparing audio" forever for both blogs and article summaries.
  `docker-compose.yml` already carried the three settings and the comment explaining them;
  they had never been ported to prod. Two things this cost time on: the
  `kafka-broker-api-versions` healthcheck **stays green throughout** (it never exercises
  group coordination), and **fixing the broker is not enough** — existing consumers do not
  recover from a long `FIND_COORDINATOR` backoff, so `restart backend` after the broker is
  healthy. Diagnose with `kafka-topics --list` (is `__consumer_offsets` there?) and
  `kafka-consumer-groups --list` (empty = nothing has ever joined); use `kafka:29092`, not
  `localhost:29092`, or the CLI's own FIND_COORDINATOR masks the answer.
- **The kernel memory cgroup is disabled, so every `mem_limit` is unenforced.** `docker info`
  warns `No memory limit support` and `docker stats` reports `0B / 0B`. The Raspberry Pi
  *firmware* prepends `cgroup_disable=memory`; it is in `/proc/cmdline` but in **no file under
  `/boot`**, so trying to delete it from `cmdline.txt` (as older docs advised) cannot work —
  you *append* `cgroup_enable=memory cgroup_memory=1` instead, which is what
  `scripts/enable-memory-cgroup.sh --apply` does. Needs a planned reboot. Second-order effect
  while it is off: JVMs size their heap from the host's 15.84GiB rather than their container
  limit (Dependency-Track's `-XX:MaxRAMPercentage=80.0` implies a ~12.7GiB heap against a
  declared `mem_limit: 2g`). The compose file now declares limits/reservations for 17 services,
  sized with ~2x headroom over measured PSS; they are inert until that reboot, and applying
  them recreates ~17 containers — so do it in the same maintenance window.
- **The backend healthcheck budget is tight for a reason.** `/actuator/health` aggregates
  Elasticsearch, Kafka, Mongo, mail and SSL, and the Kafka indicator builds a fresh AdminClient
  per call: measured ~9s on the Pi while returning `{"status":"UP"}`. It previously allowed only
  4-5s, so it marked a healthy backend unhealthy, which made `up -d` abort with
  "backend is unhealthy" and strand `frontend` in `created` (502 on www). That is the real cause
  of the "just re-run restart-prod.sh" folklore. Now `interval: 30s`/`timeout: 25s`. To rescue a
  stranded frontend without waiting on the dependency gate:
  `docker compose -f docker-compose.prod.yml up -d --no-deps frontend`.
- **A healthcheck with no `start_period` has a total cold-start budget of
  `retries x interval` — that is the whole grace period, not a per-probe timeout.**
  Failing probes during boot count against `retries`, so a slow-booting service is
  declared `unhealthy` before it can answer even once. On 2026-08-25 this broke a
  `restart-prod.sh` run: `elasticsearch` needs **~130s** on the Pi to bind 9200 but had
  no `start_period`, so its budget was `5 x 10s = 50s`. Compose aborted with
  `dependency failed to start: container ... elasticsearch-1 is unhealthy` and left
  **both `backend` and `frontend` in `created`** — 502 on www *and* api. The probe itself
  costs ~60ms once ES is up, so `timeout` was never the problem. `mongodb`, `kafka` and
  `elasticsearch` all gate `backend` on `service_healthy` and now carry
  `start_period` (60s / 180s / 300s) sized at ~2x measured boot (20s / 75s / 130s) —
  note `kafka`'s old budget was *exactly* its 75s boot time, so it was passing by luck.
  A `start_period` is free when boot is fast: the first successful probe ends it early.
  Recovery needs no special action — once ES is healthy, a plain `up -d` starts both
  stranded containers (`monitor-prod.sh` does this within a minute).
- **`deployer`** is a second instance of `FACTORY_IMAGE` with no ingress, holding
  `/var/run/docker.sock` and a **read-write** mount of the deploy directory. It executes deploys
  off the `deploy` Temporal queue and is the only container permitted to run
  `scripts/restart-prod.sh`'s host-mutating phases. It excludes itself from
  `FACTORY_DEPLOY_SERVICES` and `FACTORY_DEPLOY_RECREATABLE`, so it never recreates itself — and
  therefore must be updated by hand. `backend` no longer holds the Docker socket, the compose
  file or `.env`, which is the largest single security improvement in that change.
- **nginx serves themed maintenance/unavailable pages** from
  `config/nginx/maintenance/*.html` (bind-mounted, all CSS inlined, no external asset — the
  frontend that would serve those assets is what is down). The maintenance page is driven by
  `/var/run/deploy-state/maintenance.on` in the `deploy-state` volume, read-write on `deployer`
  and **read-only** on `nginx`. Deliberately outside the flag: `/healthz` (failing it marks nginx
  unhealthy and `pinggy` waits on that, taking every hostname offline),
  `POST /webhooks/github`, and the four ops hostnames.
- **Recover a downed/partial stack** from the deploy directory: `docker compose -f docker-compose.prod.yml up -d`
  (reconciles containers stuck in `created`, respecting `depends_on` ordering). Minimal alternative:
  `docker start simonrowe-dev-monorepo-langfuse-1 && docker start simonrowe-dev-monorepo-nginx-1`.
- Containers left in Docker `created` state (built but never started) after an interrupted
  `docker compose up` are a common failure mode — a stranded `frontend` means `502` on www.
  (The old explanation, "nginx keeps serving with a stale cached upstream IP", no longer
  applies: since `62d26cc` nginx resolves upstreams per request, so a `502` here means the
  upstream really is not running. `monitor-prod.sh` now detects `created`/`exited` containers
  and reconciles the stack, and no longer bounces nginx for DNS reasons.)
- **Pinggy tunnel:** one `PINGGY_TOKEN` = one active tunnel. If another host still holds it you get
  `A tunnel with the same token is already active`; reclaim it by setting `PINGGY_TOKEN=<token>+force`
  (the `+force` suffix terminates the stale session). The token maps to the `*.simonrowe.dev` custom domain.
- **Running prod on macOS/OrbStack for testing:** the backend bind-mounts the docker CLI via
  `DOCKER_BINARY_PATH`/`DOCKER_PLUGINS_PATH`, whose compose defaults (`/usr/bin/docker`,
  `/usr/libexec/docker/cli-plugins`) don't exist on macOS — set them in `.env` to
  `/opt/homebrew/bin/docker` and `~/.docker/cli-plugins`. nginx/portainer publish no host ports
  (all ingress is via the pinggy tunnel), so there are no conflicts with other local stacks.

## Recent Changes
- 044-factory-flow-console: `/admin/software-factory` replaces its seven module cards with a
  twelve-node loop diagram, `com.simonrowe.factory.flow`. **No module gained persistence** — every
  count and drawer list is read live from Temporal visibility (`WorkflowCountsReader`,
  `CountWorkflowExecutions`/`ListWorkflowExecutions` per workflow type) or, for the four artifact
  nodes, from `linear_issues` and GitHub's REST API (`ArtifactCountsReader`). That is also why
  `codereview` — the one module with no run collection of its own anywhere in Mongo — is countable
  at all: Temporal's own visibility store answers for it exactly as well as for the other five.
  Load-bearing bits:
  - **`linear` is an artifact, not a module box.** It is the factory's only activity-only task
    queue — nothing flows *through* it — so it carries the `linear` module's health as a badge on
    the `linear` artifact node via `NodeDescriptor.moduleKey`, rather than being drawn as a
    seventh box. `platformbackup` is the opposite deliberate omission: it sits off the ring on
    `Band.UTILITY` and is on **no edge at all**, pinned by
    `FactoryFlowTopologyTest.leavesPlatformBackupOffTheRing` — it has nothing downstream inside
    this factory, and drawing it on a loop would assert a feedback path that does not exist.
    `FactoryFlowTopologyTest` pins the exact twelve node keys and the topology's internal wiring,
    and `moduleKeysOnNodesMatchModulePrerequisitesExactly` now cross-checks `NODES` against
    `ModulePrerequisites.KEYS` in both directions — a key in `ModulePrerequisites.KEYS` carried
    by no node means a module was added without being drawn into the graph, a node `moduleKey`
    matching no real key means a typo that would leave that node's health permanently unknown,
    and both failure messages name the offending keys. At Task 1, before this test existed, a
    real module/node mismatch like this was caught only by a reviewer reading both lists side by
    side; a future module now fails the build on its own if it is forgotten.
  - **Several state pairs are decided separately on purpose and must never collapse**: `IDLE`
    (nothing to do) vs `OFFLINE` (work waiting, nothing listening) for the `build` node;
    `NOT_TRACKED` (no source of live data at all — only `production` today, which is reported by
    `/api/platform/status` instead of duplicated here) vs an unconditional `READY`; a null
    `NodeCounts`/`FlowDetail.items` (the source could not be read) vs zero/an empty list (it was
    read and genuinely found nothing). The frontend carries the same three-way split to the pixel —
    "Counts unknown", a visible error banner, and a node-specific empty message ("No open
    tickets.") are three different renders of `FactoryNodeDrawer`, not one collapsed "nothing
    here". Collapsing any of them reproduces the exact bug a reviewer caught mid-implementation: a
    deployer that could not be reached rendered byte-identical to a deployer with a genuinely quiet
    30 days, directly under a counts panel proving otherwise.
  - **`GET /api/factory/flow` is unauthenticated; `GET /api/factory/flow/{nodeKey}` is not — a
    mid-implementation reversal of the spec, not the original design.** The list endpoint returns
    only node keys, counts and `diagnostic` strings of the same disclosure class
    `/api/factory/status` already serves openly from both containers; the *detail* endpoint is the
    one that actually carries ticket subjects and pull request titles
    (`FlowDetail.Item#title()`), and lives in a **separate controller class** for exactly the
    reason `FactoryStatusController` documents elsewhere: `FactoryTokenAuthenticator` is a plain
    `@Component` each protected controller calls for itself, not a Spring Security filter, so a
    `@GetMapping("/{nodeKey}")` added to the unauthenticated controller would silently inherit its
    posture instead of gaining a check. Token-protecting the list endpoint would have forced
    handing the socket-holding `deployer` — which owns the `deploy`/`platformbackup` nodes and
    holds no `FACTORY_TRIGGER_TOKEN` — a credential that also authorises the **seven** other
    trigger-protected controllers (`Review`, `Deploy`, `CveScan`, `PlatformBackup`, `LogWatch`,
    `Feedback`, `FactoryRun`), including the one that starts a deploy.
  - **The actual fix is a second, narrower token**, `FACTORY_READ_TOKEN`, checked by
    `authenticateRead` (never `authenticate`, never the trigger token) and accepted by exactly one
    endpoint. `deployer` now declares it and still declares no `FACTORY_TRIGGER_TOKEN`, enforced by
    a new `DeployerReadTokenConfinementTest` in the same compose-parsing style as
    `DeployerLinearCredentialTest`/`DeployerGrafanaCredentialTest`. It uses compose's `${VAR:-}`
    empty-default form, **deliberately not `:?`**: the variable does not exist in production `.env`
    yet, and an unset `:?` fails interpolation for the *whole compose file*, wedging `sync-config`
    and taking `monitor-prod.sh`'s minutely `up -d` down with it — the identical precedent
    `trivy-server`'s `--token` argument already established. **Operator action required**:
    `FACTORY_READ_TOKEN` must be added to the production `.env` before the `deploy`/`platformbackup`
    drawers show real run history; until then both correctly render "not available" rather than a
    misleading empty list.
  - **The `build` node is declared but unstaffed.** The build agent (`specs/045-build-agent/`) runs
    on a machine this server cannot reach, so its health is derived entirely from the open Linear
    backlog waiting for it. Recorded, not hidden: today that check keys off **any** open Linear
    issue rather than specifically `factory:build`-labelled work, and has no agent-liveness check
    at all — an unrelated open CVE ticket shows `build` as `OFFLINE`. Do not mistake this for
    finished 045 semantics.
  - **Accessibility**: every node renders as a real `<button>` in main-loop DOM order
    (`FACTORY_FLOW_ORDER`) with the SVG laid over it as `aria-hidden` decoration, so keyboard and
    screen-reader users traverse the same ring a sighted user sees. Below 50rem the SVG is dropped
    entirely and the buttons stack as the mobile layout, for free. The drawer traps Tab, moves
    focus to its heading on open, and restores it to the triggering button on close.
  - **A reciprocal-edge rendering bug survived a full review round looking correct.** The fast
    loop's two opposite-direction edges between `pull-request` and `codereview` computed the
    identical curve through a double sign flip that cancelled itself, rendering as one visible
    segment instead of two. The regression test that should have caught it only compared the two
    edges' SVG `d` strings for inequality — trivially true whenever two endpoints are textually
    swapped, so it proved nothing about the actual picture. Found only by a reviewer hand-computing
    control points. This diagram's geometry needs an eye on it, not just a green suite.
  See `docs/runbooks/software-factory.md` ("Factory flow console") and
  `specs/044-factory-flow-console/`.
- spring-boot-4-upgrade: Boot **3.5.16 → 4.1.1**, Java **21 → 25 LTS**, Gradle 9.7.1, Spring AI
  **2.0.1**, Embabel **1.5.1**, Jackson 3, JUnit 6, Spring Kafka 4, Testcontainers 2,
  Elasticsearch **9.4.5**, Mongock unchanged at 5.5.1. Backend 1160 tests and software-factory
  582 both match the pre-upgrade baseline. Full detail in
  `docs/runbooks/spring-boot-4-upgrade.md`; the parts that will bite again:
  - **4.1.1, not the 4.0.x the OpenRewrite recipe pins.** `embabel-agent-platform-autoconfigure`
    1.5.1 — the first Embabel line supporting Boot 4 at all — declares `spring-boot 4.1.0` and
    `spring-ai 2.0.0`, and `spring-ai-starter-model-openai:2.0.1` declares Boot 4.1.1. There is
    no Boot 4.0 configuration of this repo where both are on a supported release, and there is
    no `UpgradeSpringBoot_4_1` recipe — you run the 4.0 one and hand-bump afterwards.
  - **Mongock declares no Boot 4 support and works anyway.** Its POM upper-bounds Boot at
    `[3.0.0-RC1, 4.0.0)` and there is no `mongock-springboot-v4`, but those bounds sit on
    `provided`/`optional` deps that never reach our classpath. All 31 change units ran on Boot
    4.1.1 in the real app. **A green suite proves nothing here** — `application-test.yml` sets
    `mongock.enabled: false`, so the gate is running
    `*V011SeedAndBackfillDanVegaBlogIntegrationTest` by name and *reading* the `APPLIED` lines.
    A pass with no Mongock output means the override did not take.
  - **Java 25, deliberately not 26.** 26 is a short-term release that goes end-of-support on
    18 Sep 2026 and 27 is not an LTS either; the next LTS is 29, in Sep 2027. Three things move
    with the toolchain and none fail at compile time: JaCoCo below 0.8.14 cannot read Java 25
    bytecode (bare "Error while creating report"), `Dockerfile.software-factory` ran a 21-jre
    that cannot load the bytecode at all, and `bootBuildImage` now pins `BP_JVM_VERSION` rather
    than trusting the buildpack default — that one only fails at container start.
  - **The recipe never touched `gradle/libs.versions.toml`.** Every version here is behind a
    catalogue alias its `UpgradeDependencyVersion` / `MigrateToModularStarters` steps cannot see
    through, so Boot itself, the modular starter renames, Testcontainers, Spring AI and Embabel
    were all hand changes. Treat OpenRewrite as a **source-code** tool in this repo, not a
    dependency one. Its YAML edits were also reverted (it re-indented comment blocks away from
    what they document) and 13 cosmetic text-block conversions dropped, including two applied
    change units and the guardrail classifier prompt. Use plugin **7.39.0**: 7.40/7.41 need
    `rewrite-bom:8.91.0`, which Maven Central does not carry — the Code Genome migration
    starting to bite.
  - **Silently-ignored config is the theme of this upgrade.** Four separate instances, each
    of which starts cleanly and does the wrong thing: `spring.data.mongodb.uri` moved to
    `spring.mongodb.uri` (the old key is ignored and the driver falls back to
    `localhost:27017` — `docker-compose.prod.yml` set the env-var form, so production would
    have done exactly that); `spring.autoconfigure.exclude` entries naming classes deleted by
    the Spring AI merge; the tracing keys, where `.endpoint` moves to
    `management.opentelemetry.tracing.export.otlp` but `.export.enabled` moves to
    `management.tracing.export.otlp` — genuinely two prefixes, not a mistake; and the logback
    one below. **Verify property renames against the shipped jar's
    `spring-configuration-metadata.json`, not a migration guide.**
  - **Console logging died completely and no test could see it.** `logback-spring.xml` selected
    its appender with an `<if condition="...">` attribute, which Logback 1.5.38 (Boot 4.1.1, up
    from ~1.5.18) deprecated **and ignores** — no CONSOLE appender is created and the app logs
    nothing at all. Deleting the file is not the fix either: with no logback config Boot honours
    `logging.pattern.console` but **not** `logging.structured.format.console`, so prod loses its
    JSON. Now pure property substitution, with the plain case coming from a `:-` default because
    Logback rejects `<property value=""/>`. Janino is gone with the conditional. The Gradle
    suite cannot cover any of this — `backend/src/test/resources/logback-test.xml` takes
    precedence — so `scripts/test/test-backend-console-logging.sh` guards it instead.
  - **Elasticsearch 8.17 → 9.4.5 is forced and operator-facing.** Boot 4.1.1 manages
    `elasticsearch-java` 9.4.5 and a 9.x client refuses an 8.x server outright ("status: 400 ...
    Expecting a response body, but none was sent"). Take the `content-embeddings` backup before
    recreating that container: the search indices rebuild from Mongo, the vectors cost real
    money. Keep the four pinned copies (both compose files, `evals.yml`, `ApplicationTests`) in
    step; `ProdImageCatalogTest` asserts the compose tag.
  - **Jackson 2 and 3 coexist and both are needed.** In Boot 4 `jackson-bom.version` means
    Jackson **3** (Jackson 2 moved to `jackson-2-bom.version`), so the old SIM-9 override would
    have pinned Jackson 3 to a version that does not exist; five of the six `ext[...]` overrides
    were deleted because Boot now ships at or above them, and `commons-lang3` would actively
    have downgraded. `jackson-annotations` keeps its `com.fasterxml` groupId. The Elasticsearch
    client is still Jackson 2, so `ElasticsearchJsonpMapperConfig` builds its own mapper and
    must re-add JSR-310 and ISO-8601 dates by hand. And Jackson 3 turns
    `FAIL_ON_NULL_FOR_PRIMITIVES` **on** by default, so every request body omitting a primitive
    began returning 400 — restored globally, plus `ReviewRequest.publish` boxed and normalised
    in its own compact constructor, because "an omitted flag means post nothing" is a safety
    property and its test runs standalone where the global setting does not apply.
  - **Boot 4 split tracing auto-configuration out.** `micrometer-tracing-bridge-otel` still
    supplies `OtelTracer` but nothing builds the `Tracer` bean from it;
    `spring-boot-micrometer-tracing-opentelemetry` is now an explicit dependency. Without it the
    Langfuse pipeline goes silent. The narrow module, not
    `spring-boot-starter-opentelemetry`, which would stand a second metrics registry beside
    Prometheus.
- 042-factory-log-watch: A seventh Software Factory module, `com.simonrowe.factory.logwatch`, on a
  new `logwatch` Temporal task queue. Reads `ERROR`/`WARN` container logs from Grafana Cloud Loki,
  reduces each line to a signature invariant to timestamps, UUIDs, hex ids, paths, addresses and
  numbers, drops signatures occurring fewer than twice, sorts most-severe-first, caps at five per
  run, and files each one through the existing `linear` sink. Dedup, cancel-to-suppress and
  reopen-to-re-arm are entirely the sink's — there is no logwatch-side state for them, deliberately.
  Off by default; schedule created **active** (unlike cvefix's paused-by-default, because a
  read-and-file scan cannot damage anything and a paused observability check is one nobody turns
  on). Load-bearing bits:
  - **An empty read is not a clean read, and the module says so.** Source health is established
    *before* anything is interpreted, and an unusable source reports `SOURCE_UNHEALTHY`, never
    `NO_FINDINGS` — separate enum values, so the distinction is structural rather than a log
    message. This is drawn from the August 2026 outage rather than from design: Grafana Cloud
    accepted nothing for three weeks while `alloy` stayed `Up (healthy)` (its healthcheck is
    `alloy --version`, which passes while every batch is dropped) and reads kept returning
    `{"status":"success"}` with an empty body, because **ingest and query are separately gated**.
    A module without this check would have filed nothing and been self-consistently correct every
    night. Two tiers: Alloy's component API on `:12345` (direct — it reports the actual `429`
    text, which separates an exhausted quota from a rejected credential from a quiet stack), then
    container-coverage inference. **Coverage is not applied to windows under an hour**, or a
    five-minute post-deploy scan over an idle stack would file a ticket after every quiet deploy.
  - **A source-health failure is filed as an ordinary finding** through the same sink, key parts
    `["source-health", <status>]` — inheriting dedup and suppression with no new mechanism. Key
    parts exclude the evidence string on purpose: a `429` whose byte counts differ every run stays
    one ticket, while a quota problem and a credential problem stay separate.
  - **`GRAFANA_CLOUD_LOKI_ENDPOINT` is the *push* URL and already contains `/loki/api/v1`.**
    `LokiClient.queryBase()` strips the trailing `/push`; appending `/api/v1` to the raw value
    gives `/loki/api/v1/api/v1/...` and a bare `404 page not found` with no JSON and no hint. Loki
    timestamps are **nanoseconds** — a seconds value is accepted and silently returns empty for a
    window fifty years wide in the wrong place, which is the exact shape this module must not read
    as clean.
  - **The credential is confined by one annotation**, `LogWatchActivitiesImpl`'s class-level
    `@ConditionalOnProperty`, evaluated by the component scanner — declaring the class through an
    explicit `@Bean` would register it unconditionally and silently ignore it. Both containers do
    register a *workflow* poller on the queue (`@WorkflowImpl` scanning is unconditional); that is
    harmless and must not be "fixed". `DeployerGrafanaCredentialTest` reads the compose file and
    fails the build if any variable **containing** `GRAFANA` appears under `deployer`, because the
    Java gate alone does not stop a future compose edit — same reasoning, and now a shared
    `testsupport/ComposeFile` helper, as `DeployerLinearCredentialTest`.
  - **The level word is part of the signature**, so `WARN slow query` and `ERROR slow query` are
    two problems. A varying status code *does* collapse, deliberately: "the send failed with a
    status" is one problem whose status varies, and the example line carries the real code.
  - Fixtures are real production lines captured with `docker logs` on the Pi, **not** from Loki,
    which held nothing while this was written. The signature rules and the occurrence thresholds
    are therefore still estimates — dry-run and tune before trusting them.
  **The admin console row shipped separately** — a Log watch panel on `/admin/software-factory`
  with **Dry run scan** and **Scan logs now**, proxied through `POST /api/admin/software-factory/
  log-scans`. Three things that were not obvious: `FactoryAdminService.ORDER` is the authoritative
  module list on the backend side, so a module missing from it is dropped from the console
  entirely no matter what the factory reports; `LogWatchScanAccepted`'s field had to be renamed
  `message` → `detail` to match `CveScanAccepted`/`PlatformBackupAccepted`, because the backend
  proxies all three through one `RunAcceptedWire` and a differently-named field deserialises as
  null; and the button labels are "Dry run **scan**" / "Scan **logs** now" because a bare
  "Dry run" collides with platform backup and "Scan now" with the vulnerability scan — the
  accessible name is all a screen reader gets, and a test pins that each stays unique.
  `actionFor` in the console was also converted from an if-chain ending in a fallthrough to a
  total switch: the old form silently labelled any unrecognised module "Dry run / backup".
  **The post-deploy trigger completes the spec** (FR-011/FR-012): a successful deploy schedules a
  scan five minutes later, over the window from deploy completion. **Two flags, and both belong on
  `software-factory`, for different reasons.** `factory.logwatch.enabled` registers the
  Loki-reading activity and must never reach the socket-holding `deployer`;
  `factory.deploy.log-watch-trigger-enabled` is read by `DeployWorkflowService` when it **builds
  the DeployRequest**, and that runs on `software-factory` because that container terminates the
  signed webhook. **Putting the trigger flag on `deployer` looks right and makes the feature
  permanently inert** — the flag the code reads stays at its `false` default and no scan is ever
  scheduled, with no error anywhere. That is exactly the `FACTORY_DEPLOY_TRIGGER_ENABLED` mistake
  from 036 repeated one variable over; the reviewer caught it on #146 before merge.
  `DeployerGrafanaCredentialTest` asserts the deployer carries **neither** flag and that
  `software-factory` carries the trigger one. **The Linear flag is passed through from
  the deploy request rather than read on the deployer**, which holds no `FACTORY_LINEAR_ENABLED`
  by design — reading it locally resolves to `false`, so every post-deploy scan would run and
  file nothing, silently. It cannot fail a deploy: the flag is checked before scheduling (an
  unguarded schedule on an unpolled queue stalls until schedule-to-close), it runs on the `fast`
  stub, and every failure is appended to the deploy detail rather than rethrown. Only
  `DEPLOYED`/`DEPLOYED_IMAGES_ONLY` schedule anything — a rollback's window would describe the
  rollback rather than the change. `DeployProperties`' new flag is **last in the record** so
  adding it appended to the eight positional test call sites rather than inserting into them.
  See `docs/runbooks/logwatch.md` and `specs/042-factory-log-watch/`.
- log-shipping-quota-exhaustion: Grafana Cloud Loki held **nothing for three weeks** in August
  2026 while `alloy` reported `Up (healthy)` with `RestartCount: 0`, was tailing containers
  correctly, and the read credential kept working. Every batch was rejected with
  `status=429 ... ingestion rate limit exceeded for user 1539009 (limit: 0 bytes/sec)` — the
  calendar-month free-tier allowance (50 GB) was spent, 55 GB used, and Grafana Cloud's free plan
  responds by setting tenant ingest to **zero** for the rest of the period rather than throttling
  or billing. Three things hid it: the healthcheck is `alloy --version`, which passes while every
  batch is dropped; **ingest and query are separately gated**, so a query returned
  `{"status":"success"}` with an empty body and read as "the stack is quiet" (the wrong-tenant
  control test proves the *credential* is fine and says nothing about write); and nothing watches
  for it. **The allowance resets by itself at 00:00 on the 1st — logs reappearing then is not
  evidence anything was fixed.** Measured steady-state shipped volume is **~20 MB/day =
  0.58 GB/month**, about 1% of the allowance, so spending it took ~100x amplification. Two
  mechanisms supplied it:
  - **Alloy's read cursors were ephemeral.** `loki.source.docker` keeps one cursor per container
    in `--storage.path`, which had **no volume** and so resolved to the container's writable
    layer. `alloy` is in `FACTORY_DEPLOY_RECREATABLE`, so **every deploy destroyed the cursors
    and re-tailed every container from the start**, re-shipping the whole accumulated history of
    the stack — then again on the next deploy. Nothing logged an error; the entire cost landed as
    ingested bytes. Fixed with the `alloy-data` named volume.
  - **No log rotation anywhere.** Every container is `json-file` with an **empty** options map
    (`docker inspect ... LogConfig.Config` → `map[]`), there is no `logging:` block in the compose
    file and `/etc/docker/daemon.json` did not exist, so logs grew unbounded for the life of a
    container (mongodb: 250 MB after 67 hours). That is what made the re-read expensive rather
    than merely wasteful. Fixed by `scripts/enable-docker-log-rotation.sh --apply`
    (`max-size=20m`, `max-file=5`) — **host-side, and it needs a maintenance window**, since
    `systemctl restart docker` cold-starts all 22 containers, and the cap applies at container
    *creation* so existing containers stay uncapped until recreated.
  **Rotation is deliberately NOT a `logging:` block in the compose file**, which is the tempting
  version and wedges production: `logging:` changes a service's `config --hash`, `sync-config`
  compares those against the nine-service `FACTORY_DEPLOY_RECREATABLE` allowlist, and rotation has
  to cover all 22 — so it would decline as `held-back` and freeze the deploy directory
  self-perpetuatingly, the #130-through-#136 wedge. `daemon.json` changes no service hash.
  `scripts/test/test-log-shipping.sh` (in the `run-tests.sh` suite, so inside the required
  `Software Factory Build & Test` check) asserts the volume, that `alloy` is in the recreate
  allowlist, and that **no** `logging:` block comes back. Note `config/alloy/config.alloy` already
  drops `kafka|mongodb|frontend|langfuse-db` from shipping — mongodb alone is 90 MB/day and never
  reached Loki. **Still open: nothing detects that shipping has stopped**, and the `logwatch`
  module in `specs/042-factory-log-watch/spec.md` would have reported this outage as *zero
  findings — all clear*, since an empty query and a healthy system are indistinguishable to it as
  specified. See `docs/runbooks/log-shipping.md`.
- 043-dependency-track-os-packages: `simonrowe-dev/frontend-image` showed **Risk Score 0** while
  carrying 20 fixable Alpine findings (2 HIGH) on openssl, and `backend-image` showed 25 findings
  on the one Ubuntu package NVD happened to match where a distro-aware scan finds 242. Nothing was broken and nothing logged an error: **no enabled vulnerability
  source could match `pkg:apk/*` or `pkg:deb/*` at all**, so a container project's `0` meant
  "unscannable", not "clean". Verified live: OSV mirrored only `npm, Go, Maven, NuGet, PyPI`; the
  `nvd` source was **disabled** (its data frozen at 2026-08-25) and is what the only two distro
  findings ever reported — `openssl` and `perl`, the rare packages whose Ubuntu *source* name
  happens to be a real NVD `vendor:product` pair — over-reported, because CPE matching cannot
  model Canonical's backports. Fixed with a `trivy-server` container plus a one-time analyzer
  enable. Load-bearing details:
  - **Adding `Alpine`/`Ubuntu` to the OSV ecosystem list is NOT the fix**, though it looks like the
    cheap one. OSV's distro records are keyed on the **source** package (`purl` carries
    `arch=source`) while an SBOM lists **binary** packages: 69 of 99 debs in `backend-image` have
    a differing source name (`libssl3t64`→openssl, `libc6`→glibc), and 33 are vulnerable *only*
    via that name. Name-only matching misses all of them, for a large permanent mirror on the Pi.
  - **`publish.yml` generates the three image SBOMs with trivy, not `anchore/sbom-action`, and
    that is the whole mechanism — not a tooling preference.** DT reads an OS package's source name
    from the `aquasecurity:trivy:SrcName` **component property** and falls back to the purl's
    binary name when absent (`TrivyVulnAnalyzer.processOsPackage`); only trivy emits it, and
    syft's equivalent `upstream=` purl qualifier is never read. Measured against one trivy server,
    same image: trivy SBOM **20** findings, syft SBOM **0**. Reverting that step turns OS coverage
    off and the symptom is `0`, not an error. A new "assert the SBOMs are not empty" step guards
    the adjacent failure, since an empty BOM uploads fine and also reads as clean.
  - The OS is resolved by a *different* route that works with either tool: DT keys the scan blob
    on `<PkgType>-<distro>` and matches it against the `operating-system` component, so
    `alpine-3.24.1`/`ubuntu-24.04` line up. Don't tidy that component out of the BOM.
  - **The DT-side toggles are runtime config in Postgres, not deployment config** — the analyzer
    reads them via `getRuntimeConfig`, so no `DT_*` env var can set them and **a deploy cannot
    reconcile them**. `apiToken` is `x-secret-ref: true`: it holds the *name* of a DT secret, not
    the value. `trivy-server` was added to `FACTORY_DEPLOY_RECREATABLE` in the same commit, as a
    precaution against the self-perpetuating wedge from #130 — **and that precaution does not
    work**, verified in production on 2026-08-31. The allowlist reaches `sync-config` as an
    environment variable on the **running deployer**, rendered from whichever compose file existed
    when that container was last created, so the incoming commit's list is not the one consulted.
    #140 was held back by `trivy-server` despite listing it, #141 was held back for the same
    reason, and `trivy-server` was never created at all — meaning this entire fix sat dead on the
    host for a day while the images tracked `main`. Only recreating the deployer clears it. See
    "A decline does not clear itself" in `docs/runbooks/deploy.md`.
  - Its `--token` uses `:-` with a default, **deliberately not `:?`**: an unset required variable
    makes the whole compose file fail to interpolate, which both wedges `sync-config` and breaks
    `monitor-prod.sh`'s minutely `up -d`, taking the watchdog down. What it protects is not really
    a secret (no ingress, public DB); DT just refuses to be configured without one.
  - Expect the portfolio to go from ~107 findings to ~500. That is the measurement starting to
    work, not a regression — 228 of `backend-image`'s 247 have fixes and are discharged by
    rebuilding on a current base image, not by 300 triages.
  - **These findings do not reach Linear**, and that is now a live gap rather than a moot one.
    `cve-report-project-attribution` (below) scopes the nightly report to
    `simonrowe-dev/backend` and `simonrowe-dev/frontend`, calling the three image projects out
    of scope because "their findings are base-OS packages a manifest edit here cannot fix" —
    true, and the reason they were also unmeasured. They are measured now, so the ~356 OS
    findings live only in the Dependency-Track UI. Widening
    `factory.cvefix.dependency-track.projects` (a list, defaulted in `CveFixProperties` rather
    than set in compose) is the obvious follow-up, but the report renders per-component upgrade
    advice that does not apply to a base image, so it needs a different presentation, not just
    another project key.
  - **Tracked, not fixed:** the `internal` analyzer reports every Go advisory whose fixed version
    is a pseudo-version (`0.0.0-2019…`) against current modules, because it compares `0.58.0` as
    *older*. 21 phantom findings on `x/net@v0.58.0` (OSV: 0), ~40 of `backend-image`'s 69, and
    most of why the risk scores read 325/190. No config works around it and Trivy does not remove
    them — it adds a correct second opinion beside the wrong one. Also open: who disabled `nvd`,
    and why (undocumented, and its configured feed URL is the retired JSON 2.0 format).
  See `docs/runbooks/dependency-track.md` ("OS packages: why a container project's `0` did not
  mean clean").
- cve-report-project-attribution: The nightly CVE scan's one consolidated Linear ticket is now
  grouped by Dependency-Track project (`##` heading), most-severe-first within it, instead of one
  flat component list where two projects sharing a component silently merged into one entry. Only
  `simonrowe-dev/backend` and `simonrowe-dev/frontend` are in scope, while CI publishes three more
  image-SBOM projects that nothing reads — deliberately out of scope, since their findings are
  base-OS packages a manifest edit here cannot fix. A project with zero findings gets no heading at
  all, and that can only mean it is clean: `DependencyTrackClient.uuidFor` throws for a configured
  project absent from Dependency-Track, so a silently-skipped project is not a reachable state.
  **The dirty-to-clean transition now posts exactly one comment** on the long-lived ticket, gated
  on whether the *previous* scan found anything — never on every clean run, or a clean repository
  would collect one comment a night forever, and the sink's own replay guard cannot help here
  because it keys on the occurrence id (the run id), which differs every night. "Previous scan"
  excludes runs that never reached Dependency-Track (`CveFixRunRepository
  .findFirstByIdNotAndStatusInOrderByStartedAtDesc`, restricted to `COMPLETED`/`NO_FINDINGS`), so a
  single operational blip — Dependency-Track sharing `langfuse-db`'s Postgres and going down on its
  own — can't permanently swallow the transition by masquerading as a clean predecessor. This is
  the one change outside `cvefix`: the shared Linear sink (`com.simonrowe.factory.linear`, also
  used by `deploy` and `review-feedback`) gained `IssueFiling.commentOnly` and
  `FilingDecision.SKIPPED_NO_ISSUE` — a `commentOnly` filing never creates an issue and reports no
  issue reference when it finds nothing to comment on, so a newly-clean repository can never get a
  fresh "current vulnerabilities" ticket filed in its own name. Additive only: other producers
  leave the flag unset and are unaffected. See `docs/runbooks/cvefix.md` and
  `docs/runbooks/linear.md`.
- 042-share-links-404: Every `https://simonrowe.dev/s/<slug>` returned the SPA's themed 404 from
  the moment 041 shipped, for blogs and news/events alike, while
  `curl https://api.simonrowe.dev/s/<slug>` served a perfect Open Graph document. Two independent
  faults, and the second is the one to remember:
  - **`docker-compose.prod.yml` bind-mounted `./frontend/nginx.conf` over
    `/etc/nginx/conf.d/default.conf`, which `Dockerfile.frontend` already copies into the image.**
    The mount wins, so the container ran the deploy checkout's copy of the file regardless of the
    image CI had just built. The mount is **removed**; the image is now the only source. The
    `nginx` proxy keeps its mount and must — it is stock `nginx:alpine` with no image of its own.
    Diagnose this class of fault from the response headers alone: an `Etag`/`Last-Modified`/
    `Accept-Ranges` triple on a path that should be proxied means nginx served a file from disk,
    i.e. the request fell through to `location /`'s `try_files … /index.html`. Guarded by
    `scripts/test/test-frontend-nginx-shipping.sh` (in the `run-tests.sh` suite, so it is inside
    the required `Software Factory Build & Test` check).
  - **The deploy directory had been frozen since #130, and three deploys said "The site is up."**
    `deployer` is deliberately outside `FACTORY_DEPLOY_RECREATABLE`, so #130 — which added
    `FACTORY_RUNTIME_ROLE: deployer` to that service — made `sync-config` return `held-back` and
    leave `HEAD` alone. `SyncDecision.deployImagesAnyway()` is true for **every** decline, so the
    deploy pulled and recreated images anyway. **A held-back checkout is self-perpetuating**: the
    comparison is host-checkout vs. target, not previous-target vs. target, so `deployer` kept
    differing and #131 and #132 were held back for the same reason. Images tracked `main`; every
    host-side file (`docker-compose.prod.yml`, `config/nginx/`, `scripts/`) stayed at #129.
    It was invisible because **`DeployReportRenderer.partialDeployComment` was dead code** —
    referenced only from its own tests. `DeployWorkflowImpl.finish` posts a commit comment for
    exactly one status, `DEPLOYED_IMAGES_ONLY`, and rendered it with `commitComment`, which with
    no triage and no Linear URL emitted only its `siteState` line. `commitComment` now includes
    `partialDeployComment`, and says that later merges will be held back too. Handy corollary
    while triaging: a bot commit comment on a merge **is** the images-only signal, because a
    fully-applied deploy posts nothing — `gh api repos/.../commits/<sha>/comments`.
  Recovering a wedged checkout needs a human on the host, by design: run the
  `manual-command=` the phase prints (`docker compose -f docker-compose.prod.yml up -d <held-back>`)
  and then let the next deploy fast-forward. See `docs/runbooks/deploy.md`.
- 040-software-factory-console: A `/admin/software-factory` page in the site's own admin area,
  four modules switched **on** by default, and the CVE flow rewritten from "open a repair PR" to
  "file one Linear ticket". Three things about it are load-bearing:
  - **`GET /api/factory/status` is deliberately unauthenticated, and it has to stay that way.**
    The backend asks *both* `software-factory` and `deployer` for it, and the `deployer` holds no
    `FACTORY_TRIGGER_TOKEN` on purpose — token-protecting the endpoint would make the deployer
    report itself permanently unreachable, which disables the deploy and platform-backup actions
    with no configuration that recovers it. It returns booleans, queue names, poller counts and
    schedule times; `GET /api/factory/runs/{id}` next to it *does* require the token, because a
    run's `detail` is free-text diagnostics. Same reasoning as `/api/version`.
  - **Enabled is not the same as able to work, and four flags now default true while their
    credentials default empty.** `FACTORY_FEEDBACK_ENABLED`, `FACTORY_CVEFIX_ENABLED`,
    `FACTORY_LINEAR_ENABLED` and `FACTORY_PLATFORM_BACKUP_ENABLED` are all `true` in
    `docker-compose.prod.yml`; `LINEAR_API_KEY`, `FACTORY_LINEAR_TEAM_KEY` and
    `DEPENDENCYTRACK_API_KEY` are all still `${...:-}`. `ModulePrerequisites` is the one place
    that knows both, reports per-module `missingPrerequisites` on the status endpoint and logs
    them once at `ApplicationReadyEvent`. It never fails startup: a missing prerequisite must
    degrade one module, not take the factory down. A module's `ready` is the conjunction of flag,
    poller *and* prerequisites, and the backend refuses an action whose module is not ready —
    without that, a workflow started on a queue nothing polls does not fail, it sits in Temporal
    looking accepted until an activity timeout.
  - **One endpoint follows every module's runs**, because every factory workflow exposes a query
    method named `progress` returning `{phase, detail, <one module-specific field>}`. It is read
    as a `JsonNode` via an **untyped** stub: Temporal's `JacksonJsonPayloadConverter` does *not*
    disable `FAIL_ON_UNKNOWN_PROPERTIES` (verified in the 1.36.0 jar), so a typed read of one
    module's record throws on another's. Temporal's `executionStatus` and the workflow's `phase`
    are reported separately — a failed workflow cannot answer a query at all, and "it failed" is
    the most useful thing the page can say, so the query failing must not lose the status.
  **Code review gained a manual trigger after all** (the original cut had it status-only): the
  webhook builds its workflow id from the head SHA under `REJECT_DUPLICATE`, so the same commit
  can never be re-reviewed from GitHub — not after a failed review, and not after one whose
  webhook never arrived. The console sends **no `expectedHeadSha`**, which makes
  `ReviewWorkflowService` mint a UUID instead; that omission is the entire mechanism and a test
  pins it. The factory side needed no change at all. A **dry run posts nothing whatsoever** — no
  findings, no verdict, no failure notice — so its outcome is visible only in this page's run
  progress, which is what makes offering it reasonable.
  Also: the `deployer`'s status call deliberately sends no token; `deploy` and `platformbackup`
  are taken from the **deployer** and reported unavailable when it is unreachable, never from
  `software-factory`'s own (switched-off) view of them; and downstream statuses are translated
  rather than forwarded — 409 stays 409 ("already in progress"), a downstream 503 becomes "reports
  that module as disabled", 401/403 becomes 502, and only a real outage says "unavailable".
  Collapsing all of those into "unavailable" was the first cut, and it sent an operator looking for
  a down container when the answer was a flag.
  **CVE fix is no longer a fix.** `ClaudeCliFixEngine`, `CveFixPrGateway`, `CiStatusGateway`,
  `FindingSuppressor`, `UnfixableFindingRecord` and the git/branch/CI machinery are **deleted**
  (~4,300 lines). The workflow reads Dependency-Track, groups findings by component, and files
  **one** consolidated Linear ticket for the whole repository — key parts are the repo plus the
  literal `current-vulnerabilities`, so a later scan comments the full current set on that same
  long-lived ticket rather than filing a ticket per CVE. It never touches git, opens no PR and
  polls no CI. `CveFixProperties`'s `agent` and `ci` blocks are retained but **unused**, purely so
  a Temporal history serialized by the old implementation still deserializes. `DeployWorkflowService`
  lost its `@ConditionalOnProperty(factory.deploy.trigger-enabled)` so the manual endpoint works
  where the webhook is off; the webhook branch still checks `triggerEnabled()` itself.
  Manual redeploy can only redeploy the commit already running: backend and frontend commits must
  be equal and not `unknown`, the phrase `REDEPLOY <short-sha>` is re-validated server-side, and the
  commit sent to Temporal is **the backend's own** — the browser's value only proves the two agree.
  `FACTORY_RUNTIME_ROLE` (`software-factory` / `deployer`) is new, and is only how a container names
  itself in its status response. See `docs/runbooks/software-factory.md`,
  `docs/runbooks/cvefix.md`, `docs/runbooks/linear.md`, and `specs/040-software-factory-console/`.
- 041-share-short-links: A Share control on blog posts, blog cards and news/event cards,
  handing out `https://simonrowe.dev/s/<slug>` — a readable first-party address that
  redirects, unfurls, and counts human clicks. One new collection, `short_links`, and one
  new package, `com.simonrowe.shortlink`. Things that are load-bearing:
  - **The slug IS the `_id`.** The redirect is a primary-key lookup, and slug uniqueness is
    enforced by Mongo rather than by application code that hopes. `ensureFor` inserts and
    catches `DuplicateKeyException`; there is deliberately **no read-then-write** check.
    A duplicate key has two causes needing opposite responses — the slug is taken (retry
    with a suffix) or a concurrent call already minted for *this* content (return theirs) —
    told apart by re-reading the content index, never by parsing the error.
  - **The unique `(contentType, contentId)` index is what makes "one link per item"
    structural.** Created by `V029CreateShortLinksAndBackfill`, never by `@CompoundIndex`:
    `auto-index-creation` is off. `V029` is the first change unit here that creates indexes
    **and** writes data — deliberate, because unlike `platform_releases` a slug is not
    derived self-healing data; nothing recreates a lost one with the same value.
  - **`GET /s/{slug}` serves the same 200 OG document to EVERY client — never a 302.**
    Crawlers follow redirects, so a redirect to the SPA lands them on a page with no
    metadata and the link unfurls as the bare site title. User-Agent matching decides only
    whether to *count* a click, where a miss costs an inflated statistic rather than a
    broken preview. An unknown slug is a themed **404**, never a redirect to `/` — a typo
    that lands somewhere plausible looks like a working link.
  - **`og:image` must be absolute.** Crawlers drop a relative one silently, so the feature
    looks broken with nothing in the logs. Three rules: `/uploads/…` gets `site.base-url`
    prepended, an absolute URL passes through (news hotlinks the publisher's image), and
    anything else falls back to `frontend/public/images/share-card.png` — the **frontend**
    public dir, not the backend classpath, because production serves `/images/**` from the
    frontend bundle while local dev proxies it to the backend.
  - **`/s/**` is deliberately absent from `RateLimitInterceptor`'s allowlist in
    `WebConfig`.** One paste into a busy Slack workspace is a burst of unfurl fetches from
    one address range; a 429 there breaks the preview rather than throttling anyone. It is
    also public only via `.anyRequest().permitAll()` — no matcher names it — so
    `SecurityConfigTest` asserts it stays reachable, or a future tightening 401s every link
    already pasted elsewhere. `SecurityConfig`'s global cache-control disable applies and is
    correct here: a cached document would stop the counter incrementing.
  - **`short_links` is in `BackupService.BACKUP_COLLECTIONS` and
    `RestoreService.IMPORT_ORDER_INDEPENDENT`, and `RestoreService.ensureShortLinkIndexes()`
    calls `V029.createIndexes` directly** — a restore drops indexes with the collection and
    Mongock will not re-run a recorded unit. Not housekeeping: these slugs are in URLs
    already pasted into other people's Slack channels.
  - **`frontend/nginx.conf` gains `location /s/`, and now ships inside the frontend image.**
    It used to *also* be bind-mounted from the deploy directory, which shadowed the image's
    copy — see the `042-share-links-404` entry, which is how this route spent its first
    day live returning the SPA's 404 for every shared link. Still verify with
    `curl -i https://simonrowe.dev/s/<known-slug>` after deploy — getting the SPA's HTML
    back means every shared link is unfurling as the bare site title. `vite.config.ts` needs
    the matching `/s` proxy or the endpoint 404s locally.
  - `shortUrl` is added to `BlogSummaryResponse`, `BlogDetailResponse`, `ArticleResponse`
    and `EventResponse` as a **nullable** absolute URL via a *second* factory overload (the
    one-arg forms have six callers between admin and favourites). Populated by one batched
    `urlsFor` per listing — 24 news cards cost one extra query, not 24. Null means no link
    yet and the Share control is simply absent, never broken.
  - Frontend: `ShareButton` detects `navigator.share` → `clipboard.writeText` →
    `execCommand` **at click time, not render time** — jsdom has neither of the first two,
    so render-time detection would leave both shipping paths untested. `AbortError` from a
    dismissed sheet is swallowed and does **not** fall through to copying.
  - `NewsEventsPage` gains `?article=` / `?event=` deep links. The drawer was already
    id-driven so it needed no new state; the real work is `deepLinkedArticles` /
    `deepLinkedEvents`, fetched by id when the shared item has fallen off page one —
    **without it the page loads and silently does nothing**, the failure mode most likely to
    ship unnoticed. Both fetches are gated on `newsSettled`/`eventsSettled`, or every shared
    link fetches by id even when the item is on page one. Cards now carry
    `id={article.id}` / `id={event.id}` so the already-mounted `useScrollToHash` has
    something to find.
  - Four controls on a news card: under 30rem the labels collapse to icons rather than any
    control being dropped.
  - Admin: `GET /api/admin/short-links` (unpaged, sorted in the browser) at
    `/admin/short-links`, plus a Clicks column in the blog list. A deleted item leaves an
    **orphaned link with a null title** that stays visible — slugs are never reclaimed,
    because reclaiming one would redirect an already-shared URL to different content.
  See `specs/041-share-short-links/`.
- 039-linear-issue-sink: A sixth `software-factory` module, `com.simonrowe.factory.linear` — a
  **sink** with no trigger, schedule or webhook of its own, on a new `linear` Temporal task queue.
  Files findings from `deploy` (failed deploys) and `cvefix` (unfixable CVE components) into
  Linear exactly once per distinct problem, and stays quiet once a human has declined one.
  **`linear` is the factory's first activity-only task queue**: verified live against
  `temporal-spring-boot-starter` that `@ActivityImpl(taskQueues = "linear")` alone gets a worker,
  with no `@WorkflowImpl` and deliberately no entry in `workflow-packages` for it — so
  `temporal task-queue describe --task-queue linear` correctly shows **one activity poller and
  zero workflow pollers**; do not "fix" that shape to match the other five queues. Fingerprint is
  `sha256("v1:" + producer + ":" + keyParts)` — deploy's key parts are failing phase +
  `DeployStatus` (not the service, which is not structured anywhere), cvefix's is the component
  purl alone (`UnfixableFindingRecord`'s existing key). **Bumping `Fingerprint.VERSION` (`v1`)
  orphans every existing ticket** — a deliberate, one-time cost, never a casual change.
  Precedence when resolving every issue carrying a fingerprint via Linear's `attachmentsForURL`:
  **open > (canceled or duplicate) > completed.** Linear ships a `duplicate` state type out of
  the box and sets `canceledAt` (not `completedAt`) on it — verified live, and not in the
  original design — so a duplicate-closed ticket suppresses exactly like a canceled one, rather
  than falling through to `UNKNOWN` (classified open) and having the sink keep commenting on a
  ticket someone declined. **Reopening a cancelled or duplicate issue un-suppresses it**, because
  open outranks that band; no config flag exists for this, it is just what the precedence gives
  you. A regression (fixed, then recurred) files a **new** issue linked to the completed one,
  because the same fingerprint URL can legally sit on two issues (also verified live). Mongo's
  `linear_issues` is the audit trail, never the source of truth — state is always re-read from
  Linear. `attachmentPending` on that record is set true between `issueCreate` and
  `attachmentCreate` and cleared after, specifically so a retry landing in that gap **repairs by
  attaching** rather than filing a second ticket for the same problem.
  **Credential confinement**: `deployer` runs the same image as `software-factory` and holds
  `/var/run/docker.sock`, so it must never hold `LINEAR_API_KEY`. The **only** thing stopping
  that is `LinearActivitiesImpl`'s class-level `@ConditionalOnProperty(factory.linear.enabled)` —
  evaluated by the component scanner, so declaring the class through an explicit `@Bean` method
  would register it unconditionally and silently ignore the annotation, the same trap
  `DeployActivitiesImpl` documents. `docker-compose.prod.yml` declares
  `FACTORY_LINEAR_*`/`LINEAR_API_KEY` only under `software-factory`; a new
  `DeployerLinearCredentialTest` reads the compose file and fails the build if any variable whose
  name **contains** `LINEAR` appears under `deployer`, because the Java-side gate alone does not
  stop a future compose edit handing the credential to the socket-holding container directly.
  Containing rather than prefixed on purpose: a `LINEAR_` prefix catches `LINEAR_API_KEY` and
  misses `FACTORY_LINEAR_ENABLED`, the flag that actually registers `LinearActivitiesImpl` in the
  socket-holding JVM. Both
  producers carry a request-level `linearFilingEnabled` flag — set by whichever side builds the
  request from its own configuration, since a `@WorkflowImpl` cannot inject Spring properties —
  as the primary guard against scheduling `fileIssue` at all while the sink is disabled; the
  activity's 2-minute `scheduleToCloseTimeout` is only the backstop, because with
  `factory.linear.enabled=false` nothing polls the `linear` queue and an unguarded schedule would
  otherwise stall the producing deploy or CVE run until that timeout instead of failing in
  milliseconds. **The deploy failure path's GitHub issue is gone** — `gh issue list --state all`
  returned nothing, proving it had never once fired — and the commit comment that replaces it now
  names the Linear ticket instead. Off by default everywhere; see `docs/runbooks/linear.md`,
  including two tracked-not-fixed gaps: a `sync-config` or `maintenance-on` deploy failure still
  files nothing (faithful parity with the dead GitHub path, but the worse case since the
  automation itself is wedged), and with the sink disabled `DeployWorkflowImpl` still computes
  the full triage diagnosis via `renderFailure` and then discards it, because that call sits
  inside the `linearFilingEnabled` guard and `DeployRunRecord` persists no triage field.
- feedback (PR #99, 2026-08-11 — never previously in this file): the review-feedback loop,
  `com.simonrowe.factory.feedback`, on the `review-feedback` Temporal task queue, triggered on PR
  close. Harvests the closed review's conversation with Haiku, writes
  `software_factory.review_learnings`, and — when the harvest finds lessons — opens
  `agent-feedback`-labelled guidance PRs (Sonnet) against `agent-setup` and/or the source repo.
  PRs already labelled `agent-feedback` are never harvested, which is the loop guard stopping the
  distiller from learning from its own guidance PRs. Master switch `FACTORY_FEEDBACK_ENABLED`,
  off by default. Needed a GitHub App permission bump (Contents read → read/write) before its
  image could ship, which is exactly the outage `software-factory-manual-actions.md`'s item 1
  records: `GitHubCredentials.mintInstallationToken` requests `contents: write` on **every**
  installation token regardless of which path is minting it, so an unbumped permission 422s token
  minting for code review too, not just for feedback. See "Review feedback loop" in
  `docs/runbooks/software-factory.md`.
- 038-deploy-rollout-fixes-2: The first three merges after auto-deploy went live
  (2026-08-27) deployed **nothing**, with no visible symptom beyond the site staying on
  the old version. Three causes:
  - **`sync-config` validated the incoming compose file with
    `docker compose -f $(mktemp) config -q`.** Compose derives the project directory —
    and so where it looks for `.env` — from the compose file's own location, so it read
    `/tmp/.env`, found nothing, and every `${VAR:?}` failed as "required variable is
    missing a value". Indistinguishable from the real `missing-variable` decline, and it
    names whichever required variable compose reaches first, so three merges blamed three
    different variables that were all present. Nothing deployed and the site was never
    touched — `sync-config` fails before `maintenance-on`. Fixed with
    `--project-directory "$PROJECT_DIR"`. `service_hashes` had the identical bug with a
    **silent** failure: stderr discarded, empty hash list, which reads as "no service
    changed" and would let a non-allowlisted service past the held-back check.
  - **`reconcile()`'s bare `up -d` recreates the `deployer` mid-deploy**, SIGTERMing the
    container running the workflow; the replacement is left in `created` because the
    process that would start it is the one being killed. The trigger is NOT a change to
    the deployer's service definition — `deployer` and `software-factory` share
    `${FACTORY_IMAGE}` (`software-factory:latest`) and the `pull` phase re-tags `:latest`,
    so compose sees an image change on the deployer on **every deploy where the factory
    image changed**. Happened twice. `reconcile()` now enumerates services with
    `config --no-interpolate --services` (interpolating would make enumeration depend on
    a full `.env`) and excludes `deployer`.
  - **`FACTORY_PLATFORM_BACKUP_SCRIPT`/`_REPO_DIR` pointed at `/workspace/repo`**, which
    stopped existing when 036-auto-deploy-rollout-fixes moved the deploy-directory mount
    to its own host path. Inert only because `FACTORY_PLATFORM_BACKUP_ENABLED` defaults
    false. Any new `deployer` path variable must use `${DEPLOY_DIR}`.
  Note the interaction that hid all of this: `sync-config` checks `already-current`
  **before** the compose validation, so a rehearsal deploy on the SHA already in
  production exercises none of it. Only a real fast-forward reaches the broken code.
- 038-pr-governance: `main` gets a real gate, and review findings become resolvable instead of
  deleted. Three mechanisms, deliberately independent. **(1) Findings carry identity.** The bare
  `FINDING_MARKER` gains a fingerprint — `sha256(file + NUL + normalise(title))`, excluding the
  **line** (moves on every rebase) and the **severity** (the model re-grades) — and
  `GitHubGateway.publishReview`'s unconditional `deletePreviousFindings` is replaced by a
  reconcile against existing threads. **Nothing is deleted any more**: `ThreadAction` has no
  delete case and a test asserts it stays that way. Reading and resolving threads is **GraphQL**
  (`ReviewThreadGateway`) because REST can neither see `isResolved` nor set it — that is the
  actual reason delete-and-repost was the only strategy available before. Resolution needs only
  `pull_requests: write`, already held. The reply is **"No longer reported as of `<sha>`", never
  "Fixed"** — a re-worded title produces the same state as a genuine fix, so "fixed" would be a
  lie. Threads the reviewer did not open are never touched; legacy bare-marker threads match no
  fingerprint and so are resolved on the first run after deploy (correct, and destroys nothing).
  **(2) The verdict becomes a `Code Review` check run**, since no merge path can read an issue
  comment. `failure` when the verdict is `REQUEST_CHANGES` **or** any `CRITICAL` finding exists —
  both checked independently, because the engine can emit a verdict inconsistent with its own
  severities (`APPROVE` + `CRITICAL` must be red). **Only `success` and `failure` are ever sent**;
  whether `neutral` satisfies a required check is version-dependent behaviour the gate must not
  rest on. Created after `loadPullRequest`, not at `openStatusComment` time, because that holds
  only a `ReviewRequest` whose `expectedHeadSha` is nullable on the manual path — so a review that
  dies earlier creates **no check at all**, and an absent required check blocks. That is the fix
  for silence being the normal presentation of failure. Accepted cost: a `software-factory`
  outage stops all merging. **(3) `.github/rulesets/main.json`** requires four checks
  (`Backend`/`Frontend`/`Software Factory Build & Test` + `Code Review`), **zero** approvals
  (self-approval is forbidden, so requiring one deadlocks a solo maintainer permanently),
  conversation resolution and linear history. **Repository admins bypass every rule**
  (`actor_id: 5`, `bypass_mode: always`) — added 2026-08-29, reversing 038's original
  `bypass_actors: []`, because without it a `software-factory` outage stopped *all* merging and
  the only recovery was hand-editing the required contexts in the GitHub UI under pressure. The
  hatch is an escape hatch, not a merge strategy; every use lands in rule insights, which is now
  the whole control. Excluded on purpose:
  `Static Analysis` (`continue-on-error: true`, so success is meaningless), `SonarCloud Code
  Analysis` (would make an intentionally advisory gate blocking with no legitimate escape hatch,
  and Constitution III bans manual overrides), `evaluate` (`paths:`-filtered, normally absent, and
  an absent required check blocks forever).
  Two things will brick the repository if done out of order, and neither is testable:
  - **Grant the App `checks: write` BEFORE deploying.** `mintInstallationToken` sends an explicit
    `permissions` block and GitHub 422s the *whole* token request when it over-reaches, which
    takes down code review **and** the feedback loop together — same shape as the `contents:
    write` incident. `commentToken` survives only because it deliberately sends no block at all.
  - **Committing the ruleset does not apply it; apply it only after seeing a real `Code Review`
    check.** Applying it first makes that required check permanently absent, blocking *every* PR
    including the one that would fix it. The admin bypass is now the recovery path.
  Also: `scripts/classify-change.sh` (+ `test-classify-change.sh`, auto-discovered by
  `run-tests.sh`) maps changed paths to `auto-merge`/`ux-review`/`manual`. **Rule 4 —
  unrecognised path ⇒ `manual`, never `auto-merge`** — so a new top-level directory defaults to
  needing a human; and infra paths **outrank** backend-only ones because an auto-merge triggers
  Publish, which triggers an unattended prod deploy. Expect far fewer unattended merges than
  "backend-only ⇒ auto-merge" implies: conversation resolution means *any* `SUGGESTION` blocks
  until fixed or declined. Deploying needs **both** `software-factory` and `deployer` (same image,
  and `deployer` never recreates itself). Skills (`pr-review-loop`, `code-review-triage`) live in
  `simonjamesrowe/agent-setup` and are follow-up. See `docs/runbooks/pr-governance.md`.
  **Do not run `.specify/scripts/bash/update-agent-context.sh` on this file** — it fails with
  `grep: repetition-operator operand invalid` and silently strips the lead line from eight
  existing entries here.
- 037-platform-status-page: A public `/status` page reports which commit each first-party
  service runs, the third-party image tags, and a changelog with AI-written release notes.
  Every version fact is **baked into the artifact at build time** (`springBoot { buildInfo }`
  with the commit SHA in `additional`, plus two generated resources) and self-reported — no
  Docker socket, so nothing new touches the one container that can mutate prod.
  `GET /api/platform/status` returns three services (backend, `software-factory`, `deployer`);
  the frontend adds its own entry client-side because the backend cannot know which bundle a
  browser loaded. Things that are load-bearing:
  - **`software-factory`/`deployer` version metadata comes from CI build args, not git inside
    the image.** `Dockerfile.software-factory` runs Gradle in the build stage with `.git/`
    excluded by `.dockerignore`, so `software-factory/build.gradle.kts` reads
    `GIT_SHA`/`GIT_COMMIT_TIME`/`GIT_COMMIT_SUBJECT` env vars first and only falls back to
    running `git` directly for a local build. `publish.yml`'s `publish-software-factory` job
    resolves those three on its full-history runner checkout and passes them as
    `docker/build-push-action` build-args. Get this wrong and both services permanently report
    `unknown` with no error anywhere — it happened once during implementation.
  - **`publish.yml`'s `fetch-depth: 0` only needs to be on three of the four checkouts** —
    `publish-backend`, `publish-frontend`, `publish-software-factory`. The default depth-1
    checkout makes `git log` return ONE commit, so the changelog ships with a single entry and
    looks like it worked; the `sbom` job stays shallow on purpose, since it never runs
    `generateReleaseHistory` or reads `buildInfo`.
  - **`buildInfo`'s `time` is the COMMIT timestamp, not wall-clock** — a wall-clock value
    changes every build and invalidates `:backend:bootJar` in the cache `ci-build-speedup`
    only just got working.
  - **Summaries are generated at ingest by `ReleaseSummarySweep`, never on view.**
    `/api/platform/**` is deliberately absent from `RateLimitInterceptor`'s explicit four-path
    allowlist in `WebConfig` (the page makes two requests per view), so an LLM call on the read
    path would be both a cost and abuse problem. Releases go `PENDING` → `READY`/`FAILED` only,
    with no intermediate claimed state — `ReleaseSummarySweep.sweep()` reads `findPending()` and
    calls `summarise()` directly, no `findAndModify` claim step in between. Safe today only
    because prod runs one backend instance and `@Scheduled(fixedDelay)` cannot let a second tick
    overlap the first; revisit before ever running two instances or switching to `fixedRate`.
  - **Release records are written by `ReleaseRecorder` on startup, not Mongock** — deliberate
    deviation: they are derived, self-healing data a restore has to re-establish, and
    change-unit LLM I/O would run against the shared Testcontainers Mongo. `V022` creates
    indexes only, and `RestoreService` calls `createIndexes()` directly because Mongock will
    not re-run a recorded change unit. `PlatformRelease` has no `insertions`/`deletions`
    fields — dropped as dead schema with no data source and no consumer.
  - **software-factory's `GET /api/version` is unauthenticated on purpose** — unrouted by
    nginx, discloses only a public-repo SHA. Token-protecting it would hand the backend a
    token that also authorises `/api/reviews`. This endpoint is what makes `deployer` drift
    visible, since it never recreates itself.
  - One commit == one release: `main` is squash-merged and Publish runs on every merge.
    Historical entries are labelled **published**, not deployed — `deploy_runs` is empty.
  - `platform_releases` is in `BackupService.BACKUP_COLLECTIONS` and
    `RestoreService.IMPORT_ORDER_INDEPENDENT`. See `docs/runbooks/platform-status.md`.
- 034-platform-datastore-backup: nightly (02:00) + on-demand capture of the four
  `langfuse-db` Postgres databases (`langfuse`, `dtrack`, `temporal`,
  `temporal_visibility`) and the ClickHouse `default` database. **It runs in the
  `deployer`, not the backend** — `scripts/backup-platform.sh` invoked by a Temporal
  activity on an active-by-default nightly schedule (`platform-backup-nightly`,
  `FACTORY_PLATFORM_BACKUP_ENABLED`). Constitution 2.0.0 forbids `ProcessBuilder` and
  Docker access in the container serving public traffic, so the capture cannot live
  there; the Java side never invokes `docker` itself, exactly as `PhaseRunner` only
  ever runs `restart-prod.sh`. The backend lists retained archives under Data Ops and
  proxies dry-run or confirmed real captures from the Software Factory admin page to
  the unrouted factory API. There is no restore endpoint. A newly created schedule is
  active; an existing operator pause is preserved. Always assert a live poller on the
  `platform-backup` task queue, since a healthy container with no poller runs nothing.
  **The separate Drive folder (`simonrowe-platform-backups`) is load-bearing:**
  retention deletes everything past the newest 7 `.zip` in a folder, so sharing one
  would make the two backup types evict each other and silently halve both recovery
  windows. The script resolves it by name and never falls back to
  `GOOGLE_DRIVE_FOLDER_ID`; `GoogleDriveFolderResolutionTest` guards the backend's
  listing side of the same rule. The Software Factory admin page can start a dry run or
  confirmed real capture through the backend-to-factory proxy; restore remains host-only.
  Upload is Google's resumable protocol in `curl`
  (session URI + ranged PUT), with Temporal retry over the top.
  New `langfuse-clickhouse-backups` volume + `config/clickhouse/backup-disk.xml`
  (`<backups><allowed_path>`) + a `clickhouse-backups-init` busybox one-shot that
  `chown`s the volume to `101:101`. **The chown is required, not defensive:**
  `/backups` does not exist in the ClickHouse image, so the volume is created
  root-owned while the server drops to uid 101 even when the container starts as
  root — verified, `BACKUP` fails `CANNOT_OPEN_FILE errno 13` without it.
  `clickhouse-backups-init` is registered in `ONESHOT_SERVICES` in
  `scripts/monitor-prod.sh`; a one-shot missing from that list reads as a broken
  container every cron tick and makes the watchdog reconcile the whole stack once a
  minute forever. Restore is **`scripts/restore-platform.sh`** on the host, per-target
  (`langfuse`/`dtrack`/`temporal`/`all`), never stops `langfuse-db` itself (dropping
  databases inside a running server is what keeps the targets independent), restarts
  stopped consumers from an `EXIT` trap so a failed restore leaves them running, and
  **refuses to run when the archive's SHA-256 secret fingerprints don't match `.env`**
  — Langfuse/DT rows restored under different secrets load fine and then fail to
  decrypt, a failure that presents as success. Both scripts compute that fingerprint
  with `printf '%s'`, never `echo`: a trailing newline would refuse every legitimate
  restore. Verified against the pinned `clickhouse-server:26.7.1.1315`:
  `DROP DATABASE ... SYNC` then `RESTORE DATABASE` works (also into an
  existing-but-empty `default`, which the entrypoint recreates on restart);
  `allow_non_empty_tables` is deliberately **unused** because it appends and would
  duplicate every trace row; and `docker cp` alone is not enough — it preserves host
  ownership, so the file must be chowned to 101:101 or the restore fails
  `CANNOT_OPEN_FILE` with no hint that ownership is the cause. The two backups **can
  now overlap** (no shared mutex); the 22:00/02:00 gap is kept for I/O contention, not
  exclusion. Deploying recreates `langfuse-clickhouse` and `deployer`. ClickHouse
  archive size is **unbounded and still unmeasured** (no TTL on trace tables); measure
  before trusting the Drive quota. See `docs/runbooks/platform-backup-restore.md`.
- 036-auto-deploy-rollout-fixes: Turning auto-deploy on for the first time (2026-08-27)
  found that **the `deployer` could not perform a single deploy step**, for nine separate
  reasons, none of which any test could catch — they are all properties of running
  `docker compose` *inside a container against the host daemon*, and the unit tests mock
  the shell. All are fixed in `docker-compose.prod.yml`; see the new
  "Running compose from inside the deployer" section of `docs/runbooks/deploy.md`.
  The ones worth remembering because they fail deceptively:
  - **Relative bind mounts are resolved against the compose project directory and then
    handed to the HOST daemon.** With the old `.:/workspace/repo`, compose asked the
    daemon for `/workspace/repo/frontend/nginx.conf`, which exists only inside the
    deployer. **The daemon creates a missing bind source as an empty directory instead of
    erroring**, so the container dies with "not a directory" and the host root gets a
    stray `/workspace/...`. Nine binds are affected, including nginx's own proxy conf and
    the maintenance page. Fixed by mounting the deploy directory **at its own host path on
    both sides** (`${DEPLOY_DIR}:${DEPLOY_DIR}`).
  - **`COMPOSE_PROJECT_NAME` was left on `backend`** when 036 moved the Docker socket to
    `deployer`. Compose derives the project from the directory name, so the deployer would
    have built a *second, parallel stack* and reported a successful deploy while the live
    site ran the old images.
  - **Compose gives the process environment precedence over `.env`.** Any variable that is
    both interpolated in the compose file and set in the deployer's own environment
    resolves to the container's value when the deployer runs compose. Two collided:
    `GITHUB_APP_PRIVATE_KEY_PATH` meant both the host mount source and the in-container
    path (now split into `..._HOST_PATH` + `..._PATH` — **do not merge them back**), and
    `FACTORY_DEPLOY_TRIGGER_ENABLED` was pinned `"false"` on `deployer`, which would have
    re-rendered `software-factory` with the trigger **off** — auto-deploy would have
    worked exactly once and then disabled itself. That line is now deliberately absent.
  - **`FACTORY_DEPLOY_TRIGGER_ENABLED` was never passed to `software-factory` at all.**
    That service has no `env_file`, and the variable appeared only on `deployer`, so
    rollout step 7 ("set it true on software-factory") was a silent no-op.
  - **`.env` must be group-readable and the deployer joins its OWNING group.** A
    `chgrp factory .env` does not hold: `sed -i` and every rename-based editor recreates
    the file with the host user's group, and the next deploy dies at `recreate` with
    `permission denied` *after* the maintenance page is up (ends `ROLLBACK_FAILED`).
    `group_add` now carries `DOCKER_GID` (the socket is `root:docker 0660` and the
    container runs as uid 10003) and `DEPLOY_ENV_GID`.
  - Also: the `deploy-state` named volume is created `root:root`, so `maintenance-on`
    could not write the flag — fixed with a `deploy-state-init` chown service mirroring
    `uploads-init`; and git refuses the host-owned checkout ("dubious ownership"), which
    `sync-config` misreports as "<dir> is not a git checkout" — fixed with
    `GIT_CONFIG_COUNT`/`KEY_0`/`VALUE_0` setting `safe.directory`.
  - **`monitor-prod.sh` was not deploy-aware.** It polls www every minute and treats the
    maintenance page's 503 as a fault, and a deploy outlasts its 3-strike threshold — so
    the watchdog reconciled the stack underneath a running deploy. It now stands down
    while `deploy-state/maintenance.on` is set (read through nginx, which mounts the
    volume read-only, so it needs no root). `deploy-state-init` was also added to
    `ONESHOT_SERVICES`, or its `exited 0` fires a stack reconcile every single minute.
  - Still open, deliberately not fixed here: `reconcile()` in `restart-prod.sh` runs a
    **bare `up -d`**, which ignores `FACTORY_DEPLOY_RECREATABLE` entirely — so the
    deployer's self-exclusion is incomplete and a merge that changes the `deployer`
    service will have the deploy recreate the deployer mid-flight and kill its own
    workflow. Also `DeployWorkflowImpl` reports `maintenancePageLeftUp: true` on the
    sync-config-failed path, where the page was never raised.
  - The `restart-prod.sh` parser tests (6 checks) cannot pass on the Pi: **the host has no
    `jq`**, which lives only in the deployer image. Pre-existing, not a regression.
- 036-auto-deploy-on-merge: A merge to `main` now deploys itself. `software-factory` gains a
  `workflow_run` branch on its existing signed webhook — accepted only for
  `Publish`/`success`/`main`/allowlisted-repo — which **signal-with-starts** a Temporal workflow
  on the fixed id `deploy-prod` carrying the head SHA. A new `deployer` container (the same
  `FACTORY_IMAGE`, no ingress, holding the Docker socket) polls the `deploy` queue and runs
  phases of `scripts/restart-prod.sh`: `sync-config` → `maintenance-on` → `pull` → `recreate` →
  `verify` → `maintenance-off` → `verify-public`, with rollback + a `Bash`-less Claude triage +
  a GitHub issue and commit comment on failure. Things that are load-bearing and easy to break:
  - **The socket is confined to `deployer` by ONE annotation.** Both containers run the same
    image, and `@WorkflowImpl` classpath scanning is unconditional, so **both** register a
    *workflow*-task poller on the `deploy` queue — harmless, since a workflow only schedules
    activities. What stops `software-factory` executing a deploy step is that
    `DeployActivitiesImpl` carries `@ConditionalOnProperty(factory.deploy.enabled)` and that flag
    is true only on `deployer`. Note a class-level `@ConditionalOnProperty` is evaluated by the
    *component scanner*: declare the same class through an explicit `@Bean` method and the
    annotation is silently ignored, which is why `DeployWorkerRegistrationTest` component-scans
    rather than wiring the beans directly.
  - **`error_page 503 @maintenance` has no `=`.** With `= @maintenance` nginx rewrites the status
    to the named location's 200, so the maintenance page would be served as a success — and
    `verify-public` (which treats 503 as failure) would PASS while the page was still up. The
    flag check lives inside `location /`, never at server level, or it would 503
    `POST /webhooks/github` — the endpoint that triggered the deploy.
  - **`pull_policy: always` → `missing`** on `backend`/`frontend`/`software-factory`.
    `monitor-prod.sh` runs a bare `up -d` every minute, which resolves `:latest`; with `always` a
    rollback was undone within 60 seconds and the watchdog could silently upgrade a service while
    healing an unrelated container.
  - **`sync-config` decides which services a compose change affects BEFORE moving `HEAD`**
    (`git show <sha>:docker-compose.prod.yml` + `docker compose config --hash='*'`). Fast-
    forwarding and then declining to recreate would leave the directory ahead of what is running,
    and the watchdog's next `up -d` would apply the held-back change within the minute. Fenced by
    a clean-tree check (`--untracked-files=no`, so a hand-edited `.env` never blocks), an
    anonymous fetch from a **pinned** URL, `merge-base --is-ancestor`, `--ff-only`, and an
    eight-service recreate allowlist. Declines exit `2` (survivable) rather than `1`.
  - **`pull` truncates `rollback-images` rather than appending**: activities are retried, and an
    append would record the freshly-pulled image as the rollback target.
  - `deploy_runs` keys on the Temporal **run** id, not the workflow id — the workflow id is the
    fixed `deploy-prod`, so keying on it (the `CveFixRunRecord` pattern) would collapse all
    history into one document.
  - The settle-loop parser moved `python3` → `jq` (so the image needs no Python; `curl`/`jq`
    added to the runtime stage) and now handles compose's JSON-**array** output as well as JSON
    Lines — the old parser only handled the latter, so a compose upgrade would have made every
    container look settled.
  - **The `deployer` never recreates itself**, so it does not self-update:
    `docker compose -f docker-compose.prod.yml up -d --no-deps deployer` after any merge touching
    `software-factory/`. Same shape as the bug that left `software-factory` on an old image for
    months; recorded in `docs/runbooks/deploy.md` and the `prod-deploy` skill.
  - Both flags (`FACTORY_DEPLOY_ENABLED`, `FACTORY_DEPLOY_TRIGGER_ENABLED`) default **off**, so
    merging changes nothing until an operator opts in. A human must subscribe the GitHub App to
    `workflow_run` or the feature is inert with no error anywhere.
  - Test the script with `DRY_RUN=1` and a throwaway `STATE_DIR`
    (`./scripts/test/run-tests.sh`). The `sync-config` tests deliberately opt out of `DRY_RUN`
    because real git behaviour is what they verify, and are safe because each builds its own
    throwaway origin+clone. See `docs/runbooks/deploy.md` and `specs/036-auto-deploy-on-merge/`.
- 035-listen-from-listing: Narration audio is playable straight from `/blogs` and `/news-events`.
  New public `GET /api/narrations/ready?contentType=BLOG|ARTICLE_SUMMARY` returns
  `[{contentId, audioUrl, durationSeconds}]`, one row per content id (newest `READY`, via a
  `match`/`sort`/`group first` aggregation). **This bulk read is a necessity, not an
  optimisation**: `RateLimitInterceptor`'s POST-only exemption exists only in the summary
  branch, so `/api/blogs/*/narration` is capped at 10/min per IP on `GET` too and per-card
  polling would 429 on first render — the new path deliberately does not match that pattern.
  For `ARTICLE_SUMMARY` the `contentId` **is the aggregated article id**, so the news page
  needs no join. **`POST /api/blogs/{blogId}/narration` is now authenticated** (it spends the
  same monthly TTS budget as summary narration) — the previously deliberate asymmetry is
  gone, `SecurityConfigTest` asserts the new posture, and `BlogNarration` gained the
  `useEnsureAuthenticated()` gate `SummaryNarration` already had; `GET` stays public on both.
  Frontend: `NarrationAudioProvider` mounted **above `<Routes>` and inside `AuthProvider`**
  holding a `new Audio()` **appended to `<body>`** — `PublicLayout` wraps each route
  individually, so anything inside it remounts on navigation and a JSX `<audio>` there stops
  playing; `<body>` rather than fully detached because `document.querySelectorAll('audio')`
  only walks the document, so a detached element is invisible to `NarrationPanel`'s
  "pause every other audio" and the two players talk over each other;
  `ListenButton` (a keyed view over provider state, no local state) and `NarrationPlayerBar`
  (inside `PublicLayout`, so never under `/admin`). The chain imports `useNarration`'s
  `LONG_POLL_SECONDS`/`MAX_LONG_POLLS` rather than adding a second polling policy.
  `useArticleSummaries` gained `noteSummarised(articleId)` so a summary produced by the
  Listen chain flips the card without refetching the ids set.
  Starting a chain **pauses and clears the audio element first**, and the bar renders its
  transport only when the current track has an `audioUrl` — without both, pressing Listen on
  a cold card while another track played left the previous audio running under a bar
  relabelled to the new item, with a Pause button that paused a post the bar was not naming.
  Only reproducible with one ready and one cold item at once, so it took a manual pass
  against restored prod data to find.
  `NarrationScriptBuilder.FORMAT_VERSION` is untouched. See `specs/035-listen-from-listing/`.
- ci-build-speedup: `:backend:test` had grown to 13m28s in CI, and **421s of it was seven
  `KafkaTemplate.send()` calls in one test class** (`FavouritesControllerTest`) each
  blocking for the 60-second `max.block.ms` default, because the test profile points at
  `localhost:9092` and CI has no broker there. `send()` is only asynchronous *once the
  producer holds topic metadata*; before that it blocks the calling thread inside
  `waitOnMetadata`. Dated precisely to commit `0cc86413` (PR #106, auto-summary on
  favourite), which took CI from 418s to 795s in one step. Fixed in two places:
  - Production `spring.kafka.producer.properties.max.block.ms: 5000`. The 60s default was
    a live prod bug, not just a slow test — `FavouritesService.requestSummary` catches and
    swallows publish failures so "the heart still fills", but the catch only runs *after*
    the block, so a down broker hung a request thread for a full minute.
  - A `SharedKafkaContainer` singleton (same static-initializer pattern as
    `SharedMongoContainer`, deliberately not the per-class `@Container` lifecycle) wired
    into `AbstractIntegrationTest`, so integration tests publish to a real broker rather
    than a dead port. Mocking the publisher would have hidden the client behaviour that
    caused this. `ApplicationTests` now reuses it instead of starting its own Kafka.
    Note this changes an old invariant: Kafka is no longer confined to `ApplicationTests`.
  Result: 870 tests, 10m02s → 2m21s locally.
  **Separately, CI's Gradle build cache had never worked once.** `setup-gradle` writes its
  cache only on the default branch (`cache-read-only: true` everywhere else) and keys it
  per job id, and `ci.yml` triggered on `pull_request` only — so no run ever wrote a cache
  that CI's own jobs could restore, and the backend job fell through its restore keys to
  the 1.1MB `sbom` entry and recompiled cold every time. `ci.yml` now also runs on
  `push: [main]`. With a warm cache an untouched module reports `:backend:test`
  FROM-CACHE (verified locally: 1s after a full `clean`). The `concurrency` group cancels
  superseded PR runs but deliberately never main — a cancelled main run is a lost cache
  write that every subsequent PR would pay for.
- 034-article-summary-audio: On-demand, globally shared AI summaries of aggregated news
  articles (`article_summaries`, id = `sha256(SUMMARY_FORMAT_VERSION + articleId)`) with
  optional audio. Generation is **synchronous** with an insert-first dedup guard — an LLM
  call has no long-running-operation handle to poll, so the Kafka/lease/recovery machinery
  narration needs has no justification here; crash recovery is a conditional
  `findAndModify` guarded on **both** `status` and `updatedAt`. The narration package is
  generalised from `blogId` to `contentType` (`BLOG` | `ARTICLE_SUMMARY`) + `contentId`
  behind a `NarrationSource` strategy; `BlogNarrationService` → `NarrationService`,
  `BlogNarrationScriptBuilder` → `NarrationScriptBuilder`, but **`FORMAT_VERSION` stays the
  literal `blog-narration-v1`** because it feeds the fingerprint that *is* the narration
  `_id` — changing it orphans every stored blog MP3. `/api/blogs/{blogId}/narration` keeps
  its path, but its `POST` is **no longer public** — 035-listen-from-listing made it
  authenticated to match the summary narration `POST`, because both drain the same
  1,000,000 chars/month TTS budget. `ArticleSectionWriter`'s source-text
  cascade is extracted to `ArticleSourceTextProvider`. `article_summaries` must be added to
  `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT` (a
  restore drops collections, so `NarrationRestoreValidator.ensureIndexes()` — not Mongock —
  is what puts narration indexes back). See `specs/034-article-summary-audio/`.
- 033-sonarqube-static-analysis: SonarCloud analysis moved out of the `backend` job into its
  own `sonar` job (`needs` all three build jobs, `fetch-depth: 0`, `continue-on-error: true`,
  runs `./gradlew classes testClasses sonar` — no test re-run). `SONAR_TOKEN` moved to
  job-level `env:` so the `if: env.SONAR_TOKEN != ''` guard can actually evaluate true; it
  never could before, so the analysis had never run once. **A tokenless `sonar` invocation
  takes ~10 minutes and then fails hard**, so that guard is load-bearing. Frontend gains
  `@vitest/coverage-v8` + `test:coverage` + a blocking `npm run lint` step (exits 0 today:
  5 `react-refresh` warnings, 0 errors); `software-factory` gains JaCoCo **report only, no
  floor**. `sonar.coverage.exclusions` hand-mirrors `backend`'s nine `jacocoExcludes` entries,
  translated from JaCoCo's class-file dialect to Sonar's source-file dialect — keep the two
  lists in step or the coverage percentages disagree. Frontend has 58 tests in
  `frontend/tests` and **9 co-located under `frontend/src`**, so `sonar.sources`/`sonar.tests`
  deliberately overlap and are disambiguated by `sonar.exclusions` +
  `sonar.test.inclusions`. Gate is advisory (`sonar.qualitygate.wait` unset). See
  `docs/runbooks/static-analysis.md`.
- 030-langfuse-sessions-content-evals: `chat-turn` Micrometer observation carries `session.id` +
  `langfuse.trace.input`/`.output` (fixes empty Sessions and shallow traces);
  `LangfuseContentObservationFilter` writes prompt/completion span attributes (Spring AI's
  `log-prompt`/`log-completion` only log, they never set attributes); `LangfuseScoreClient`
  posts guardrail/tool-count/error/empty-answer scores; Alloy `ai_only` keep-list gains
  `langfuse.trace.name`; local Langfuse upgraded to v3 with an Alloy traces pipeline;
  `scripts/bootstrap-langfuse-evaluators.sh` provisions LLM-as-a-judge. Spring Boot 3.5.16,
  Spring AI 1.1.8, OTel instrumentation 2.30.0.
- 029-favourite-news-events: Added Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies.
- 028-chat-ontopic-web-search: Added Java 21 (backend only) + Spring Boot 3.5.x, Spring AI 1.1.4 (OpenAI SDK starter + `@Tool`),
- 027-mcp-page: Added TypeScript 5.x (frontend); Java 21 / Spring Boot 3.5.x (backend — MCP server config + ToolCallbackProvider) + React (latest stable), React Router v7, Vite, Vitest, Lucide React; Spring AI 1.1.4 `spring-ai-starter-mcp-server-webmvc` (existing)
  authoritative `fullResponse` + single initial-query send guard), contextual tool labels
  (dropped "Used 1 tool" expander), safe allowlisted link/image rendering in answers
  (`chat/linkPolicy.ts`, custom react-markdown `a`/`img` renderers, no `rehype-raw`),
  item-level deep links (`/experience?job=`/`?skillGroup=` via `useDrawer` + `useScrollToHash`,
  job/skill-group ids added to widget payloads), Playwright e2e (`frontend/e2e/`), and
  deterministic Langfuse bootstrap (`LANGFUSE_INIT_*` in `docker-compose.prod.yml`,
  `scripts/verify-langfuse-trace.sh`, `docs/runbooks/langfuse-observability.md`).

<!-- MANUAL ADDITIONS START -->
# Manual additions

> Maintained in simonjamesrowe/agent-setup — edit there.

- The `pinggy` tunnel is single-tenant per `PINGGY_TOKEN`: if another host still holds the tunnel, reclaim it by appending `+force` to the token value (`PINGGY_TOKEN=<token>+force`).
- On macOS, running the production compose file under OrbStack requires overriding `DOCKER_BINARY_PATH=/opt/homebrew/bin/docker` and `DOCKER_PLUGINS_PATH=~/.docker/cli-plugins`, since the compose defaults assume a Linux Docker install.
- There is a management-port mismatch between environments: `docker-compose.prod.yml` sets `MANAGEMENT_SERVER_PORT: 8081`, while `application.yml` defaults `management.server.port` to `8082`; local health checks should target `8082` unless an env override is in effect.
- The README's backup/restore instructions are stale: `scripts/create-backup.sh`, `scripts/restore-backup.sh`, and `scripts/migrate-strapi-data.js` no longer exist in the repo — use `scripts/backup.sh` and `scripts/restore.sh` instead.
- **The backend has no self-redeploy endpoint any more.** `POST /api/admin/data-operations/redeploy` and `RedeployService` were deleted in `036-auto-deploy-on-merge`, together with the backend's `/var/run/docker.sock`, docker-CLI, compose-file and `.env` mounts. Deploys are performed by the `deployer` container instead. A `NoHostProcessLaunchTest` now fails the build if any `ProcessBuilder` reappears in `backend/src/main/java`, and Constitution Principle II (2.0.0) prohibits it.
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies. (029-favourite-news-events)
- MongoDB — new `favourites` collection (record + `@Document`, unique compound index on `userId,type,contentId`). Existing `aggregated_articles` / `aggregated_events` unchanged. (029-favourite-news-events)
- Static analysis: SonarQube Cloud (`org.sonarqube` 6.0.1.5171, project key `simonjamesrowe_simonrowe-dev-monorepo`), JaCoCo 0.8.12 on `backend` (0.78 floor) and `software-factory` (report only), `@vitest/coverage-v8` ^3.0.0 for frontend LCOV, ESLint 9 in CI. No persistence. (033-sonarqube-static-analysis)
- Java 21 (backend), TypeScript 5.x / React 19 (frontend), bash (restore script) + Spring Boot 3.5.x `@Scheduled`/`@RestController`, the existing Google Drive API client, `java.util.zip`, `java.lang.ProcessBuilder`. **No new dependencies in any module.** (034-platform-datastore-backup)
- No application persistence: reads Postgres 15 (`langfuse-db`) and ClickHouse (`langfuse-clickhouse`) via `docker exec`, writes a zip to Google Drive. One new Docker named volume (`langfuse-clickhouse-backups`) as the ClickHouse→backend handoff. (034-platform-datastore-backup)
- Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.16, Embabel `Ai` (`com.embabel.agent.api.common.Ai`, the established inline-LLM injection point alongside `ArticleSectionWriter`/`DigestComposer`), Mongock, Bucket4j via the existing `RateLimitInterceptor`, `react-markdown`, Lucide React `Sparkles`. **No new dependencies in either module.** (034-article-summary-audio)
- MongoDB — new `article_summaries` collection (mutable `@Document` class, not a record, because the generation flow transitions it in place); `narrations` changed from `blogId` to `contentType` + `contentId`. Indexes via Mongock change units `V020`/`V021` — `auto-index-creation` is off, so `@Indexed`/`@CompoundIndex` alone are decorative. (034-article-summary-audio)
- Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.16 (web, security OAuth2 resource server, data-mongodb), `MongoTemplate` aggregation, existing `useAuth`/`useEnsureAuthenticated` (Auth0), Lucide React. **No new dependencies in either module.** (035-listen-from-listing)
- MongoDB — read-only. **No new collection, field, index or Mongock change unit**: the bulk ready-narration aggregation is already ordered by the existing `idx_narration_content_updated` (`{contentType: 1, contentId: 1, updatedAt: -1}`) on `narrations`. (035-listen-from-listing)

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
