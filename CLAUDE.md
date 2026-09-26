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
- Keep null checks on third-party library return values even when static analysis flags them unreachable — library behavior varies across versions and the check is cheaper than an NPE from a dependency upgrade.

## Key Design Decisions

- Blog tags/skills use MongoDB `@DBRef` references; admin API uses DTO pattern converting between `@DBRef` entities and string IDs for the frontend
- When a request DTO reconstructs a whole resource for a save/update API, test that every field round-trips unchanged, not just the ones the change touches — an omitted field silently drops existing data on save
- Admin CMS uses Lucide React icons for actions/status, right-side drawer for Media Library, two-column layout for blog editor
- Uploads served via Spring `ResourceHandlerRegistry` at `/uploads/**`, path configurable via `UPLOADS_PATH` env var (default: `uploads/` relative to backend CWD)
- `scripts/backup.sh` and `scripts/restore.sh` are the canonical data management scripts (legacy Strapi migration scripts retained for reference)
- When adding dry-run/preview modes to multiple operations, decide independently what each should expose in preview rather than mirroring another operation's guard conditions — e.g. filing might show nothing useful, but sweeping should show what would be resolved
- Destructive Mongock change units (ones that remove rows) need an integration test proving they actually run at boot, correct survivor-selection logic, and a comment documenting why the deleted data is safely re-derivable — path classifiers won't flag these for review, so verify by hand

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
- **Container DNS is pinned in `daemon.json`, because a boot without network strands it.** Docker's
  embedded resolver copies each container's upstream servers from the host *at container start*
  and never re-reads them. On 2026-09-24 the wifi-watchdog rebooted the Pi with the Wi-Fi down,
  twenty containers started with no upstream, and for ~34 hours they resolved service names (all
  green) but nothing external: Dependency-Track lost its Auth0 button (`/api/v1/oidc/available`
  → `false`), alloy shipped nothing to Loki, and software-factory could reach neither Loki nor
  Linear, so nothing reported it. `scripts/enable-docker-dns.sh` pins `dns` (applying needs a
  Docker restart, i.e. a cold start); `monitor-prod.sh` layer 4 restarts an internet-facing
  container that cannot resolve while the host can; and `.github/workflows/prod-heartbeat.yml`
  checks Loki freshness and the public hostnames **from GitHub**, the only alarm the Pi's own
  network cannot silence. A missing `# ExtServers:` line in a container's resolv.conf is the tell.
  See `docs/runbooks/prod-monitoring.md` and `docs/runbooks/prod-heartbeat.md`.
  When a probe script like this runs a command inside a container image whose toolset it does not
  control, treat exit codes 126/127 (command not found/not executable) as "cannot probe", not as a
  failure — and verify the probe's tools actually exist in every target image before trusting the
  result.
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
- factory-auto-merge: **The reviewer now arms auto-merge; GitHub performs the merge.** Until now
  auto-merge was armed by the `pr-review-loop` skill, from the same agent session that wrote the
  code. The decision now sits at the end of the code-review workflow, where the factory already
  owns the right moment. There is no "all required checks passed" webhook to wait for, and no
  need for one: the reviewer's own required `Code Review` check is still in progress while it
  arms, so nothing merges until it completes, and GitHub then merges as soon as the ruleset is
  satisfied. `FACTORY_CODEREVIEW_AUTO_MERGE_ENABLED` is declared on `software-factory` (default
  `true` in compose, `false` in `application.yml`). The rules and the one-line summary are in
  `docs/runbooks/pr-governance.md` ("Auto-merge policy"). Load-bearing bits:
  - **Withdraw before every review, fail-closed.** GitHub keeps auto-merge armed across pushes by
    anyone with write access. So a "yes" for a docs-only commit would otherwise merge a later push
    to `docker-compose.prod.yml`. A bot arm that cannot be withdrawn fails the review, and the red
    check holds the merge. **A person's arm is never touched**, and "a bot" is read from
    `enabled_by.type`, never a configured login, because a mistyped login would silently disable
    the withdraw. Arming is the opposite: best-effort, reported as `ARM_FAILED`, never thrown.
  - **`expectedHeadOid` on the arm** makes a slow or stale concurrent review harmless: GitHub
    refuses to arm if anything was pushed after the reviewed commit.
  - **Paths come from `/pulls/{n}/files`, both sides of every rename, never from the review
    workspace.** That list is filtered to agent-safe paths and capped at `maxChangedFiles`, so it
    can omit exactly the paths this check exists for. A listing short of GitHub's `changed_files`
    count arms nothing.
  - **The public-repo rules come first:** no forks, and the author must have `write`/`admin`
    permission, read from `/collaborators/{login}/permission`. Without them a stranger's pull
    request with a clean review would merge itself. **Never decide trust on
    `author_association`.** It is computed for the viewer, and an App token cannot see a private
    organisation membership, so the first live pull request (#194) was refused with the repository
    owner reading as `CONTRIBUTOR`. `simonrowe`'s membership of `simonjamesrowe` is private, which
    is why it looks correct (`MEMBER`) through a personal token and wrong through the App. `agent-feedback` guidance pull requests are excluded (they touch root
    `*.md`, which the path rules alone would call auto-merge), and `no-auto-merge` is the opt-out.
  - **The classifier exists twice, held together by one fixture.**
    `codereview/domain/MergeDisposition.java` is a port of `scripts/classify-change.sh`, and both
    test suites read `scripts/test/fixtures/merge-disposition-cases.tsv`. Its glob copies bash
    `case` semantics, where `*` crosses `/`.
  - **Versioned with `Workflow.getVersion("auto-merge", …)`**, and a `publishReview` task
    scheduled by the old image with three arguments still runs, because Temporal passes `null` for
    the missing decision. That is pinned by a test, not assumed. Since
    `factory-workflow-workers-per-role` (#193) only `software-factory` polls `code-review`, so
    the new workflow code runs in one build; a `deployer` older than #193 still polls it until
    it is recreated.
  - **Console:** a new fast-loop arrow `codereview → main` ("arms auto-merge"), the pull request
    drawer shows `auto-merge armed` / `auto-merge armed (by a person)`, and the run banner names
    the decision. Edge labels are API data only; the SVG draws no edge text.
  - **Outstanding in `agent-setup`:** `pr-review-loop` step 7 still runs `gh pr merge --auto`,
    which arms as a person and so is never withdrawn. It should report the reviewer's decision
    instead.
- factory-workflow-workers-per-role: **Each Temporal workflow is now polled by one container.**
  `software-factory` and `deployer` run the same image, and `@WorkflowImpl` classpath scanning
  cannot be gated by a Spring condition, since those classes are not beans. So both used to poll
  all six workflow queues. The comments called that harmless ("a workflow only schedules
  activities"), and it was harmless only while both ran the same build. The `deployer` never
  recreates itself and routinely lags; on 2026-09-15 that is what wedged `logwatch-daily` (see
  `temporal-version-skew` below). #191's lenient converter survives an *added* field. It could
  not survive a changed workflow: adding, removing or reordering an activity call on one build
  replays as a `NonDeterministicException` on the other. This removes the mismatch at its source.
  Load-bearing bits:
  - **`spring.profiles.active: ${FACTORY_RUNTIME_ROLE:software-factory}`.** The role variable was
    already set on both services, so there is **no compose change**. That matters: editing the
    `deployer`'s service definition holds a deploy back, the self-perpetuating wedge from #130.
    `application.yml` lists `codereview`, `feedback`, `cvefix` and `logwatch`;
    `application-deployer.yml` **replaces** that list with `deploy` and `platformbackup` (a list
    in a profile document overrides wholesale, it does not merge). Each workflow now sits in the
    container whose `*ActivitiesImpl` flag is true.
  - **Two silent failures, both pinned by `FactoryWorkflowWorkersTest`.** (1) A role that stops
    being exactly `deployer`, or any `SPRING_PROFILES_ACTIVE` on either service, gives the
    `deployer` the software-factory list. Nothing then polls `deploy`, and no deploy ever starts.
    The test reads both roles out of the compose file. (2) A new `@WorkflowImpl` package listed
    for neither role is a queue nothing polls. The test scans the classpath for every
    `@WorkflowImpl` package and asserts the two lists partition them. Mutation-checked: dropping
    `platformbackup` from the deployer list fails two of its tests. `FactoryDeployerRoleTest`
    boots the deployer's context and reads the starter's own registration info, which shows the
    starter honours the profile, not just that the YAML resolves.
  - **The activity gates are unchanged and still the security boundary.** The lists decide where
    orchestration runs; `@ConditionalOnProperty` on each `*ActivitiesImpl` still decides where
    credentials and the Docker socket live. `FactoryApplicationTest`'s old
    `registersDeployWorkflowPollerEvenWithTheFlagsOff` pinned the double registration "so it is
    not fixed"; it now asserts the opposite.
  - **Consequence:** a deploy or platform-backup run's `progress` query is answered only by the
    `deployer`, so it is unavailable while that container is down. `FactoryRunService` already
    reports that as a status with no phase.
  See `docs/runbooks/software-factory.md` ("Which container polls which queue").
- temporal-version-skew: The daily log-watch scan had not run since 2026-09-15, and nothing
  said so. One scheduled run, `logwatch-2026-09-15T00:00:00Z`, was stuck retrying its workflow
  task (about 1,700 attempts, one every ~9 minutes). With overlap `SKIP`, `logwatch-daily` skipped
  every firing behind it: `SkippedOverlap: 11` by the time it was terminated on 2026-09-26. The
  cause was **two builds serving one workflow**. `deployer` runs `FACTORY_IMAGE` too, and
  `@WorkflowImpl` scanning registers every workflow on both containers. The config comment calls
  that "harmless: deterministic orchestration", which holds only while both run the same build,
  and `deployer` is updated separately, so they routinely don't (on 2026-09-26 the two digests
  still differed). That night `observe` ran on the build that had #169's `mutedSignatures`, and
  the workflow task landed on one that did not. Temporal's stock `JacksonJsonPayloadConverter`
  keeps `FAIL_ON_UNKNOWN_PROPERTIES` on, so it threw. The catch block recorded the run and
  rethrew a plain `RuntimeException`, which fails the workflow *task* and retries forever. Every
  replay on the newer build then parsed the result, took the filing path, and hit
  `NonDeterministicException` (`FileIssue` where the history held `RecordRun`). Load-bearing bits:
  - **`TemporalPayloadConfiguration` ignores unknown properties on every payload**, as the bean
    named `mainDataConverter` (the name the starter prefers). This reverses the 046 entry's
    "not fixed, and accepted". That entry expected the exposure only across a deploy boundary,
    for one short-lived run. The deployer makes it permanent, and one stuck run blocks a schedule
    indefinitely. Additive fields are now always safe; removing or renaming one is still breaking.
    `TemporalPayloadConfigurationTest` replays the incident: a payload from the current
    `ScanObservation` read into its pre-#169 shape. A control asserts the stock converter still
    throws on it, so the test cannot pass by testing nothing.
  - **Every scheduled workflow gets a 22h execution timeout** (`ScheduledRuns.EXECUTION_TIMEOUT`,
    on `logwatch-daily`, `cve-fix-daily` and `platform-backup-nightly`): shorter than the gap
    between firings, so a run stuck for *any* reason ends before the next one is due. 22 rather
    than 23 because the backup runs on a `Europe/London` calendar and the spring-forward day is
    23h long. It clears the longest legitimate run, a backup exhausting three 6h attempts. The
    schedules reconcile on boot, so the timeout lands on the next `software-factory` start.
  - **Diagnose from the schedule, not the workflow list.** `temporal schedule describe` shows
    `RunningWorkflows` and `SkippedOverlap`, and a non-zero skip count is the whole signal. The
    workflow list shows a stuck run as merely `Running`. The post-deploy scans kept completing
    throughout, which is why log watch looked alive.
  - **Not changed here, and since fixed:** workflows still registered on both containers after
    this change. `factory-workflow-workers-per-role` (above) gives each workflow one owning
    container.
  See `docs/runbooks/logwatch.md` ("Muting third-party noise", last part).
- news-search-and-source-filter: Two things, one page. **The site search box was never missing the
  article — it was silently degrading.** "AI SLDC" (a transposition of SDLC) returned five Rundown
  AI headlines and not `The AI-Native SDLC playbook`, which reads exactly like an import that never
  reached Elasticsearch. It had: the document was indexed on import, `?q=SDLC` returned it, and the
  4-hourly `fullSyncSiteIndex` plus the per-item Kafka path both cover it. What actually happened is
  that `multi_match` has no fuzziness, so `sldc` matched **nothing**, and with the default OR
  operator the query collapsed to `ai` alone — a full page of plausible results, none of them the
  answer, and no error anywhere. `SearchService` now sends `fuzziness: AUTO` with
  `prefix_length: 1` on all three of its queries (site, blog, by-type) and weights the title
  (`name^3` / `title^3`). Load-bearing bits:
  - **AUTO is what makes the reported case work**: it allows one edit at four characters, and
    Elasticsearch's automaton is Damerau-Levenshtein, so a transposition costs one. Measured
    against a 300-article copy of the production index in a throwaway 9.4.5 container: the SDLC
    article goes from absent to rank 1 (score 1.28 → 17.26), and `marketplce`, `anthropik` and
    `sprign boot` all start finding what they mean.
  - **The boosts matter as much as the fuzziness and are the part that would be quietly dropped.**
    Every field scored the same before, so `claude marketplace` put five articles that merely
    say "Claude" above the one titled "Claude Marketplace". Fuzziness without weighting would have
    found the SDLC article and still ranked it below the noise.
  - **`prefix_length: 1`** keeps the term expansion bounded; verified that `zzzzqqq` and
    `kubernets` (no such document) still return nothing rather than everything.
  **And the News & Events page got the search box it never had, plus a source filter that scales.**
  The pill row was one pill per source with a `MIN_ARTICLES_FOR_PILL = 3` threshold and a "More"
  overflow; at sixteen sources it wrapped to three lines, hid the long tail — which is exactly where
  a manually imported one-off lands — and could only ever hold **one** source at a time. It is now
  a free-text box, a checkbox dropdown that lists every source with its count, and the two existing
  view toggles, on one wrapping row. Load-bearing bits:
  - **`GET /api/news` takes `source` repeatedly and a new `q`.** Repeated rather than
    comma-joined: a source name may contain a comma — `/api/news/sources` is built from scraped
    publisher names — and a joined value would split it into two sources matching nothing. A
    single `?source=X` still works, so nothing that linked to the old behaviour breaks.
  - **`@RequestParam List<String> source` does not do what it looks like, and a test is what
    proved it.** Spring resolves a parameter present exactly *once* to a `String` and then
    converts it to the list **by splitting on commas**, so the repeated-parameter design defended
    against the frontend joining names and then reintroduced the identical fault one layer down:
    `?source=Smith,%20Jones%20%26%20Co` arrived as two sources, and the page reported a real
    source as holding no articles. `NewsController.sourcesFrom` reads
    `HttpServletRequest.getParameterValues` instead, which splits nothing. The comment claiming
    the repeat was sufficient was written before the test existed and was simply wrong; the cost
    of the fix is that a hand-written `?source=A,B` no longer means two sources, which is the
    right way round — a comma inside a name is a real thing, a comma-joined list is a convention.
  - **The page's search is Mongo, not Elasticsearch, and that is deliberate.** What it backs is a
    filter over a date-ordered paged listing — newest first, "Load more" paging the *matching* set —
    and relevance ranking is the wrong shape for that. `ArticleQueryService` ANDs terms across the
    record but ORs them across `title`/`summary`/`author`/`sourceName`, so "claude marketplace"
    matches a Claude Blog article titled "Marketplace launch". Each term is `Pattern.quote`d, so a
    visitor typing `C++` matches literally and there is no quantifier for a regex engine to
    backtrack over; the 100-character and six-term caps bound the collection scan, not a crafted
    pattern. Deliberately **not** `fullContent` — matching text that is not on the card looks like
    a bug. The consequence to know: the page's box is literal where the site box tolerates a typo.
  - **Events are filtered in the browser and articles on the server, and that is not an
    inconsistency.** Events are fetched whole (50 upcoming, 20 past) and never paged, as are
    favourites; for those two, client-side filtering is not a duplicate of the query, it is the
    whole of it. Articles are paged out of ~740, so filtering them client-side would search only
    the loaded 24.
  - **Picking a source still hides the events timeline**, exactly as selecting a pill did: events
    carry sources (`Meetup`, `lu.ma`) that `/api/news/sources` — built from articles — does not
    list, so "these publishers" cannot sensibly include them.
  - **Source counts do not narrow as you type.** They label the sources; numbers that move while
    you read them are harder to choose from.
  - **A search that matched nothing anywhere gets one empty state, not two.** The timeline's own
    "No upcoming events match" above the feed's "Nothing matches" reads as a page that
    half-loaded. It is kept for the useful case — articles found, events not — and in events-only
    mode, where it is the only thing that can answer. Both are pinned by tests.
  - **The hidden checkbox needs `position: relative` on its row.** Absolutely positioned inside a
    label whose nearest positioned ancestor was the whole panel, it landed on some other row's hit
    area — invisible in jsdom, where clicks go straight to the input, and caught only by driving a
    real browser.
  - `.feed__filters` / `.feed__pill` / `.feed__more-count` stay: `/status`'s release timeline wears
    them. `.feed__more*` (the departed overflow menu) and `.feed__modes` are deleted.
  Backend 1593 tests, frontend 958.
- termtime-note-image-upload: `/admin/school/notes` can now turn one photographed page into
  editable note text and a suggested title before the existing Save note path runs. The image is
  never persisted: the browser fixes displayed EXIF orientation, scales the longest edge to 2000
  px and sends a JPEG to a new stateless vision endpoint; the operator reviews the transcription
  in the existing textarea, where it appends rather than replacing typed context. A title is
  prefilled only while the title field is empty. Dateless notes such as spelling lists remain
  ordinary useful notes: they are embedded even when they yield zero events and zero links.
  `SCHOOL_VISION_MODEL` is independently switchable and usage is recorded as `TRANSCRIBE`, because
  an image call should not disappear inside dated-event extraction spend. Both nginx layers now
  allow 12 MB so the backend's 10 MB validation, rather than nginx's default 1 MB HTML error, is
  authoritative; this also repairs the Media Library's existing 10 MB promise. The outer proxy
  config is bind-mounted by file, so production must recreate nginx to pick up that directive.
  Deliberately unchanged: no image bytes in `SchoolAttachmentStore`, one image per note, HEIC
  rejected, and Gmail image attachments still skipped. See `docs/runbooks/term-time.md` ("Reading
  a photographed page before saving it").
- termtime-pasted-notes: A **Paste a note** screen at `/admin/school/notes` — text in, dated events
  and scraped pages out. It exists because a whole class of thing a Year 6 parent asks about
  **cannot reach Term Time through any existing source**: secondary-school open evenings arrive in
  a parents' WhatsApp group, one school and one date and one link per message, and none of it is
  Kilmorie's so neither the mailbox, the calendar feed nor the website crawl will ever see it.
  Almost no new machinery: the note becomes an ordinary `SchoolDocument`, the dates come out
  through the same `SchoolEventExtractor` that reads newsletters, and the links are fetched by the
  same `SchoolLinkFetcher` that serves the admin Fetch button — SSRF guard, per-hop redirect
  revalidation and all. Deliberately no date picker and no per-event form: retyping four messages
  into a structured form is slower than reading them. Load-bearing bits:
  - **A note is PUBLIC with no approval, and that is the rule working rather than a hole in it.**
    The queue exists because mail arrives from somebody else and nobody has read it; a note is
    typed by an administrator who has, so pasting it *is* the decision and a queue would mean
    approving your own typing. `SchoolNoteService` is now the only writer of `PUBLIC` besides
    `withApproval` and the two already-public sources.
  - **`SchoolSourceType` gains two members and `PASTED_NOTE` is LAST — least authoritative of
    all.** A note is a transcription of somebody else's message with no publisher behind it, so
    anything a school publishes for itself beats it, and the collision is the common case not a
    corner case: the note says "Harris Boys — 17 Sept" and the school's own page, fetched from the
    link in that same note, says the same evening with a time and a booking address. Both land on
    one `SchoolIds.eventId`. The ordering is the whole of what makes the page win.
  - **`EXTERNAL_PAGE` is not tidiness.** `SchoolQueryService.COMMUNICATION_SOURCES` — the
    allowlist behind "what did the school send last week" — contains `WEBSITE_PAGE`, so a
    secondary school's admissions page filed under that type is reported to a parent as something
    Kilmorie published. Invisible while every fetched third-party page was `RESTRICTED`; notes are
    public and so is everything fetched from them, so it would have started on the first note.
    Only **new** fetches are relabelled — the document id derives from the source type, so
    relabelling existing rows would orphan them, and the pre-existing ones are all restricted.
  - **`SchoolLinkFilter.isWorthOffering` is deliberately NOT applied to a note's links.** It drops
    bare homepages, which is right for a mail footer and wrong here: "St Matthew's Academy Catholic
    school: <homepage>" **is** the message, and filtering it discards the only address that school
    has in the note. Every link is also fetched without being offered — the "record links, never
    follow them" rule is about *email*, where a sender the school does not control chose the
    address; these were pasted by the one person allowed to press Fetch. Nothing about the guard is
    relaxed, only the ceremony.
  - **`SchoolEvent.withYearGroupScope` narrows a note's events to Year 6**, and the events of the
    pages its links led to. Applied only where the source named no year group itself, so "Years 5
    and 6 welcome" survives. Without it four secondary open days land whole-school and every
    Reception parent asking what is on this week is shown all of them. `yearGroups` is not part of
    `eventId`, so narrowing does not move the row.
  - **The extractor can now return a link and `verbatimUrl` checks it.** A URL is exactly the shape
    of thing a model completes rather than copies — right host, guessed path — and an invented
    booking link reaches a parent under the same confident citation as the date. Any value not
    found literally in the document body is discarded; a plain substring test, because the question
    is "did you copy this", not "is this well formed".
  - **Two prompt changes ship with it and the feature is close to useless without them.**
    `SchoolTopicGuardrail` answered only for "a UK primary school", so "when is the Kingsdale open
    evening" could be refused before reaching a tool. `SchoolSystemPrompt` gains a Year 6 section
    requiring the assistant to **name the school every time** (a date attached to no school is
    useless to somebody holding four in their head and dangerous if they act on it for the wrong
    one), to say these are not Kilmorie's announcements, and to prefer a school's own page over a
    note when they disagree.
  - **Two faults the reviewer caught, both silent, both defeating the mechanism they sat inside.**
    (1) The year-group scope was a pass in `SchoolNoteService` straight after its own call to
    `SchoolLinkFetcher.fetch` — but `fetch()` re-extracts and rewrites a document's events on
    **every** invocation, including from `POST /links/{id}/fetch`, the admin Fetch button. So
    retrying a note's `FAILED` link, which a 403 from a school's site makes routine, re-wrote its
    events whole-school. Now `SchoolLinkFetcher.writeEventsFor` narrows them from the document,
    which persists the scope, on every path; scoping *before* the write also stops it touching
    rows `SchoolEventWriter` declined on precedence. (2) A fetched page was classified with the
    address **requested** rather than the one answered, so a school-host link redirecting off the
    host stored third-party content as `WEBSITE_PAGE` — public from a note, therefore in the
    answer to "what did the school send last week", under the school's name. **The split that
    resolves it matters, because the obvious fix over-reaches:** only classification follows the
    resolved address; `sourceRef` stays the requested one, because it feeds `SchoolIds.documentId`
    (re-keying orphans every fetched document and forks a new one whenever a redirect target
    moves), because `publishedAtFor` matches on it, and because it is the honest citation.
    Neither fix's wiring is coverable offline — the first URL must pass `isFetchableUrl` and a
    loopback server never does, the same limit `SchoolLinkFetcherRedirectTest` documents.
  - **Not done:** no image paste (the messages usually arrive as a screenshot, so transcribing one
    is still manual), links are followed one level only, and duplicate events remain possible —
    collapsing the note's row onto the page's depends on the extractor titling both with the
    school's name, which is a prompt instruction rather than a guarantee.
  Backend 1542 tests, frontend 882. See `docs/runbooks/term-time.md` ("Pasting a note in").
- termtime-newsletter-tier-and-recency: Term Time could not answer anything about the newsletter of
  11 September 2026, and the two reasons were independent. **The newsletter had been ingested
  perfectly and then hidden.** The school moved its weekly newsletter out of the mail body onto its
  parent portal that week, so the email is now a covering sentence and a link;
  `SchoolLinkFilter.isAutoFetchable`'s carve-out followed it correctly (prod Loki, 2026-09-11
  15:22:35Z: `Auto-fetched school newsletter …/parentportal/newsletter/?id=163`), and then
  `SchoolLinkFetcher` filed the page at the **parent email's** tier, which is `RESTRICTED` by
  construction — so it sat in the approval queue, invisible to a chat whose audience is
  `PUBLIC`-only. That contradicted the reasoning that permits the fetch at all: `isAutoFetchable`
  allows it *because* "the website crawl already reads that host wholesale", and
  `SchoolIngestService.ingestWebsite` stores every other page on that host at `PUBLIC`. The same
  page got opposite tiers depending on which door it came through, and it is absent from
  `/googlesitemap.asp` (218 entries, no `/parentportal/`) so only the email door exists. Now
  `SchoolLinkFetcher.tierFor` decides on the **content**: an auto-fetchable school newsletter is
  `PUBLIC`, everything else still inherits. Load-bearing bits:
  - **One predicate, reused.** `isAutoFetchable` answers both "may ingest follow this" and "is this
    the school's own published page", so there is no second list to drift. It therefore applies to
    the admin **Fetch** button too — the content decides, not the requester.
  - **It only applies on first write.** `SchoolDocumentWriter` carries `prior.visibility()` forward
    with every other human decision, so newsletters already stored `RESTRICTED` stay that way and
    need approving in the console — which is also the only path that re-embeds the chunks. No
    Mongock unit: a change unit could flip Mongo and would leave Elasticsearch disagreeing, which
    is the exact inconsistency `SchoolApprovalService` exists to prevent.
  - **A failed auto-fetch used to be permanent.** `recordLinks` skipped every existing link row
    *before* reaching the fetch, and an email's text never changes, so one timeout left a `FAILED`
    row that was re-found and re-skipped for ever. `FAILED` is now retried; `PENDING` is not,
    because that is the approval queue and re-fetching it would be ingest making the decision the
    queue exists to ask for.
  **The second fault is why the conversation was bad even where the data was present.**
  `searchSchoolInformation` is pure similarity — top-8, threshold 0.3, no recency signal anywhere —
  and a dozen weekly newsletters are worded almost identically, so they cluster and the one
  returned is close to arbitrary. Measured live on prod before the fix: "what was in the newsletter
  from last week?" answered from **10 July** *and reported 10 July as the latest*; "stars of the
  week" from **3 July**; and, asked what the Big Half raised, it produced **£2,230** cited to "the
  newsletter of 11 September" — a figure in no source at all, under a specific confident citation,
  which is the most damaging output this assistant can produce. `SchoolQueryService`'s own premise
  ("everything date-shaped is answered here, by query") covered events and not documents, and
  questions about documents are just as date-shaped. New `getRecentCommunications(from, to)` over
  `SchoolDocumentRepository.findPublishedBetween`. Load-bearing bits:
  - **It is the only tool that can say a window was EMPTY.** An empty top-k means "nothing was
    similar", never "nothing exists" — so until this existed there was no way to tell a parent
    there was no newsletter last week, only a way to hand them the nearest old one.
  - **`CALENDAR_FEED` is excluded from what counts as a communication, and that is not tidiness.**
    `ingestCalendar` re-stamps its single container document (`"The school's published calendar
    feed."`) with `Instant.now()` every pass — every 30 minutes in prod. Included, that stub would
    be the newest thing the school had "published" in every window for ever.
  - **Documents are included whole or left out and named, never truncated.** A model reading half a
    newsletter cannot tell it is reading half, so it reports that the newsletter does not mention
    something that was in the paragraph after the cut. The first document is included regardless of
    size, or a school that publishes one long newsletter gets an index with no text under it.
  - **The window is clamped to 62 days from the RECENT end, not refused.** The caller is a model
    turning "this term" into two dates, and the recent half is the half being asked about. The end
    bound is the end of the last *day*: the newsletter that started all this was sent at 15:03 on
    the closing day of the window anyone would ask about.
  - Two system-prompt rules with it: route "what did the school send" questions here rather than to
    search, and **never attribute anything to a source you were not shown** — no naming a document
    you have not read, no carrying a fact from one source across to another's date.
  See `docs/runbooks/term-time.md` ("It is public, and that took a second go to get right" and
  "'Was there a newsletter last week' is a query, not a search").
- 050-logwatch-backlog-3: 049's mute rules were correct and **SIM-28 was re-filed anyway, every
  night**, because muting was decided one rule at a time. `Ignore.mutes` requires every variant to
  match *that* rule, and SIM-28's group has five distinct messages from one logger — three saying
  `context canceled` and two reporting the same deploy-time teardown from the SQL driver
  (`sql: transaction has already been committed or rolled back`, `database connection lost:
  driver: bad connection`). No single phrase can cover all five, so a group of mixed third-party
  noise was structurally unmutable however many rules were added. `LogWatchProperties.mutedBy` now
  returns **the set of rules that between them disown every variant**, and the safety property is
  unchanged and is the point: one message no rule claims and the whole group is filed, so a real
  fault under a muted logger un-mutes it on the next scan. Load-bearing bits:
  - **A group covered by two rules counts once in `mutedSignatures` and is attributed to both.**
    The two numbers no longer agree by construction and must not be made to — `muted` is how many
    problems went unfiled; the reasons are how an over-broad rule is spotted. The old
    `sum(mutedByReason)` would double-count exactly the case this change exists for.
  - **The two new Temporal rules are justified by timing, not by reading the message.** Across the
    14 days to 2026-09-14 all 26 occurrences of both phrases landed within a minute of a container
    recreate (09-06 09:00, 09-10 06:00, 09-13 18:30 and 20:04 — every one a deploy) and none at
    any other time. `database connection lost` is the kind of line that should not be muted on its
    wording alone; the rule's `reason` records the evidence and the condition for deleting it.
  - **SIM-47 could not be muted at all, and that is a property of the group, not the rules.**
    Spring's `BeanPostProcessorChecker` emits six distinct messages (Embabel's
    `embeddingTrackingConfiguration`, Spring AI MCP's `serverAnnotatedBeanRegistry` and
    `McpServerAnnotationScannerAutoConfiguration`, OpenTelemetry's `otelMapConverter` and
    `PropertiesConfig`) and `MAX_VARIANTS` lists five — a capped group is never muted. It fires on
    every boot, so it re-filed after every deploy. Fixed with a `logging.level` entry in the
    backend instead, which also keeps the volume out of Grafana Cloud.
  - **`logging.level` keys containing `$` must be bracketed.** `logging.level` binds as a map and
    Spring's relaxed binding **strips the `$`** from an unbracketed key, so the level lands on
    `...PostProcessorRegistrationDelegateBeanPostProcessorChecker`, a logger no class owns. Starts
    cleanly, silences nothing — the silently-ignored-config theme again. `LoggingLevelConfigTest`
    binds the shipped YAML and asserts the bound key, and was confirmed to fail on the unbracketed
    form before being trusted.
  - **Not fixed, and now quiet anyway:** SIM-29 (Alloy re-shipping history as `timestamp too old`)
    last occurred 2026-09-11 and did not recur across the 09-13 deploys — but `alloy` was not
    recreated by either of them, so the question 048 named (does the positions file survive a
    *recreate*) is still untested. The absence sweep will close it around 19 September regardless;
    that is a statement about the logs, not about the cause.
  Verified in production while diagnosing this, both from Loki: the platform backup **now works**
  (`Platform backup completed`, 01:04 on 2026-09-14, first successful run ever), and the FontBox
  silencing works (a school ingest at 06:28 produced none). The absence sweep has **never yet
  closed a ticket** — SIM-27/33/36 were closed by hand on 09-10 — because nothing has been absent
  for the full 7 days; the first closures fall due 17–21 September.
  See `docs/runbooks/logwatch.md` ("Muting third-party noise" and "When a rule cannot reach it").
- 049-logwatch-backlog-2: The fourteen open `factory:logwatch` tickets on 2026-09-13 were four
  problems, one already fixed, and six that no code in this repository could ever fix.
  - **The platform backup STILL had never run.** 048 was right that the `deployer` image lacked
    `python3`, and fixing it only moved the failure one layer in: with the prerequisites met the
    script reached its first `docker` calls and every one of them failed, because
    `backup-platform.sh` addressed the datastores by their compose **service** names
    (`POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-langfuse-db}"`). `docker exec` takes a container
    name or id and knows nothing about services; compose calls the container
    `simonrowe-dev-monorepo-langfuse-db-1`. So `sweep_orphans` warned "could not sweep /backups"
    (SIM-46) and `dump_postgres` died on "pg_dumpall --roles-only failed" (SIM-43) — **neither
    message names a container**, which is why the first read as a ClickHouse volume-permissions
    problem and the second as a Postgres one, and SIM-34 is the same incident seen through
    Temporal's activity-failure WARN. This never worked anywhere and was never environment-
    specific. `restore-platform.sh` carried the identical defect and is the sharper illustration:
    its own `wait_for_health()` has always built `${COMPOSE_PROJECT}-${service}-1` correctly, so
    the two halves of one file disagreed about what a container is called. Both now resolve via
    `docker ps --filter label=com.docker.compose.project/service` — the label rather than a
    formatted name, because the label is what compose itself matches on. **Only `--dry-run` falls
    back to the formatted name**; a real run dies naming the service and project, because
    guessing in a real capture is how "No such container" gets attributed to Postgres.
    `scripts/test/test-platform-container-resolution.sh` stubs `docker` on PATH and answers the
    label query with a name deliberately *not* of the `<project>-<service>-1` shape, so the
    assertions cannot pass on the fallback path.
  - **`org.apache.fontbox.ttf` is a different package root from `org.apache.pdfbox.pdmodel.font`,**
    which is why 048's PDFBox silencing covered none of SIM-44/SIM-45. FontBox is the font parser
    PDFBox delegates to; it logs one WARN per table per font per document ("No PostScript name
    data is provided for the font Wingdings3", "Format 14 cmap table is not supported and will be
    ignored"). Both are statements about what it chose not to read, not failures. Scoped to `ttf`
    rather than all of `org.apache.fontbox`, so a font that cannot be parsed at all still reports.
  - **Six tickets had no fix in this repository and now have a mechanism instead.** Temporal logs
    `context canceled` and `pq: canceling statement due to user request` at ERROR (SIM-28, SIM-32,
    SIM-40), Alloy logs an ERROR per container that exits between discovery and tailing (SIM-31),
    and Dependency-Track logs a WARN per malformed pypi range it mirrors from OSV — 195 lines in
    two seconds (SIM-41, SIM-42). Their recorded disposition was "not fixed, deliberately", which
    is a backlog entry pretending to be a decision: they were re-filed nightly for ever, and the
    absence sweep could never close them because they never stopped happening.
    `factory.logwatch.ignore` is a curated list of `(reason, container, contains)` rules.
    Load-bearing bits:
    - **Every variant must match, not just the group's leader.** A group is keyed on the emitting
      code and one logger can emit two different faults — the standing objection to source-key
      grouping, which `variants` exists to answer. Leader-only muting would let a real failure
      ride out of sight inside a group whose most frequent message is noise.
    - **A group whose variants were capped is never muted**, however well the visible ones match:
      `MAX_VARIANTS` limits what is *listed*, not what was *seen*. Same distinction, and the same
      reason, as the per-run cap's veto over the absence sweep.
    - **Muting runs before the occurrence floor and the cap.** Third-party noise is high-volume by
      nature, so muting last would let it occupy the five slots it is being muted from.
    - **`mutedSignatures` is counted separately from `signaturesDropped` and must never be folded
      into it.** Dropped means "could not fit", which is why it vetoes the absence sweep; muted
      means "excluded on purpose, every run". Collapsing them makes the sweep permanently inert on
      any stack that mutes anything.
    - **Every run's detail names the rules, not just a count.** An over-broad rule is invisible in
      the tickets by definition, so the run detail is the only place it can be seen.
    - **In `application.yml`, not behind an env var.** A rule is a statement that a class of log
      line belongs to somebody else — a code-review decision that wants its `reason` in the diff,
      not something to change on a host at 2am while the thing it hides is the outage.
    - `LogWatchIgnoreRulesTest` binds the shipped YAML and runs the **real captured production
      lines** from all six tickets through it, and asserts Alloy's `final error sending batch`
      (the SIM-29 ingest-failure signal, the one thing this module must never stop hearing) stays
      audible from the same container.
    The muted tickets are closed by the existing absence sweep after `resolve-after` (7d) — the
    same path a fixed problem takes — so nothing needs cancelling by hand.
  - **Not changed, deliberately:** SIM-39 (Embabel `MISSING_GOALS`) is already fixed — #161
    converted both agents to plain `@Component`s on 2026-09-10 and the occurrences it cites are
    from 06:00 that morning, before the deploy; it will sweep itself. SIM-29 (Alloy re-shipping
    log history as `timestamp too old`) is a **real** finding and is deliberately not muted; it
    still needs the one host fact 048 named — whether `alloy-data` is actually mounted on the
    running container and whether the positions file survives a recreate.
  See `docs/runbooks/logwatch.md` ("Muting third-party noise") and
  `docs/runbooks/platform-backup-restore.md` §0a.
- term-time-connection-resilience: Term Time said it could not connect roughly once a minute,
  and the cause was that **the STOMP socket carried no traffic at all between questions**.
  Spring's simple broker sends no heartbeats unless it is handed a `TaskScheduler` — the default
  is `"0, 0"` — and it *advertises* that zero in its CONNECTED frame, which disables the client's
  heartbeat however the client is configured. A silent WebSocket is indistinguishable from a dead
  one to every proxy on the path: nginx's `proxy_read_timeout` defaults to **60s**, Cloudflare's
  is ~100s, pinggy has its own. So the socket was closed underneath any reader who spent a minute
  reading, and the next question went nowhere. Nothing logged anything. Load-bearing bits:
  - **`WebSocketConfig` injects `messageBrokerTaskScheduler` by qualifier rather than declaring a
    `TaskScheduler` bean.** `@EnableWebSocketMessageBroker` already publishes one for exactly this
    purpose, and a second `TaskScheduler` bean would make Boot's task-scheduling auto-configuration
    back off (`@ConditionalOnMissingBean(TaskScheduler.class)`) and quietly move every `@Scheduled`
    method in the application onto whichever pool was declared last. `{10000, 10000}` is stated
    explicitly even though `setTaskScheduler` already defaults to it — that default is a side
    effect of a setter whose name says nothing about heartbeats.
  - **`WebSocketHeartbeatTest` exists because nothing else would notice.** A context with
    heartbeats off starts perfectly, serves every request and passes every other test in the suite.
  - **The nginx block carrying every production chat socket is `api.simonrowe.dev`, not the
    term-time one.** The frontend image is built with `VITE_API_BASE_URL=https://api.simonrowe.dev`,
    so the browser opens `wss://api.simonrowe.dev/ws/chat` *even from `term-time.simonrowe.dev`* —
    the term-time block's `/ws/` with its careful `proxy_read_timeout 300s` is same-origin only and
    is not the one a released build uses. `api` now has its own `/ws/` block, carrying the
    maintenance-flag check that `location /` gave it before the split; `location /` keeps nginx's
    default timeouts, because it also serves every ordinary request and a 300s ceiling there would
    hold connections open for five minutes against a backend that has stopped answering.
  - **`sendMessage` was `if (connected) publish(...)` with no `else`.** A question typed into a
    page whose socket had quietly timed out was discarded silently, under a typing indicator that
    never stopped. It is now held in **one slot** and published on reconnect; if that has not
    happened in 20s the page is told, the empty reply bubble is removed and the indicator stops.
    One slot rather than a queue: a second question supersedes the first, because two answers
    arriving at once is worse than one lost draft.
  - **Four connection states, and only `offline` renders anything.** `reconnecting` is silent on
    purpose — it is typically over inside a second, and announcing it is what made a working page
    feel broken. `offline` shows one quiet "Reconnecting to Term Time…" line with **no button**:
    recovery is automatic (exponential from 1s, capped at 15s — the cap *is* how long a reader
    waits after the service returns, since nobody presses anything).
  - **Staleness is judged on a connection epoch, not the session id.** A reconnect reuses the same
    session deliberately, and `deactivate()` is asynchronous, so the outgoing client's close event
    lands after the replacement is live. Compared on session id those two are identical and the
    stale close is scored as a failure of a connection that is fine.
  - **Only a 503 hands over to the landing page.** While `offline`, the page polls
    `probeSiteStatus()` every 10s; 503 means a deploy, so it reloads and lets nginx's maintenance
    page take over. 502/504/network-error leaves it where it is — the transcript is still readable
    and the socket is still retrying, and replacing that with an error page is a downgrade. It
    never polls while connected. The probe uses this origin plus this **path**, never the full
    `href`: query and hash are the only third-party-influenceable part and no server block reads
    them, and returning the visitor to their exact address is `reloadPage()`'s job anyway. The
    landing pages *do* poll the full href, correctly — there the poll and the reload target the
    same URL.
  - **Both landing pages now carry the visitor's own light/dark preference and bring them back to
    the URL they were on.** Two inline scripts each — not a loosening of the "nothing is fetched"
    rule, since neither requests anything at parse time and both pages are complete with scripting
    off. The theme script writes `data-theme` for **both** values, unlike the main site which only
    writes it for light: here an absent attribute is what the `prefers-color-scheme` fallback
    claims, so leaving it off for dark shows a light page to somebody who chose dark on a light OS.
    The return poll targets `window.location.href`, never `/` — the flag is evaluated per server
    block, a visitor may be deep in the site, and Term Time is a different hostname entirely. The
    pages also name the site from the hostname. `test-nginx-maintenance.sh` gained term-time
    coverage, a `HEAD`-parity check (the poll uses HEAD, so a HEAD that disagreed with GET would
    either never return anyone or return them into a broken site) and a narrowed "no external
    asset" assertion that catches `<link>`, `<script src>`, `src="http"`, `@import` and `url(`
    rather than any `<script>` at all.
  - **`reloadPage()` lives in `services/siteStatus.ts` beside the probe.** `window.location.reload`
    is non-configurable in jsdom, so a caller reaching for it directly cannot be tested at all.
  See `docs/runbooks/term-time.md` ("It keeps saying it cannot connect") and
  `docs/runbooks/deploy.md` ("The maintenance page brings people back on its own").
- 048-logwatch-backlog: The nine open `factory:logwatch` tickets were six distinct problems.
  Five are fixed here; the sixth is recorded, not fixed. **The one that mattered: the nightly
  platform backup had never once run.** `scripts/backup-platform.sh` executes in the `deployer`,
  which is a second instance of the software-factory image — and that image installs
  `ca-certificates git curl jq` and nothing else, while the script requires **`python3`** (all its
  JSON: the manifest, the ClickHouse row counts, every Drive API response) and **`zip`** (the
  archive). It aborted at `check_prerequisites()` every night from the day 034 shipped, so no
  Langfuse / Dependency-Track / Temporal capture has ever reached Google Drive (SIM-30, and
  SIM-34 is the same incident seen through Temporal's activity-failure WARN). Nothing outside the
  container log noticed: Temporal retried and gave up, the run landed as failed in a collection
  nobody reads, and the console rendered it identically to a transient failure. The jq-not-python3
  decision from 036 is unchanged and still correct **for the deploy settle loop**; the platform
  backup is a different consumer. `python3-minimal` suffices (the script uses only `json`, `os`,
  `sys` — verified on arm64). `scripts/test/test-platform-backup-prereqs.sh` now reconciles the
  script's `check_prerequisites()` against the Dockerfile's runtime `apt-get install` line, with
  `docker` (bind-mounted from the host) and `sha256sum` (coreutils) as the only exemptions —
  neither build step runs the other, so this test is the only thing tying the two files together.
  The other four:
  - **`ContentAggregationAgent` and `WeeklyDigestAgent` are not Embabel agents and never were**
    (SIM-27). Both carried `@Agent`/`@Action` while every caller invokes them directly as Spring
    beans, and neither declared an `@AchievesGoal` — so `DefaultAgentValidationManager` logged
    `MISSING_GOALS: Agent '...' must have at least one goal defined` at **ERROR** on every boot.
    An agent with no goal can never be planned or executed, so the annotation was provably
    decorative. Now plain `@Component`s. **`@Component` is explicit and load-bearing**: `@Agent`
    is itself meta-annotated `@Component`, so dropping it without adding one removes the bean and
    breaks constructor injection at startup. `Ai` injection is unaffected — `ArticleSectionWriter`
    and `DigestComposer` were already doing exactly this from plain components.
  - **The mail health indicator is off** (SIM-33). `/actuator/health` is not an information
    endpoint here: the backend's Docker healthcheck greps its body for `{"status":"UP"}` and
    `monitor-prod.sh` restarts anything Docker calls unhealthy — so everything in that aggregate
    is a **restart trigger**. `MailHealthIndicator` opened a real SMTP connection to Brevo on
    every call (~2,880/day at the 30s interval) and intermittently failed DNS
    (`UnknownHostException: smtp-relay.brevo.com`, 52 times in one day), each failure taking the
    aggregate DOWN and, after three strikes, restarting a backend that was serving every request
    perfectly — to fix a name lookup that has nothing to do with the process. Mail itself is
    untouched; a genuine send failure still surfaces on the request that attempted it. Also takes
    a real bite out of the ~9s `/actuator/health` measured on the Pi.
  - **`org.apache.pdfbox.pdmodel.font` is at ERROR** (SIM-36). PDFBox logs one WARN per
    non-embedded font per document; the school PDFs Term Time ingests reference the Arial/Times
    families without embedding them, so one crawl produced **339 WARNs in an hour**. Scoped to the
    font package, not `org.apache.pdfbox`, so a real parse failure is still reported. Log volume
    is not free — it spends the same Grafana Cloud allowance whose exhaustion blacked out ingest
    for three weeks in August 2026.
  - **Not fixed, deliberately:** SIM-28/SIM-32 are Temporal's own shutdown/cancel noise
    (`context canceled`, `pq: canceling statement due to user request`) and SIM-31 is a transient
    tail against a container being removed — third-party, nothing in this repo to change.
    **SIM-29 is a real finding with an unresolved cause**: alloy re-ships `nginx` /
    `temporal-create-namespace` / `pinggy` log history on restart and Loki rejects it as
    `timestamp too old`, 22 times across 8 occasions in 14 days, despite `loki.source.docker`
    persisting positions and the `alloy-data` volume being declared since #141. Note
    `grafana/alloy` is pinned to **`:latest`** in `docker-compose.prod.yml` — the same
    unpinned-image shape as the ClickHouse incident. Needs one fact from the host (whether the
    volume is actually mounted on the running container, and whether the positions file survives)
    before it can be fixed rather than guessed at.
  **And the backlog now closes itself.** `com.simonrowe.factory.linear`'s `IssueResolver` +
  `AbsenceSweep` are the other half of the sink: at the end of every scan, any `logwatch`
  fingerprint unreported for `factory.logwatch.resolve-after` (**7d**) gets a comment and is moved
  to Done. `FACTORY_LOGWATCH_RESOLVE_WHEN_CLEAR` is the **only flag in that module that defaults
  ON**. Load-bearing bits:
  - **The sweep takes no "what is still present" list, on purpose.** It always runs *after*
    filing, and every filing advances that fingerprint's `lastSeenAt` — so "still happening" and
    "recently seen" are the same fact and the quiet period is the only input. A present-set would
    be a second, independently-wrong answer to one question.
  - **Four conditions, each of which is a way to close a ticket about a live problem.** The
    source must be healthy (an ingest outage otherwise reads as universal success and closes the
    whole backlog, *including* the ticket the same run just filed to say it cannot see); the read
    must not be truncated; the window must be ≥1h (a post-deploy scan covers ~5 minutes, in which
    almost everything is absent because 5 minutes is short — enforced structurally in
    `LogWatchWorkflowImpl`, not only by the caller passing `false`); and **nothing may have been
    dropped by the per-run cap** — the subtle one, because the cap limits what is *filed*, not
    what was *seen*, so with more live problems than the cap the overflow never reaches the sink,
    its `lastSeenAt` never advances, and a busy stack closes the tickets about its own busiest
    failures. Useful consequence: the sweep is inert while the backlog exceeds `max-per-run` and
    comes into effect as it shrinks.
  - **Done, never Cancelled.** The sink reads a cancelled issue as "never tell me again", so an
    automatic cancel would permanently suppress a problem that had merely paused. Closing as
    completed leaves the fingerprint attachment in place, so a recurrence files a linked
    `FILED_REGRESSION` — being early costs one extra linked ticket, not a lost report, and that
    recoverability is the whole reason 7 days is an acceptable threshold.
  - **It never touches an issue a human has started**, and counts those separately. Someone
    mid-fix is very often *why* the logs went quiet. It also never crosses producers (an absent
    `cvefix` finding means something else entirely; an absent `deploy` failure means nothing),
    and never rewrites the description.
  - **`LinearGateway.updateIssue` now omits a null description rather than sending it.** GraphQL
    reads an explicit null as "set this field to null", so the state-only update the sweep
    performs would otherwise have erased the description of every issue it closed.
  - `TeamContext.completedStateId` is **nullable** where `triageStateId` is not, and a team
    without one reports `SweepReport.unavailable` rather than silence — "nothing was closed" and
    "nothing *can* be closed" must not present identically. A state literally named `Done` always
    wins over board order.
  - **A dry run needs BOTH dry-run flags, and only one of them was there.** The sweep is not
    short-circuited on a dry run — it calls through so the sink can report what it *would* close,
    because a preview that skips half the run is not a preview — but `IssueResolver` originally
    gated the write on `factory.linear.dry-run` alone. That is the sink's *standing* posture, not
    the *request's*, so a manual "Dry run scan" from the console answered "nothing will be filed"
    in its API response and then moved real tickets to Done with real comments on them, on any
    stack where the sink is (correctly) configured to write. `AbsenceSweep.dryRun` now carries the
    request-level flag and the sink honours whichever of the two is set. Caught by the reviewer
    bot on #161, not by any test — every test set the two flags together.
  - **Stub `sweepResolved` in every workflow test, not just the sweep ones.** An unstubbed Mockito
    mock returns null, the workflow NPEs, and Temporal retries a failed *workflow task*
    indefinitely — so the symptom is a test that hangs forever rather than one that fails.
  See `docs/runbooks/logwatch.md` ("Closing tickets again: the absence sweep"),
  `docs/runbooks/linear.md` ("The absence sweep") and
  `docs/runbooks/platform-backup-restore.md` §0.
- sbom-cyclonedx-17-rejected: The three **image** SBOM uploads to Dependency-Track have failed
  with `400` on **every** Publish run since the trivy switch (#140, 2026-08-31) — and the
  workflow was green each time, because the `sbom` job is `continue-on-error` and
  `DependencyTrack/gh-upload-sbom` logs the status code but **not** the response body. The body
  reads `{"title":"The uploaded BOM is invalid","detail":"Unrecognized specVersion 1.7"}`:
  trivy 0.74.0 emits **CycloneDX 1.7** with no flag to emit anything older, and
  Dependency-Track **5.0.3** ingests 1.6 at most (1.7 landed upstream in **5.1.0**, released
  2026-08-27). The two *dependency* SBOMs were never affected — `npm run sbom` pins
  `--spec-version 1.6` and the Gradle plugin emits 1.6 — which is exactly why the failure read
  as a partial blip rather than a broken feature. Cost: the three `-image` projects served their
  last **syft-era** BOM from 2026-08-31 09:59 for ten days, so everything 043 says about OS
  package coverage was true and none of it was reaching production. Fixed with a
  `cyclonedx/cyclonedx-cli convert --output-version v1_6` step in `publish.yml` before the
  uploads: measured lossless on this repo's own images (same component count, same purls, all
  `aquasecurity:trivy:SrcName` properties, same dependency graph and `operating-system`
  component — trivy populates no 1.7-only field), and the "not empty" assertion now also fails
  the job unless each BOM declares `1.6`. Two things worth keeping: the temp file in that step
  **must** keep a `.json` extension (cyclonedx-cli infers the output format from the filename
  and otherwise exits `Unable to auto-detect output format`), and the conversion sits *after*
  the two dependency uploads for the same reason the assertion does — a trivy-side failure must
  not stale the Maven and npm data it has nothing to do with. Drop the step once production is
  on Dependency-Track >= 5.1.0. See `docs/runbooks/dependency-track.md` ("CycloneDX 1.7").
- 047-term-time: **Term Time**, a school assistant at `simonrowe.dev/school` for Kilmorie Primary
  School (Lewisham — spelled Kilmor**ie**), `com.simonrowe.school`. Public tier over the school's
  own published data, restricted tier over the school mailbox that is **unreachable from the browser** — the page has
  no sign-in at all, so email content reaches a reader only by being approved into the public
  tier. The tiering stays because ingested mail still needs somewhere safe to sit; what was
  removed is the way in — and, later, the name gate as well (see below).
  **All four phases are shipped and green (backend 1292 tests, frontend 824).** Outstanding: an
  integration test for the public chat path and the `evals/` cases. See
  `specs/047-term-time/tasks.md`. Load-bearing bits:
  - **Publishing a second `VectorStore` bean would delete the first one.** Spring AI's
    Elasticsearch autoconfiguration is `@ConditionalOnMissingBean(VectorStore)`, matching on
    *type*, so a qualified or non-primary second bean still makes it back off and the main site's
    store disappear. `school-embeddings` is therefore held behind a `SchoolVectorStore` **wrapper**
    that composes rather than extends. Do not "simplify" that away.
  - **A wrapped store never gets `afterPropertiesSet()`.** `ElasticsearchVectorStore` implements
    `InitializingBean` and `initializeSchema(true)` only takes effect from there; because the
    instance is wrapped rather than published, Spring calls the lifecycle method on the wrapper.
    The index would never be created, the first search would return nothing, and nothing would
    error. It is called by hand in `SchoolVectorStoreConfig`.
  - **The store is a null object when disabled, not a conditional bean.** Gating it on
    `school.enabled` while its consumers stayed plain `@Service` beans made the *entire*
    application context fail to start — every unrelated controller test in the suite went red.
    `SchoolVectorStore.disabled()` keeps the graph intact so the controller can answer "not
    switched on".
  - **Fail-closed tiering, in the constructor.** `Visibility` defaults to `RESTRICTED` in
    `SchoolDocument`'s compact constructor; a classifier writes only `proposedVisibility`; only
    `withApproval` writes `PUBLIC`. `withNameGateBlocked()` used to outrank approval and make the
    gate a gate rather than advice; the gate is gone and that path is dead, so **approval is now
    the only writer of `PUBLIC` and nothing can veto it**.
  - **`SchoolSourceType`'s declaration order IS the source precedence** (`CALENDAR_FEED` > `EMAIL`
    > `WEBSITE_PAGE` > `PDF`) and reordering it silently changes which source wins. Not
    theoretical: the school's term-dates page still shows the *previous* academic year while its
    calendar feed carries the current one, and both are ingested. `SchoolIds.eventId` keys on
    academic year, date and normalised title — deliberately **not** on the source — so the two
    collide on one row and precedence picks the winner.
  - **The sender allowlist matches ADDRESSES, never display names.** `system@insighttracking.com`
    sends mail whose display name is "Kilmorie Primary School". Full-text matching on the school
    name is also wrong — Kilmorie Road is a street, so it catches estate agents. ParentPay is
    denied outright rather than ingested as restricted.
  - **An empty `StaffDirectory` blocks every name**, so the gate is inert-closed rather than
    inert-open before the crawl runs — which is why `primeStaffDirectory()` runs at
    `ApplicationReadyEvent` rather than waiting for the first nightly crawl. The default staff
    URL matters for the same reason: `/our-school/staff` is a 404 and `/our-school/our-staff`
    is the real page, and getting it wrong leaves the directory empty with one WARN to show
    for it.
  - **The name gate is DELETED, and reinstating it would reverse a decision made three times.**
    `StaffNameGate` compared capitalised word pairs against the published staff directory, blocked
    any document naming someone it could not place, and redacted names out of anonymous answers.
    It went in three steps, each on the owner's instruction: off website pages (it matched
    "Contact Us" as a person, marking 162 of 162 crawled pages restricted), then narrowed to
    pupils only (it had redacted "Taylor Shaw", the *catering company*, while "Edwards & Blake"
    survived), then removed altogether — pupils' names included. It blocked 98 of 99 emails, so
    its "Publish anyway" override was pressed as a matter of routine, which is what a control
    that has stopped controlling anything looks like. Gone with it: the `blocked` count on the
    bulk-approval response, `nameGateBlocked` on the documents API, the override button and the
    "Name-gate blocked" filter. `SchoolDocument.nameGateBlocked` survives as a **never-written**
    field so stored documents still deserialize. **Approval is now the entire control** over what
    reaches the public tier — which is what was always doing the real work.
  - **Events come from four sources and the website was missing for four phases.** Only the email
    path called `SchoolEventExtractor`, so website pages and the PDFs they link to — the
    enrichment timetable, term dates and lunch menu, the most current documents the school
    publishes — produced **zero** events while sitting in the index as prose.
    `SchoolIngestService.extractEventsFrom` closes it. Two filters run *before* the model because
    both are free and it is not: a `DATE_LIKE` regex (pure cost control over ~160 pages re-read
    every crawl, most of which contain no date) and then the ingest cutoff applied to the model's
    **output**, never to the document — a page last edited in 2022 can still announce a date this
    term.
  - **`getEventsBetween` reads both stores, deliberately.** An event row carries a date and a
    title; the letter announcing it carries the time, venue, what to bring, the booking link and
    often a PDF, and that lives in Elasticsearch rather than `school_events`. The tool now returns
    the dated rows plus a prose search **keyed on the titles it just found** — not on the user's
    phrasing, which pulls back whatever is topically near "this week" instead of the specific
    letter.
  - **Exactly one kind of link is followed automatically.** The rule is still "record links, never
    follow them" — an email can link anywhere and the ingester must not become a general crawler.
    `SchoolLinkFilter.isAutoFetchable` carves out a **newsletter path on the school's own host**,
    because the website crawl already reads that host wholesale so following one reaches nobody
    new. Deliberately not the whole school domain: the parent portal also serves per-family pages.
    The host is checked before the path, or `evil.example.com/newsletter/` would qualify.
  - **`SCHOOL_INGEST_FROM_DATE` has to be enforced on the calendar too, not just on Gmail.** It
    began as an `after:` clause in `GmailIngestService.buildQuery()`, which capped mail correctly
    and did nothing about `CALENDAR_LOOKBACK_MONTHS = 6` — so a full wipe and repopulate put 53
    class trips and assemblies from the *previous* academic year straight back into the admin
    console. `SchoolEventWriter.write()` now drops any event finishing before the cutoff (the
    guarantee, and it also catches past dates stated by an in-window email) and
    `SchoolIngestService.calendarRangeStart()` narrows the feed request to match (the
    optimisation). An event *spanning* the cutoff is kept. **The cutoff deliberately does not
    apply to website documents**: `publishedAt` there is the CMS last-edited date, and the live
    Term Dates page carries May 2026 — filtering on it would delete current information.
    In `docker-compose.prod.yml` the default must be **repeated, not blank**: `${VAR:-}` passes
    an empty *string*, which resolves, so the yml default never applies and there is no cutoff at
    all. `SchoolIngestCutoffTest` pins the two declarations together.
  - **`SCHOOL_DAILY_TOKEN_BUDGET=0` means "answer nothing anonymously", not "unlimited".** An
    unauthenticated LLM endpoint that defaults to unbounded spend is the wrong default.
  - **Model and `promptCacheKey` are set on the school `ChatClient`'s own options**, never under
    `spring.ai.openai.chat`, whose values merge into every per-call `OpenAiChatOptions` in the
    application — the same trap that bans `reasoning-effort` from the yml. Spring AI 2.0's
    `options()` takes the **builder**, not a built object.
  - **`/school` and `/school/` are not the same URL, and the difference is silent.** Bare
    `/school` is served by the MAIN site's bundle — nginx prefix matching is literal so
    `location /school/` does not match it, and Vite's dev server only resolves the directory
    index for the slashed form. Since the Auth0 callback lands on this path, a redirect URI
    without the slash returns from sign-in into the wrong application. Fixed with
    `location = /school { return 301 /school/; }` plus a slashed `redirect_uri`; found only by
    curling the page, because every test in the suite passed either way.
  - **The school chat MUST send `reasoningEffort("none")`.** `gpt-5.6-luna` is a reasoning model
    and OpenAI rejects function tools alongside a reasoning effort on `/v1/chat/completions`
    (`400: Function tools with reasoning_effort are not supported`). Term Time is entirely
    tool-driven, so without it every single turn fails. Same family as the ban on
    `reasoning-effort` in `application.yml`, except here the value arrives from the model's own
    default rather than from configuration — no amount of reading the yml would have shown it.
  - **Term Time streams over STOMP and reuses the site's chat components**, deliberately — same
    `styles.css`, same `ChatMessage` (so markdown, the link policy and tool activity all render
    identically), same `chatStreamReducer`. The wire type is the shared `ChatResponse`, which is
    the whole reason one reducer serves both; a bespoke frame shape would have meant a second
    reducer, and two reducers drift. An earlier cut gave Term Time its own design language and a
    plain JSON POST — that was reversed on request, and with it the ESLint import boundary
    between `src/` and `src/school/`, whose premise no longer holds.
  - **`ChatStreamPublisher` hardcodes `/topic/chat.`**, so reusing it for Term Time published
    every tool frame to the portfolio assistant's topic where nothing was subscribed. The answer
    still arrived and the activity lines simply never appeared — publishing to an unsubscribed
    STOMP topic is legal and silent. Hence a separate `SchoolStreamPublisher` on
    `/topic/school.`, rather than a shared class with a mutable prefix.
  - **`classifyLink` strips any https URL not in the per-message allowlist**, and that allowlist
    is built from streamed *widget* payloads. Term Time emits no widgets, so its own refusal
    advice — "check the school's website" — rendered as unclickable text until `ChatMessage`
    gained an `extraAllowedUrls` prop.
  - **The frontend is a second Vite entry point, not a second project** — `frontend/school/`
    plus `rollupOptions.input`, sharing one `package.json`, ESLint config, Vitest config, CI job
    and Docker stage. Both bundles emit into the shared `dist/assets/`, so `nginx.conf` needs only
    a `location /school/` with its own `try_files` and no second caching rule. Note
    `resolve(__dirname, ...)` needs `@types/node`, which this project does not have — the input
    paths are relative to Vite's root instead.
  - **The Gmail `From` header is parsed to a bare address before the allowlist sees it.**
    `GmailMessage` strips the display name, because the header reads
    `"Kilmorie Primary School" <system@insighttracking.com>` for a third-party sender and any
    check applied to the whole header admits it. Attachments are identified by the presence of an
    `attachmentId`, never a size threshold, and bodies are base64**url** — `Base64.getDecoder()`
    throws on the `-`/`_` alphabet and the symptom is a silently empty message.
  - **The page is unauthenticated and stays that way.** `SchoolAudienceResolver` and the
    token field on the STOMP request are retained deliberately: they are what make a token
    *safe* if one ever appears, since the resolver validates through the real `JwtDecoder` and
    falls back to anonymous on anything it cannot verify. Removing them would leave an unvalidated
    field on a public endpoint.
  - **Term Time reuses the site's `ThemeProvider` and `theme-preference` key**, so light/dark
    carries across from the main site. The no-flash script in `frontend/school/index.html` is
    duplicated from the main entry rather than imported — a module would run after first paint,
    which is the flash it exists to prevent.
  - **Approval must re-embed, or it silently does nothing.** `visibility` is chunk metadata and
    the retrieval filter reads Elasticsearch, not Mongo, so promoting a document without
    rewriting its chunks leaves it public in one store and restricted in the other. The tier also
    cascades to every `SchoolEvent` extracted from the document. Nothing re-checks the text at
    approval time any more — the name gate that used to is deleted.
  - **Email attachments are downloaded and extracted, and dated facts come out of prose via
    Embabel.** `SchoolEventExtractor` uses `Ai.createObjectIfPossible` (not `createObject` — most
    emails have no dated facts and that must be a null, not an exception per newsletter), and
    each PDF attachment becomes its own document inheriting the parent email's tier. Both were
    missing initially: attachment *filenames* were recorded and the bytes discarded, and the mail
    path never wrote a `SchoolEvent` at all, so "what's on this week" saw only the 17-event
    calendar feed. **`contentHash` means an unchanged email skips re-ingest**, so adding an
    extraction step does not backfill — delete the documents and let the next sync re-read them.
  - **Production serves it at `term-time.simonrowe.dev`** from the same `frontend` container.
    The proxy maps only `location = /` onto `/school/`; rewriting every path would break
    `/assets/`, which both bundles share, and the page would render blank. `CORS_ALLOWED_ORIGINS`
    must list the hostname even though its `/api` and `/ws` calls are same-origin — Spring checks
    the STOMP handshake's `Origin` header against that list regardless of who proxies it, and the
    symptom is a page that loads perfectly and never answers.
  - **The school publishes its enrichment timetable, term dates and lunch menu as PDFs**, and
    the enrichment timetable is the most current document on the site. `SchoolPdfExtractor`
    handles both of the CMS's URL conventions and is called from the website crawl — it existed
    for a while without being wired to anything, which presented as the assistant saying "I don't
    know" about clubs while the answer sat one link away.
  - **`scripts/google-drive-auth.sh` is dead code**, found while building this: it uses the
    `urn:ietf:wg:oauth:2.0:oob` redirect Google has removed, so the documented way to re-mint the
    *production backup* token no longer works. `scripts/termtime-gmail-auth.py` is the working
    loopback pattern.
  - **A consent screen left in "Testing" issues 7-day refresh tokens** and adding yourself as a
    test user does not help. The `simon-james-rowe` project was already External / In production /
    unverified and already carried the restricted `auth/drive` scope, so `gmail.readonly` was added
    there rather than to a new project; the OAuth *client* is separate so re-consenting Gmail
    cannot invalidate the Drive backup token. Changing your Google password revokes any
    Gmail-scoped refresh token, silently.
  See `docs/runbooks/term-time.md` and `specs/047-term-time/`.
- 046-linear-dedup-grouping: The `linear` sink's deduplication was never broken — the bug was one
  layer up. `logwatch`'s fingerprint key was `(container, whole-normalised-line)`, and
  `SignatureExtractor.normalise` masks timestamps, UUIDs, numbers, paths and addresses but **not
  free text inside a message**, so any varying prose forked a new fingerprint and a new Linear
  ticket. Live on 2026-09-06: SIM-13/SIM-24/SIM-25 were one backend startup failure phrased three
  ways (`Validation failed with 1 errors:` / `Agent 'ContentAggregation'…` /
  `Agent 'WeeklyDigest'…`), and SIM-16/SIM-23 were one Alloy log-shipping failure with two
  different `error=` payloads. Sixteen tickets in Triage were roughly eleven distinct problems.
  Fixed with a new `SourceKeyExtractor`, identifying the emitting code across six log formats;
  `SignatureExtractor.group` now keys on `(container, severity, discriminatedSource)`, and the
  distinct message templates within a group are carried as capped, always-truthfully-counted
  variants. Load-bearing bits:
  - **`SourceKeyExtractor`'s Temporal handler reads the literal `msg` field, not
    `logging-call-at`.** The latter carries a source line number, so a Temporal upgrade that
    shifts the emitting file by one line would fork every ticket that handler files.
  - **The `logger:`/`line:` prefix on the discriminated source key is load-bearing.** Without it,
    a source key whose text happened to equal some other line's normalised form would silently
    merge two unrelated groups.
  - **FR-009**: `CveFixWorkflowImpl`'s outcome counters had to move in the same commit. It counted
    `COMMENTED_EXISTING` to report "updated"; once `cvefix` files its findings report as `ROLLING`
    (see below) the sink returns `UPDATED_EXISTING` instead, so the old counter would report zero
    updates on a run that did exactly what it was asked — a correct run and a wrong number, with
    no error anywhere. Now `updated = COMMENTED_EXISTING + UPDATED_EXISTING` and
    `regressed = FILED_REGRESSION + REOPENED_EXISTING`.
  - **The one-time re-file was chosen over migrating fingerprints.** Changing the key parts
    orphaned every pre-046 logwatch fingerprint **except the source-health filing's** — see below
    — rewriting each open ticket's attachment URL, or dual-reading old and new fingerprints for a
    grace period, is throwaway code that has to be exactly right, guarding a one-time cost that is
    spread over several nightly runs rather than a single noisy morning: `max-per-run` (default 5)
    caps each scan, and the ~11 distinct problems the new grouping surfaces take roughly three
    nightly runs plus the post-deploy scan to fully re-file. `Fingerprint.VERSION` was
    deliberately **not** bumped: that would additionally have orphaned `deploy` and `cvefix`,
    which have no duplicate-ticket problem to fix. The fourteen logwatch tickets (SIM-11 through
    SIM-21, SIM-23 through SIM-25) were cancelled by hand ahead of the deploy, each with a comment
    recording that this was a regrouping and not a decline — a cancelled ticket suppresses its
    fingerprint under the old code, so the nightly scan stayed quiet in the interval. SIM-10
    (`cvefix`) and SIM-22 (`review-feedback`) were deliberately left alone: their fingerprints are
    not orphaned, so cancelling them would have been real, lasting suppression — `cvefix` would
    have stopped reporting vulnerabilities until SIM-10 was reopened.
  - **The console never learned that those fourteen tickets were cancelled, and now never will.**
    `com.simonrowe.factory.flow.ArtifactCountsReader` (043/044) reads `linear_issues` directly as
    live state for the `linear` artifact node on `/admin/software-factory`, treating a record as
    open when `lastKnownStateType()` is null or an open `IssueStateType`. That field is refreshed
    only when the sink files against the same fingerprint again — which, for the fourteen
    orphaned pre-046 logwatch records, will now never happen. They keep `lastKnownStateType =
    TRIAGE` forever, even though all fourteen were cancelled in Linear on 2026-09-06, so the
    console shows roughly fourteen phantom open tickets — live URLs to cancelled issues — stacked
    on top of the newly re-filed ones, permanently, with no error anywhere. This is the one
    consequence nobody anticipated while designing the fingerprint changeover, which reasoned only
    about the sink (for which orphaning is harmless — Linear itself is truth) and not about a
    second reader that treats the same collection as truth. **Operator action required**: delete
    or close out the fourteen orphaned `linear_issues` documents by hand; see
    `docs/runbooks/linear.md` ("Reading `linear_issues`"). Not fixed here — a production Mongo
    data change is the owner's call, not a code change.
  - **`IssueFiling.commentOnly` became a `FilingMode` enum**: `OCCURRENCE` (today's behaviour —
    `deploy`, `review-feedback`), `REFRESH` (rewrite the description, post no comment —
    `logwatch`), `ROLLING` (as `REFRESH`, plus reopen a completed issue into Triage instead of
    filing a linked replacement — `cvefix`'s findings report, after closing it once caused the
    next scan to file SIM-10 beside the completed SIM-9), `STATUS_UPDATE` (comment verbatim, never
    create — `cvefix`'s clean transition, unchanged from the old `commentOnly` flag).
    **`REFRESH` posts no comment**, so a problem recurring after its ticket has been moved out of
    Triage now produces no Linear notification at all — the history stays in
    `linear_issues.decisions`, just not surfaced. `FilingDecider` is unchanged and still pure; the
    mode-to-action mapping lives one layer up, in `IssueFiler`. **The source-health filing moved
    to `REFRESH` too** (`LogWatchWorkflowImpl.handleUnusableSource`), with its key parts
    (`source-health`, status) left exactly as they were — an undeclared departure from the spec's
    Scope section, which lists any change to the source-health filing as out of scope. It is why
    that filing's pre-046 fingerprints are **not** orphaned by this change (see above) even though
    its mode changed alongside everything else's.
  - **Not fixed, and accepted** (reversed by `temporal-version-skew` above: the window proved not
    to be narrow): `IssueFiling` lost `commentOnly` and gained `mode`, and Temporal's
    `JacksonJsonPayloadConverter` leaves `FAIL_ON_UNKNOWN_PROPERTIES` on — verified in the 1.36.0
    jar during 040 — so a `fileIssue` activity task scheduled by a pre-046 worker and still
    pending when the new image starts will fail to deserialize its input. The window is narrow
    (short-lived nightly workflows), and this repo has direct precedent for tolerating it exactly
    this way: `CveFixProperties` keeps dead `agent`/`ci` blocks solely so a Temporal history
    serialized by the old auto-fix implementation still deserializes.
  - **The `factory:logwatch` and `factory:feedback` labels did not exist in Linear** and were
    created by hand on 2026-09-06 — that absence is why all fourteen logwatch tickets were
    unlabelled, and why SIM-21 was `logwatch` filing a ticket about its own missing label.
    `LinearGateway.teamContext()` caches labels **positively for the process lifetime**, so
    `software-factory` needs restarting before a newly created label is picked up.
  - Two deliberate limits: SIM-11 vs SIM-13 (one incident, two loggers — a source key cannot know
    two loggers belong to one incident) and SIM-19 vs SIM-20 (`MailHealthIndicator` and
    `HealthEndpointSupport`, one incident, two loggers). Only the first is pinned by a regression
    test (`SourceKeyExtractorTest`) — the mechanism is identical, so a future "improvement" that
    merges loggers by incident fails the build regardless of which pair it targets.
  See `docs/runbooks/logwatch.md` ("Grouping: what makes two lines the same problem") and
  `docs/runbooks/linear.md` ("Filing modes"), plus `specs/046-linear-dedup-grouping/spec.md`.
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
    explicit `@Bean` would register it unconditionally and silently ignore it. Both containers did
    register a *workflow* poller on the queue (`@WorkflowImpl` scanning is unconditional); that was
    called harmless and was not, and since `factory-workflow-workers-per-role` only
    `software-factory` does. `DeployerGrafanaCredentialTest` reads the compose file and
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
    module's record throws on another's. (The factory's converter has been lenient since
    `temporal-version-skew`; the untyped read stays because it is the one shape that fits all.) Temporal's `executionStatus` and the workflow's `phase`
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
    image, and `@WorkflowImpl` classpath scanning is unconditional, so **both** registered a
    *workflow*-task poller on the `deploy` queue. That was called harmless, since a workflow only
    schedules activities; it was not, and since `factory-workflow-workers-per-role` only the
    `deployer` does. What stops `software-factory` executing a deploy step is that
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
