# Platform Status Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a public `/status` page showing the commit SHA, build time and start time of every first-party service running in production, the third-party image tags the platform is built on, and a changelog of recent releases each carrying an AI-written summary.

**Architecture:** Every version fact is baked into the artifact at build time and self-reported at runtime — an artifact carrying its own build SHA cannot lie about what is running. The backend gets `springBoot { buildInfo }` plus two generated resources (a copy of `docker-compose.prod.yml`, and 50 commits of `git log` output). A startup component seeds a `platform_releases` collection idempotently; a scheduled sweep fills in AI summaries at ingest so the public read path never calls an LLM. The frontend reports its own SHA from its bundle.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Gradle 8.13 (Kotlin DSL), Spring Data MongoDB, Mongock, Embabel `Ai`, snakeyaml; TypeScript 5.x / React 19, Vite, Vitest, React Router v7, plain CSS with BEM.

**Spec:** `docs/superpowers/specs/2026-08-27-platform-status-page-design.md`

## Global Constraints

- **No new dependencies in either module.** snakeyaml arrives transitively via `spring-boot-starter`; Jackson via `spring-boot-starter-web`. If snakeyaml does not resolve at compile time, add `implementation("org.yaml:snakeyaml")` with **no version** (the Boot BOM manages it) — do not add anything else.
- **Java style:** Google Java Style, enforced by Checkstyle with `maxWarnings = 0`. Two-space indent, `final` on parameters, Javadoc on every public type and method.
- **`buildInfo`'s `time` must be the commit timestamp, never wall-clock.** A wall-clock value changes every build and invalidates `:backend:bootJar` in the Gradle build cache that `ci-build-speedup` only just got working.
- **`publish.yml`'s backend checkout must use `fetch-depth: 0`.** All four checkouts default to depth 1, so `git log` in CI returns exactly one commit and the changelog silently ships with a single entry.
- **AI generation happens at ingest, never on view.** No code path reachable from `GET /api/platform/*` may call `Ai`.
- **One commit == one release.** `main` is squash-merged and Publish runs on every merge, so releases are not collections of commits.
- **Third-party ports and hostnames:** `software-factory:8090`, `deployer:8090` (not 8080). Both are on the Docker network; neither `/api/version` is routed by nginx.
- **Historical releases are labelled "published", not "deployed".** `deploy_runs` is empty (`FACTORY_DEPLOY_ENABLED` unset), so deployment cannot be evidenced.
- **CSS:** plain CSS, BEM naming, appended to the single `frontend/src/styles.css`, using existing custom properties. No CSS-in-JS, no new stylesheet.
- **Tests must pass Checkstyle too.** Run `../gradlew :backend:test :backend:checkstyleMain :backend:checkstyleTest` from `backend/`.

## Verified facts (do not re-derive)

- `SecurityConfig` ends with `.anyRequest().permitAll()`, so **`/api/platform/**` is already public — no `SecurityConfig` change is needed.** Task 9 only adds a test asserting that posture.
- `RateLimitInterceptor` meters only `/api/news/*/summary*`, `/api/blogs/*/narration`, `/mcp*` and chat. `/api/platform/**` is unmetered. **Do not add it to the interceptor** — the page issues two requests per view and metering would break it.
- `@EnableScheduling` is already active (`AggregationScheduler` and three others), so `@Scheduled` works without new configuration.
- `Ai` is a `@MockitoBean` on `AbstractIntegrationTest`, so integration tests never make a real LLM call.
- The Embabel inline-LLM call is `ai.withLlm(model).respond(List.of(new UserMessage(prompt))).getContent()` (see `ArticleSectionWriter`).
- Gradle 8.13, `org.gradle.caching=true`, configuration cache not enabled. `providers.exec` is available and is used anyway for correctness.

## File Structure

**Backend — new package `com.simonrowe.platform`** (one responsibility per file):

| File | Responsibility |
|---|---|
| `ServiceVersion.java` | record: one first-party service's version facts |
| `PlatformComponent.java` | record: one third-party image |
| `PlatformStatusResponse.java` | record: the `/status` payload |
| `ReleaseResponse.java` | record: one changelog entry as served |
| `RunningVersion.java` | this process's own SHA/commit/start time, from `BuildProperties` |
| `ProdImageCatalog.java` | parses the baked compose file into `PlatformComponent`s |
| `BakedRelease.java` | record: one commit as baked at build time |
| `BakedReleaseHistory.java` | parses the baked `git log` resource |
| `PlatformRelease.java` | `@Document` mutable class for `platform_releases` |
| `ReleaseSummaryStatus.java` | enum `PENDING, GENERATING, READY, FAILED` |
| `ReleaseSource.java` | enum `RUNNING, PUBLISHED_HISTORY` |
| `PlatformReleaseRepository.java` | Spring Data repository |
| `ReleaseRecorder.java` | idempotent startup seeding |
| `ReleaseSummarySweep.java` | scheduled AI summary generation |
| `FactoryVersionClient.java` | fetches software-factory / deployer versions |
| `PlatformStatusService.java` | assembles the status payload |
| `PlatformStatusController.java` | `GET /api/platform/status` |
| `PlatformReleasesController.java` | `GET /api/platform/releases` |

**software-factory — new package `com.simonrowe.factory.version`:** `FactoryVersion.java` (record), `VersionController.java`.

**Frontend:** `config/version.ts`, `types/platform.ts`, `services/platformApi.ts`, `hooks/usePlatformStatus.ts`, `hooks/useReleases.ts`, `components/status/{ServiceVersionCard,ComponentTable,ReleaseList,ReleaseEntry,DriftWarning}.tsx`, `components/layout/VersionBadge.tsx`, `pages/StatusPage.tsx`.

**Modified:** `backend/build.gradle.kts`, `software-factory/build.gradle.kts`, `backend/src/main/java/com/simonrowe/dataops/{BackupService,RestoreService}.java`, `.github/workflows/publish.yml`, `Dockerfile.frontend`, `frontend/src/App.tsx`, `frontend/src/components/layout/Footer.tsx`, `frontend/src/vite-env.d.ts`, `frontend/src/styles.css`, `docs/runbooks/`.

---

### Task 1: Bake the backend's own commit into the image

**Files:**
- Modify: `backend/build.gradle.kts` (add `springBoot { buildInfo }` block after the `jacoco` block)
- Create: `backend/src/main/java/com/simonrowe/platform/ServiceVersion.java`
- Create: `backend/src/main/java/com/simonrowe/platform/RunningVersion.java`
- Test: `backend/src/test/java/com/simonrowe/platform/RunningVersionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ServiceVersion(String name, String commit, String shortCommit, String commitSubject, Instant commitTime, Instant startedAt, boolean reachable)` — a record used by every later backend task. `RunningVersion.current()` returns `ServiceVersion` named `"backend"`. `RunningVersion.commit()` returns the full SHA or `"unknown"`. `RunningVersion.startedAt()` returns `Instant`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/RunningVersionTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class RunningVersionTest {

  private static BuildProperties buildProperties(final String commit, final String subject) {
    Properties properties = new Properties();
    properties.put("group", "com.simonrowe");
    properties.put("artifact", "backend");
    properties.put("version", "0.0.1-SNAPSHOT");
    properties.put("time", "1756200000");
    if (commit != null) {
      properties.put("commit", commit);
      properties.put("commitTime", "1756200000");
      properties.put("commitSubject", subject);
    }
    return new BuildProperties(properties);
  }

  @Test
  void reportsTheBakedCommit() {
    RunningVersion version = new RunningVersion(
        buildProperties("840c311abcdef0123456789abcdef0123456789a", "docs: overhaul the README"));

    ServiceVersion current = version.current();

    assertThat(current.name()).isEqualTo("backend");
    assertThat(current.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(current.shortCommit()).isEqualTo("840c311");
    assertThat(current.commitSubject()).isEqualTo("docs: overhaul the README");
    assertThat(current.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
    assertThat(current.reachable()).isTrue();
  }

  @Test
  void reportsADevBuildWhenNoBuildInfoIsPresent() {
    RunningVersion version = new RunningVersion(null);

    ServiceVersion current = version.current();

    assertThat(current.commit()).isEqualTo("unknown");
    assertThat(current.shortCommit()).isEqualTo("dev");
    assertThat(current.commitTime()).isNull();
    assertThat(current.reachable()).isTrue();
  }

  @Test
  void treatsAnEpochCommitTimeAsUnknown() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "0");
    RunningVersion version = new RunningVersion(new BuildProperties(properties));

    assertThat(version.current().commitTime()).isNull();
  }

  @Test
  void startedAtIsSetOnConstruction() {
    Instant before = Instant.now();
    RunningVersion version = new RunningVersion(null);

    assertThat(version.startedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.RunningVersionTest'`
Expected: FAIL — compilation error, `RunningVersion` and `ServiceVersion` do not exist.

- [ ] **Step 3: Create the `ServiceVersion` record**

`backend/src/main/java/com/simonrowe/platform/ServiceVersion.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;

/**
 * The version facts for one first-party service.
 *
 * <p>Every field except {@code name} and {@code reachable} may be absent: a service built
 * outside a git checkout has no commit, and a service the backend cannot reach reports
 * nothing at all. The page renders absence rather than erroring, so null is a supported
 * value here rather than a defect.
 *
 * @param name the compose service name, e.g. {@code backend}
 * @param commit the full commit SHA, or {@code unknown}
 * @param shortCommit the seven-character SHA, or {@code dev}
 * @param commitSubject the commit subject line, or null
 * @param commitTime when the commit was authored, or null when unknown
 * @param startedAt when the process started, or null when not reported
 * @param reachable false when the backend could not reach the service to ask
 */
public record ServiceVersion(
    String name,
    String commit,
    String shortCommit,
    String commitSubject,
    Instant commitTime,
    Instant startedAt,
    boolean reachable) {

  static final String UNKNOWN_COMMIT = "unknown";
  static final String DEV_SHORT_COMMIT = "dev";

  /**
   * A service that could not be reached.
   *
   * @param name the compose service name
   * @return a version reporting nothing but the name
   */
  public static ServiceVersion unreachable(final String name) {
    return new ServiceVersion(name, UNKNOWN_COMMIT, DEV_SHORT_COMMIT, null, null, null, false);
  }
}
```

- [ ] **Step 4: Create `RunningVersion`**

`backend/src/main/java/com/simonrowe/platform/RunningVersion.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * This process's own version, read from the {@code build-info.properties} baked into the
 * image by the {@code bootBuildInfo} Gradle task.
 *
 * <p>{@link BuildProperties} is only auto-configured when that file is present, so it is
 * injected as {@code @Nullable}: a developer running {@code bootRun} from an IDE that
 * skipped the task must not get a startup failure, they get a "dev build".
 *
 * <p><b>{@code startedAt} is captured in the constructor, not from
 * {@code ApplicationReadyEvent}.</b> {@code ReleaseRecorder} listens to that event and
 * needs this value already populated; going through the event too would couple the two
 * beans through listener ordering to gain at most a second or two of accuracy.
 */
@Component
public class RunningVersion {

  private static final String SERVICE_NAME = "backend";
  private static final int SHORT_SHA_LENGTH = 7;

  private final BuildProperties buildProperties;
  private final Instant startedAt;

  @Autowired
  public RunningVersion(@Nullable final BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
    this.startedAt = Instant.now();
  }

  /**
   * This process's version.
   *
   * @return the version; never null, always {@code reachable}
   */
  public ServiceVersion current() {
    return new ServiceVersion(
        SERVICE_NAME, commit(), shortCommit(), subject(), commitTime(), startedAt, true);
  }

  /**
   * The full commit SHA this artifact was built from.
   *
   * @return the SHA, or {@code unknown}
   */
  public String commit() {
    String value = property("commit");
    return value == null || value.isBlank() ? ServiceVersion.UNKNOWN_COMMIT : value;
  }

  /**
   * When this process started.
   *
   * @return the start instant
   */
  public Instant startedAt() {
    return startedAt;
  }

  /**
   * When the commit this artifact was built from was authored.
   *
   * @return the instant, or null when unknown
   */
  public Instant commitTime() {
    String value = property("commitTime");
    if (value == null || value.isBlank()) {
      return null;
    }
    long epochSeconds = Long.parseLong(value.trim());
    // The Gradle task writes 0 when git was unavailable, which keeps the build output
    // deterministic. Epoch is never a real commit time, so it means "unknown".
    return epochSeconds == 0L ? null : Instant.ofEpochSecond(epochSeconds);
  }

  private String shortCommit() {
    String full = commit();
    return ServiceVersion.UNKNOWN_COMMIT.equals(full)
        ? ServiceVersion.DEV_SHORT_COMMIT
        : full.substring(0, Math.min(SHORT_SHA_LENGTH, full.length()));
  }

  private String subject() {
    return property("commitSubject");
  }

  private String property(final String key) {
    return buildProperties == null ? null : buildProperties.get(key);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.RunningVersionTest'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Add the `buildInfo` block to `backend/build.gradle.kts`**

Insert immediately after the `jacoco { ... }` block:

```kotlin
// ---------------------------------------------------------------------------
// Build metadata baked into the image, served by GET /api/platform/status.
//
// `time` is pinned to the COMMIT timestamp, never wall-clock. A wall-clock value
// changes on every build, which would invalidate :backend:bootJar in the Gradle
// build cache — the cache ci-build-speedup only just got working for the first
// time. The commit time is both deterministic and the more meaningful value.
//
// Every git read degrades to a constant rather than failing the build: the Docker
// build context and a source tarball both lack .git, and `./gradlew build` must
// still work there.
// ---------------------------------------------------------------------------
val gitDir = rootProject.file(".git")

fun gitText(vararg args: String): Provider<String> =
    if (!gitDir.exists()) {
        providers.provider { "" }
    } else {
        providers.exec {
            workingDir = rootProject.projectDir
            commandLine(listOf("git") + args)
            isIgnoreExitValue = true
        }.standardOutput.asText
    }

val headSha: Provider<String> = gitText("rev-parse", "HEAD").map { it.trim() }
val headSubject: Provider<String> = gitText("log", "-1", "--format=%s").map { it.trim() }
val headEpoch: Provider<String> = gitText("log", "-1", "--format=%ct").map { it.trim() }
val headBranch: Provider<String> =
    gitText("rev-parse", "--abbrev-ref", "HEAD").map { it.trim() }

springBoot {
    buildInfo {
        properties {
            time.set(headEpoch.map {
                java.time.Instant.ofEpochSecond(it.ifBlank { "0" }.toLong())
            })
            additional.put("commit", headSha.map { it.ifBlank { "unknown" } })
            additional.put("commitTime", headEpoch.map { it.ifBlank { "0" } })
            additional.put("commitSubject", headSubject.map { it.ifBlank { "" } })
            additional.put("branch", headBranch.map { it.ifBlank { "unknown" } })
        }
    }
}
```

- [ ] **Step 7: Verify the generated file contains the commit**

Run from repo root:

```bash
./gradlew :backend:bootBuildInfo && cat backend/build/resources/main/META-INF/build-info.properties
```

Expected: contains `build.commit=`, `build.commitTime=`, `build.commitSubject=`, `build.branch=`, and a `build.time=` matching the HEAD commit time (compare with `git log -1 --format=%ct`).

- [ ] **Step 8: Verify the build cache is not invalidated by a rebuild**

```bash
./gradlew :backend:bootBuildInfo && ./gradlew :backend:bootBuildInfo
```

Expected: the second run reports `UP-TO-DATE`. If it re-runs, `time` is still wall-clock — fix before committing, this is a Global Constraint.

- [ ] **Step 9: Run Checkstyle and the full platform test**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest :backend:test --tests 'com.simonrowe.platform.*'`
Expected: PASS, 0 Checkstyle warnings.

- [ ] **Step 10: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/java/com/simonrowe/platform backend/src/test/java/com/simonrowe/platform
git commit -m "feat: bake the backend commit SHA into the image and report it"
```

---

### Task 2: Parse the production image catalogue

**Files:**
- Modify: `backend/build.gradle.kts` (add a `processResources` `from(...)` for the compose file)
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformComponent.java`
- Create: `backend/src/main/java/com/simonrowe/platform/ProdImageCatalog.java`
- Test: `backend/src/test/java/com/simonrowe/platform/ProdImageCatalogTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `PlatformComponent(String name, String image, String tag, boolean floating)`. `ProdImageCatalog.components()` returns `List<PlatformComponent>` sorted by `name`. `ProdImageCatalog.FIRST_PARTY` is a package-private `Set<String>` of the four first-party service names, used only inside this class — no later task consumes it.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/ProdImageCatalogTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses the REAL {@code docker-compose.prod.yml} shipped as a resource, so this test fails
 * when the parser and the compose file drift apart — which is the whole point of it.
 */
class ProdImageCatalogTest {

  private final ProdImageCatalog catalog = new ProdImageCatalog();

  @Test
  void readsPinnedThirdPartyImages() {
    List<PlatformComponent> components = catalog.components();

    assertThat(components)
        .contains(new PlatformComponent("mongodb", "mongo", "8", false))
        .contains(new PlatformComponent("elasticsearch", "elasticsearch", "8.17.0", false))
        .contains(new PlatformComponent("langfuse", "langfuse/langfuse", "3.212.0", false));
  }

  @Test
  void marksFloatingTagsRatherThanInventingAVersion() {
    PlatformComponent alloy = component("alloy");

    assertThat(alloy.tag()).isEqualTo("latest");
    assertThat(alloy.floating()).isTrue();
  }

  @Test
  void treatsAnUntaggedImageAsFloatingLatest() {
    PlatformComponent minio = component("langfuse-minio");

    assertThat(minio.image()).isEqualTo("cgr.dev/chainguard/minio");
    assertThat(minio.tag()).isEqualTo("latest");
    assertThat(minio.floating()).isTrue();
  }

  @Test
  void resolvesComposeVariableDefaults() {
    // software-factory's image is ${FACTORY_IMAGE:-ghcr.io/...:latest}. It is first-party
    // and therefore excluded, so assert on the resolution rule via the deployer's absence
    // and on the fact that no component name survives with a '${' in its image.
    assertThat(catalog.components()).noneMatch(c -> c.image().contains("${"));
  }

  @Test
  void excludesFirstPartyServices() {
    assertThat(catalog.components())
        .extracting(PlatformComponent::name)
        .doesNotContain("backend", "frontend", "software-factory", "deployer");
  }

  @Test
  void excludesOneShotInitContainers() {
    assertThat(catalog.components())
        .extracting(PlatformComponent::name)
        .doesNotContain(
            "uploads-init",
            "temporal-db-init",
            "temporal-schema-init",
            "dependencytrack-db-init",
            "temporal-create-namespace");
  }

  @Test
  void isSortedByServiceNameForStableRendering() {
    List<String> names = catalog.components().stream().map(PlatformComponent::name).toList();

    assertThat(names).isSorted();
  }

  private PlatformComponent component(final String name) {
    return catalog.components().stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no component named " + name));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ProdImageCatalogTest'`
Expected: FAIL — `ProdImageCatalog` and `PlatformComponent` do not exist.

- [ ] **Step 3: Ship the compose file as a backend resource**

Add to `backend/build.gradle.kts`, after the `springBoot { ... }` block from Task 1:

```kotlin
// The status page reports which third-party image tags production runs. Shipping the
// compose file itself — rather than a JSON summary generated in Gradle — keeps all the
// parsing in Java where it is unit-testable, and makes drift between parser and compose
// file a test failure rather than a silent wrong answer.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("docker-compose.prod.yml")) {
        into("platform")
    }
}
```

- [ ] **Step 4: Create the `PlatformComponent` record**

`backend/src/main/java/com/simonrowe/platform/PlatformComponent.java`:

```java
package com.simonrowe.platform;

/**
 * One third-party container image production is declared to run.
 *
 * <p>This states what {@code docker-compose.prod.yml} <em>declares</em>, not what Docker has
 * resolved. For the pinned majority those are the same thing; for a {@code floating} tag they
 * are not, which is exactly why that flag exists.
 *
 * @param name the compose service name
 * @param image the image reference without its tag
 * @param tag the tag, defaulting to {@code latest} when the reference carries none
 * @param floating true when the tag does not pin a version, so the running digest is unknown
 */
public record PlatformComponent(String name, String image, String tag, boolean floating) {
}
```

- [ ] **Step 5: Create `ProdImageCatalog`**

`backend/src/main/java/com/simonrowe/platform/ProdImageCatalog.java`:

```java
package com.simonrowe.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The third-party images production is declared to run, parsed from the copy of
 * {@code docker-compose.prod.yml} shipped as a resource by {@code processResources}.
 *
 * <p>Parsed once at construction: the file is immutable inside the image, so re-reading it per
 * request would buy nothing.
 *
 * <p>Excluded are the four first-party services (they self-report a commit SHA, which is a
 * far better answer than an image tag) and the one-shot init containers, which are not
 * "running" anything a reader could care about.
 */
@Component
public class ProdImageCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(ProdImageCatalog.class);

  private static final String RESOURCE = "platform/docker-compose.prod.yml";

  /** Services that report their own commit SHA instead of an image tag. */
  static final Set<String> FIRST_PARTY =
      Set.of("backend", "frontend", "software-factory", "deployer");

  private static final Set<String> ONE_SHOT = Set.of("temporal-create-namespace");

  private static final String LATEST = "latest";

  /** Matches {@code ${VAR:-default}} and {@code ${VAR}} compose interpolation. */
  private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^:}]+)(?::-([^}]*))?}");

  private final List<PlatformComponent> components;

  public ProdImageCatalog() {
    this.components = parse();
  }

  /**
   * Every third-party image production declares, sorted by service name.
   *
   * @return the components; empty when the resource is missing, never null
   */
  public List<PlatformComponent> components() {
    return components;
  }

  @SuppressWarnings("unchecked")
  private static List<PlatformComponent> parse() {
    try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
      Map<String, Object> root = new Yaml().load(stream);
      Object services = root == null ? null : root.get("services");
      if (!(services instanceof Map)) {
        LOG.warn("No services block in {}; the status page will list no components", RESOURCE);
        return List.of();
      }
      return ((Map<String, Object>) services).entrySet().stream()
          .filter(entry -> included(entry.getKey()))
          .map(entry -> component(entry.getKey(), entry.getValue()))
          .filter(java.util.Objects::nonNull)
          .sorted(Comparator.comparing(PlatformComponent::name))
          .toList();
    } catch (IOException | RuntimeException e) {
      // A missing or malformed resource must never stop the application from starting.
      // The page renders an empty component table, which is honest.
      LOG.warn("Could not parse {}: {}", RESOURCE, e.getMessage());
      return List.of();
    }
  }

  private static boolean included(final String name) {
    return !FIRST_PARTY.contains(name) && !ONE_SHOT.contains(name) && !name.endsWith("-init");
  }

  @SuppressWarnings("unchecked")
  private static PlatformComponent component(final String name, final Object definition) {
    if (!(definition instanceof Map)) {
      return null;
    }
    Object image = ((Map<String, Object>) definition).get("image");
    if (image == null) {
      return null;
    }
    String reference = resolve(image.toString().trim());
    int separator = reference.lastIndexOf(':');
    // A colon before the last slash belongs to a registry port, not a tag.
    boolean tagged = separator > reference.lastIndexOf('/');
    String repository = tagged ? reference.substring(0, separator) : reference;
    String tag = tagged ? reference.substring(separator + 1) : LATEST;
    return new PlatformComponent(name, repository, tag, LATEST.equals(tag));
  }

  /**
   * Replaces {@code ${VAR:-default}} with its default. Production supplies these from
   * {@code .env}, which is not in the image — the declared default is the best available
   * answer and is what an unset variable would resolve to anyway.
   */
  private static String resolve(final String reference) {
    Matcher matcher = INTERPOLATION.matcher(reference);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          resolved, Matcher.quoteReplacement(matcher.group(2) == null ? "" : matcher.group(2)));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ProdImageCatalogTest'`
Expected: PASS, 7 tests.

If snakeyaml does not resolve, add `implementation("org.yaml:snakeyaml")` with no version to `backend/build.gradle.kts` dependencies and re-run.

- [ ] **Step 7: Run Checkstyle**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 8: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/java/com/simonrowe/platform backend/src/test/java/com/simonrowe/platform
git commit -m "feat: read the production image catalogue from the compose file"
```

---

### Task 3: Bake the release history into the image

**Files:**
- Modify: `backend/build.gradle.kts` (add the `generateReleaseHistory` task and wire it into `processResources`)
- Create: `backend/src/main/java/com/simonrowe/platform/BakedRelease.java`
- Create: `backend/src/main/java/com/simonrowe/platform/BakedReleaseHistory.java`
- Test: `backend/src/test/java/com/simonrowe/platform/BakedReleaseHistoryTest.java`

**Interfaces:**
- Consumes: the `gitText(...)` helper and `gitDir` from Task 1's `build.gradle.kts` block.
- Produces: `BakedRelease(String sha, Instant commitTime, String subject, String body, List<String> filesChanged)` and `BakedReleaseHistory.releases()` returning `List<BakedRelease>` newest first. `BakedRelease.type()` returns the conventional-commit type (`feat`, `fix`, …) or `"other"`. `BakedRelease.shortSha()` returns 7 characters.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/BakedReleaseHistoryTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BakedReleaseHistoryTest {

  private static final String US = "\u001f";
  private static final String RS = "\u001e";

  @Test
  void parsesOneCommitPerRecord() {
    String raw = RS + "840c311abcdef0123456789abcdef0123456789a" + US + "1756200000" + US
        + "docs: overhaul the README (#118)" + US + "Rewrote it.\n\nAdded diagrams." + US
        + "\nREADME.md\ndocs/architecture.md\n"
        + RS + "39e0f7aabcdef0123456789abcdef0123456789a" + US + "1756100000" + US
        + "feat: deploy automatically on merge to main (#116)" + US + "" + US
        + "\ndocker-compose.prod.yml\n";

    List<BakedRelease> releases = BakedReleaseHistory.parse(raw);

    assertThat(releases).hasSize(2);
    BakedRelease first = releases.get(0);
    assertThat(first.sha()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(first.shortSha()).isEqualTo("840c311");
    assertThat(first.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
    assertThat(first.subject()).isEqualTo("docs: overhaul the README (#118)");
    assertThat(first.body()).isEqualTo("Rewrote it.\n\nAdded diagrams.");
    assertThat(first.filesChanged()).containsExactly("README.md", "docs/architecture.md");
    assertThat(releases.get(1).body()).isEmpty();
  }

  @Test
  void derivesTheConventionalCommitType() {
    assertThat(release("docs: overhaul the README").type()).isEqualTo("docs");
    assertThat(release("feat: deploy automatically").type()).isEqualTo("feat");
    assertThat(release("fix(api): stop the 500").type()).isEqualTo("fix");
    assertThat(release("perf: stop a 60s block").type()).isEqualTo("perf");
    assertThat(release("Merge pull request #7").type()).isEqualTo("other");
    assertThat(release("no colon here").type()).isEqualTo("other");
  }

  @Test
  void returnsEmptyForAbsentOrBlankHistory() {
    assertThat(BakedReleaseHistory.parse("")).isEmpty();
    assertThat(BakedReleaseHistory.parse("   \n ")).isEmpty();
  }

  @Test
  void skipsMalformedRecordsRatherThanFailing() {
    String raw = RS + "onlyonefield";

    assertThat(BakedReleaseHistory.parse(raw)).isEmpty();
  }

  @Test
  void skipsARecordWithAnUnparseableTimestamp() {
    String raw = RS + "840c311abcdef0123456789abcdef0123456789a" + US + "not-a-number" + US
        + "feat: thing" + US + "" + US + "\n";

    assertThat(BakedReleaseHistory.parse(raw)).isEmpty();
  }

  private static BakedRelease release(final String subject) {
    String raw = RS + "840c311abcdef0123456789abcdef0123456789a" + US + "1756200000" + US
        + subject + US + "" + US + "\n";
    return BakedReleaseHistory.parse(raw).get(0);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.BakedReleaseHistoryTest'`
Expected: FAIL — `BakedRelease` and `BakedReleaseHistory` do not exist.

- [ ] **Step 3: Create the `BakedRelease` record**

`backend/src/main/java/com/simonrowe/platform/BakedRelease.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;

/**
 * One commit on {@code main}, as baked into the image at build time.
 *
 * <p>Because {@code main} is squash-merged and Publish runs on every merge, one commit is one
 * release. There is deliberately no "commits within a release" concept.
 *
 * @param sha the full commit SHA
 * @param commitTime when the commit was authored
 * @param subject the subject line
 * @param body the message body, empty when there is none
 * @param filesChanged the paths the commit touched
 */
public record BakedRelease(
    String sha, Instant commitTime, String subject, String body, List<String> filesChanged) {

  private static final int SHORT_SHA_LENGTH = 7;
  private static final String OTHER_TYPE = "other";

  public BakedRelease {
    filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
  }

  /**
   * The short SHA as rendered on the page and in GitHub links.
   *
   * @return the first seven characters of the SHA
   */
  public String shortSha() {
    return sha.substring(0, Math.min(SHORT_SHA_LENGTH, sha.length()));
  }

  /**
   * The conventional-commit type, used for the badge on each changelog entry.
   *
   * @return {@code feat}, {@code fix}, {@code chore}, {@code docs}, {@code perf} and so on,
   *     or {@code other} when the subject does not follow the convention
   */
  public String type() {
    int colon = subject.indexOf(':');
    if (colon <= 0) {
      return OTHER_TYPE;
    }
    String prefix = subject.substring(0, colon);
    // Strip an optional scope: "fix(api)" -> "fix".
    int scope = prefix.indexOf('(');
    String type = (scope > 0 ? prefix.substring(0, scope) : prefix).trim();
    // A space means this was prose that happened to contain a colon, not a type.
    return type.isEmpty() || type.contains(" ") ? OTHER_TYPE : type.toLowerCase(
        java.util.Locale.ROOT);
  }
}
```

- [ ] **Step 4: Create `BakedReleaseHistory`**

`backend/src/main/java/com/simonrowe/platform/BakedReleaseHistory.java`:

```java
package com.simonrowe.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The last 50 commits on {@code main}, baked into the image by the {@code generateReleaseHistory}
 * Gradle task and parsed once at construction.
 *
 * <p>The format is git's own output with ASCII record ({@code 0x1e}) and unit ({@code 0x1f})
 * separators rather than JSON, because generating JSON in Gradle means hand-rolling string
 * escaping for arbitrary commit messages — a bug waiting to happen. Separators are used instead
 * of newlines because commit bodies contain newlines.
 *
 * <p>An absent resource yields an empty list. That is the normal state for a build made outside
 * a git checkout, and the page renders "no release history yet".
 */
@Component
public class BakedReleaseHistory {

  private static final Logger LOG = LoggerFactory.getLogger(BakedReleaseHistory.class);

  private static final String RESOURCE = "platform/release-history.txt";
  private static final String RECORD_SEPARATOR = "\u001e";
  private static final String UNIT_SEPARATOR = "\u001f";
  private static final int FIELD_COUNT = 5;

  private final List<BakedRelease> releases;

  public BakedReleaseHistory() {
    this.releases = parse(read());
  }

  /**
   * The baked commits, newest first.
   *
   * @return the releases; empty when the resource is absent, never null
   */
  public List<BakedRelease> releases() {
    return releases;
  }

  private static String read() {
    ClassPathResource resource = new ClassPathResource(RESOURCE);
    if (!resource.exists()) {
      LOG.info("No {} on the classpath — release history will be empty", RESOURCE);
      return "";
    }
    try (InputStream stream = resource.getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.warn("Could not read {}: {}", RESOURCE, e.getMessage());
      return "";
    }
  }

  /**
   * Parses the baked git output.
   *
   * <p>Package-private and static so it can be tested without a classpath resource.
   *
   * @param raw the resource contents
   * @return the parsed releases, newest first; malformed records are skipped, not fatal
   */
  static List<BakedRelease> parse(final String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<BakedRelease> parsed = new ArrayList<>();
    for (String record : raw.split(RECORD_SEPARATOR)) {
      if (record.isBlank()) {
        continue;
      }
      String[] fields = record.split(UNIT_SEPARATOR, -1);
      if (fields.length < FIELD_COUNT) {
        LOG.warn("Skipping malformed release record with {} fields", fields.length);
        continue;
      }
      try {
        parsed.add(new BakedRelease(
            fields[0].trim(),
            Instant.ofEpochSecond(Long.parseLong(fields[1].trim())),
            fields[2].trim(),
            fields[3].trim(),
            files(fields[4])));
      } catch (NumberFormatException e) {
        LOG.warn("Skipping release record with unparseable timestamp '{}'", fields[1]);
      }
    }
    return List.copyOf(parsed);
  }

  private static List<String> files(final String block) {
    return Arrays.stream(block.split("\n"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.BakedReleaseHistoryTest'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Add the `generateReleaseHistory` Gradle task**

Add to `backend/build.gradle.kts` after the `processResources` block from Task 2:

```kotlin
// ---------------------------------------------------------------------------
// The changelog on /status. 50 commits are baked so the AI summary sweep has depth;
// the page itself requests 20.
//
// Separators rather than JSON: generating JSON here would mean hand-rolling escaping
// for arbitrary commit messages. `git log` emits ASCII record/unit separators for free
// and BakedReleaseHistory parses them.
//
// The task's only input is the HEAD SHA, so it re-runs when and only when HEAD moves.
//
// NOTE: in CI this yields ONE commit unless the checkout uses fetch-depth: 0. See
// .github/workflows/publish.yml.
// ---------------------------------------------------------------------------
val releaseHistoryFile = layout.buildDirectory.file("generated/platform/release-history.txt")

val releaseHistoryRaw: Provider<String> = gitText(
    "-c", "core.quotepath=false",
    "log", "-n", "50",
    "--format=%x1e%H%x1f%ct%x1f%s%x1f%b%x1f",
    "--name-only",
)

val generateReleaseHistory by tasks.registering {
    description = "Bakes the last 50 commits on this branch into a backend resource."
    val sha = headSha
    val raw = releaseHistoryRaw
    val output = releaseHistoryFile
    inputs.property("headSha", sha)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(raw.get())
    }
}

tasks.named<ProcessResources>("processResources") {
    from(generateReleaseHistory) {
        into("platform")
    }
}
```

- [ ] **Step 7: Verify the generated resource contains real history**

```bash
./gradlew :backend:processResources
tr '\036' '\n' < backend/build/resources/main/platform/release-history.txt | grep -c . 
```

Expected: a count greater than 1 (roughly 51 given the trailing newline handling). Then confirm the newest SHA matches:

```bash
head -c 41 backend/build/resources/main/platform/release-history.txt | tail -c 40
git rev-parse HEAD
```

Expected: the two SHAs match.

- [ ] **Step 8: Verify up-to-date behaviour**

```bash
./gradlew :backend:generateReleaseHistory && ./gradlew :backend:generateReleaseHistory
```

Expected: the second run reports `UP-TO-DATE`.

- [ ] **Step 9: Run Checkstyle and the platform tests**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest :backend:test --tests 'com.simonrowe.platform.*'`
Expected: PASS, 0 warnings.

- [ ] **Step 10: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/java/com/simonrowe/platform backend/src/test/java/com/simonrowe/platform
git commit -m "feat: bake the recent release history into the backend image"
```

---

### Task 4: The `platform_releases` collection

**Files:**
- Create: `backend/src/main/java/com/simonrowe/platform/ReleaseSummaryStatus.java`
- Create: `backend/src/main/java/com/simonrowe/platform/ReleaseSource.java`
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformRelease.java`
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformReleaseRepository.java`
- Create: `backend/src/main/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexes.java`
- Test: `backend/src/test/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexesTest.java`

**Interfaces:**
- Consumes: `BakedRelease` (Task 3).
- Produces: `PlatformRelease` — a **mutable class**, not a record, because the sweep transitions it in place (the `ArticleSummary` precedent). Getters/setters: `getId/setId`, `getShortSha/setShortSha`, `getCommitTime/setCommitTime`, `getSubject/setSubject`, `getBody/setBody`, `getType/setType`, `getFilesChanged/setFilesChanged`, `getInsertions/setInsertions`, `getDeletions/setDeletions`, `getSummary/setSummary`, `getSummaryStatus/setSummaryStatus`, `getSummaryAttempts/setSummaryAttempts`, `getFirstSeenAt/setFirstSeenAt`, `getSource/setSource`, `getUpdatedAt/setUpdatedAt`. `PlatformRelease.fromBaked(BakedRelease, ReleaseSource, Instant)` builds a `PENDING` record. `PlatformReleaseRepository.findRecent(int limit)` returns `List<PlatformRelease>` newest first. `V022CreatePlatformReleaseIndexes.createIndexes(MongoTemplate)` is public static, callable after a restore. `V022CreatePlatformReleaseIndexes.COLLECTION` is `"platform_releases"`.

- [ ] **Step 1: Write the failing change-unit test**

`backend/src/test/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexesTest.java`:

```java
package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

class V022CreatePlatformReleaseIndexesTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Test
  void createsTheChangelogIndexes() {
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);

    assertThat(
        mongoTemplate.indexOps(V022CreatePlatformReleaseIndexes.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .contains(
            V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX,
            V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX);
  }

  @Test
  void isIdempotent() {
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);

    assertThat(
        mongoTemplate.indexOps(V022CreatePlatformReleaseIndexes.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .filteredOn(name -> name.equals(V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX))
        .hasSize(1);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests '*V022CreatePlatformReleaseIndexesTest'`
Expected: FAIL — the change unit does not exist.

- [ ] **Step 3: Create the two enums**

`backend/src/main/java/com/simonrowe/platform/ReleaseSummaryStatus.java`:

```java
package com.simonrowe.platform;

/** Where a release's AI summary has got to. */
public enum ReleaseSummaryStatus {

  /** Seeded, not yet summarised. */
  PENDING,

  /** Claimed by a sweep tick. Guards against two ticks summarising the same release. */
  GENERATING,

  /** Summarised. */
  READY,

  /** Gave up after the attempt limit; the entry renders from its commit subject. */
  FAILED
}
```

`backend/src/main/java/com/simonrowe/platform/ReleaseSource.java`:

```java
package com.simonrowe.platform;

/**
 * How a release record came to exist.
 *
 * <p>The distinction is what lets the page be honest: {@code RUNNING} is evidenced by an
 * artifact reporting its own SHA, whereas {@code PUBLISHED_HISTORY} is derived from
 * {@code main}'s commit history and only evidences that an image was published.
 */
public enum ReleaseSource {

  /** A backend instance booted reporting this SHA, so it demonstrably ran. */
  RUNNING,

  /** Derived from baked git history: published to ghcr, deployment unknown. */
  PUBLISHED_HISTORY
}
```

- [ ] **Step 4: Create `PlatformRelease`**

`backend/src/main/java/com/simonrowe/platform/PlatformRelease.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One release, keyed by the full commit SHA.
 *
 * <p>A mutable class rather than a record, following {@code ArticleSummary}: the summary sweep
 * transitions the document in place through {@code PENDING} → {@code GENERATING} →
 * {@code READY}.
 *
 * <p>The {@code _id} is the SHA itself, which is what makes seeding idempotent — a second
 * insert of the same release is a duplicate-key error rather than a second row.
 */
@Document(collection = "platform_releases")
public class PlatformRelease {

  @Id
  private String id;

  private String shortSha;
  private Instant commitTime;
  private String subject;
  private String body;
  private String type;
  private List<String> filesChanged = List.of();
  private String summary;
  private ReleaseSummaryStatus summaryStatus = ReleaseSummaryStatus.PENDING;
  private int summaryAttempts;
  private Instant firstSeenAt;
  private ReleaseSource source = ReleaseSource.PUBLISHED_HISTORY;
  private Instant updatedAt;

  /**
   * Builds a pending release record from a baked commit.
   *
   * @param baked the commit as baked into the image
   * @param source how this record came to exist
   * @param now the seeding instant
   * @return a record awaiting its summary
   */
  public static PlatformRelease fromBaked(
      final BakedRelease baked, final ReleaseSource source, final Instant now) {
    PlatformRelease release = new PlatformRelease();
    release.id = baked.sha();
    release.shortSha = baked.shortSha();
    release.commitTime = baked.commitTime();
    release.subject = baked.subject();
    release.body = baked.body();
    release.type = baked.type();
    release.filesChanged = baked.filesChanged();
    release.summaryStatus = ReleaseSummaryStatus.PENDING;
    release.firstSeenAt = now;
    release.source = source;
    release.updatedAt = now;
    return release;
  }

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public String getShortSha() {
    return shortSha;
  }

  public void setShortSha(final String shortSha) {
    this.shortSha = shortSha;
  }

  public Instant getCommitTime() {
    return commitTime;
  }

  public void setCommitTime(final Instant commitTime) {
    this.commitTime = commitTime;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(final String subject) {
    this.subject = subject;
  }

  public String getBody() {
    return body;
  }

  public void setBody(final String body) {
    this.body = body;
  }

  public String getType() {
    return type;
  }

  public void setType(final String type) {
    this.type = type;
  }

  public List<String> getFilesChanged() {
    return filesChanged;
  }

  public void setFilesChanged(final List<String> filesChanged) {
    this.filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(final String summary) {
    this.summary = summary;
  }

  public ReleaseSummaryStatus getSummaryStatus() {
    return summaryStatus;
  }

  public void setSummaryStatus(final ReleaseSummaryStatus summaryStatus) {
    this.summaryStatus = summaryStatus;
  }

  public int getSummaryAttempts() {
    return summaryAttempts;
  }

  public void setSummaryAttempts(final int summaryAttempts) {
    this.summaryAttempts = summaryAttempts;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(final Instant firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public ReleaseSource getSource() {
    return source;
  }

  public void setSource(final ReleaseSource source) {
    this.source = source;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
```

- [ ] **Step 5: Create the repository**

`backend/src/main/java/com/simonrowe/platform/PlatformReleaseRepository.java`:

```java
package com.simonrowe.platform;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link PlatformRelease}, keyed by commit SHA. */
public interface PlatformReleaseRepository extends MongoRepository<PlatformRelease, String> {

  /**
   * The most recent releases, newest first.
   *
   * <p>A default method over {@code findAll(Pageable)} rather than a derived
   * {@code findTopNBy...} query, because the limit is a request parameter and a derived query
   * would hard-code it.
   *
   * @param limit how many to return
   * @return the releases, newest first
   */
  default List<PlatformRelease> findRecent(final int limit) {
    return findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "commitTime")))
        .getContent();
  }

  /**
   * Releases awaiting a summary, oldest commit first.
   *
   * @param limit how many to claim
   * @return pending releases
   */
  default List<PlatformRelease> findPending(final int limit) {
    return findBySummaryStatus(
        ReleaseSummaryStatus.PENDING,
        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "commitTime")));
  }

  /**
   * Releases in a given summary state.
   *
   * @param status the state to match
   * @param pageable paging and sorting
   * @return the matching releases
   */
  List<PlatformRelease> findBySummaryStatus(
      ReleaseSummaryStatus status, org.springframework.data.domain.Pageable pageable);
}
```

- [ ] **Step 6: Create the Mongock change unit**

`backend/src/main/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexes.java`:

```java
package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the indexes on {@code platform_releases}. Spring Data auto-index-creation is
 * disabled, so annotations on {@code PlatformRelease} would be decorative. Index creation is
 * idempotent, so this is safe to re-run.
 *
 * <p><b>This change unit creates indexes only.</b> The release documents themselves are
 * written by {@code ReleaseRecorder} on startup, deliberately not by a change unit: they are
 * derived, self-healing data that a restore has to re-establish anyway, and putting LLM-fed
 * seeding in a change unit would run live I/O against the shared Testcontainers Mongo. Same
 * reasoning as {@code NarrationRestoreValidator.ensureIndexes()}.
 *
 * <p>No index on {@code _id} is declared: it is the commit SHA and Mongo always indexes
 * {@code _id}, which is what the seeding dedup relies on.
 */
@ChangeUnit(id = "create-platform-release-indexes", order = "022", author = "simonrowe")
public class V022CreatePlatformReleaseIndexes {

  static final String COLLECTION = "platform_releases";
  static final String COMMIT_TIME_INDEX = "idx_platform_release_commit_time";
  static final String SUMMARY_STATUS_INDEX = "idx_platform_release_summary_status";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(COMMIT_TIME_INDEX);
    mongoTemplate.indexOps(COLLECTION).dropIndex(SUMMARY_STATUS_INDEX);
  }

  /**
   * Also called after a restore, which drops collections and their indexes with them.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    // The changelog read: sort by commitTime descending, no filter.
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(COMMIT_TIME_INDEX)
        .on("commitTime", Sort.Direction.DESC));
    // The sweep's claim query: filter on summaryStatus, sort by commitTime descending.
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(SUMMARY_STATUS_INDEX)
        .on("summaryStatus", Sort.Direction.ASC)
        .on("commitTime", Sort.Direction.DESC));
  }
}
```

The test references `COLLECTION`, `COMMIT_TIME_INDEX` and `SUMMARY_STATUS_INDEX` from the same package, so package-private visibility is correct.

- [ ] **Step 7: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests '*V022CreatePlatformReleaseIndexesTest'`
Expected: PASS, 2 tests.

- [ ] **Step 8: Run Checkstyle**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/simonrowe/platform backend/src/main/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexes.java backend/src/test/java/com/simonrowe/migration/changeunits/V022CreatePlatformReleaseIndexesTest.java
git commit -m "feat: add the platform_releases collection and its indexes"
```

---

### Task 5: Seed release records idempotently at startup

**Files:**
- Create: `backend/src/main/java/com/simonrowe/platform/ReleaseRecorder.java`
- Test: `backend/src/test/java/com/simonrowe/platform/ReleaseRecorderTest.java`

**Interfaces:**
- Consumes: `RunningVersion` (Task 1), `BakedRelease` / `BakedReleaseHistory` (Task 3), `PlatformRelease` / `PlatformReleaseRepository` / `ReleaseSource` / `ReleaseSummaryStatus` (Task 4).
- Produces: `ReleaseRecorder.record()` — public, returns `int` (the number of records inserted), so the test can assert idempotency without reading the collection twice. Called from `onApplicationReady(ApplicationReadyEvent)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/ReleaseRecorderTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReleaseRecorderTest extends AbstractIntegrationTest {

  private static final String RUNNING_SHA = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER_SHA = "39e0f7aabcdef0123456789abcdef0123456789a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearCollection() {
    mongoTemplate.dropCollection(PlatformRelease.class);
  }

  private static BakedRelease baked(final String sha, final String subject, final long epoch) {
    return new BakedRelease(
        sha, Instant.ofEpochSecond(epoch), subject, "body text", List.of("a.java"));
  }

  private ReleaseRecorder recorder(final List<BakedRelease> history) {
    java.util.Properties properties = new java.util.Properties();
    properties.put("commit", RUNNING_SHA);
    properties.put("commitTime", "1756200000");
    properties.put("commitSubject", "docs: overhaul the README");
    return new ReleaseRecorder(
        new RunningVersion(new BuildProperties(properties)), () -> history, repository);
  }

  @Test
  void seedsEveryBakedRelease() {
    int inserted = recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L))).record();

    assertThat(inserted).isEqualTo(2);
    assertThat(repository.findAll()).extracting(PlatformRelease::getId)
        .containsExactlyInAnyOrder(RUNNING_SHA, OLDER_SHA);
  }

  @Test
  void marksTheRunningReleaseAsRunningAndTheRestAsPublished() {
    recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L))).record();

    assertThat(repository.findById(RUNNING_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.RUNNING);
    assertThat(repository.findById(OLDER_SHA).orElseThrow().getSource())
        .isEqualTo(ReleaseSource.PUBLISHED_HISTORY);
  }

  @Test
  void insertsNothingOnASecondRun() {
    ReleaseRecorder recorder = recorder(List.of(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        baked(OLDER_SHA, "feat: deploy automatically", 1756100000L)));
    recorder.record();

    assertThat(recorder.record()).isZero();
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void neverOverwritesAnExistingSummary() {
    ReleaseRecorder recorder =
        recorder(List.of(baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L)));
    recorder.record();
    PlatformRelease stored = repository.findById(RUNNING_SHA).orElseThrow();
    stored.setSummary("An expensive paragraph.");
    stored.setSummaryStatus(ReleaseSummaryStatus.READY);
    repository.save(stored);

    recorder.record();

    PlatformRelease after = repository.findById(RUNNING_SHA).orElseThrow();
    assertThat(after.getSummary()).isEqualTo("An expensive paragraph.");
    assertThat(after.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
  }

  @Test
  void promotesAPublishedRecordToRunningWhenThisBuildBootsOnIt() {
    // The history is baked before the deploy, so the running SHA is usually already
    // present as PUBLISHED_HISTORY from an earlier boot. Booting on it is the evidence
    // that upgrades the claim, and it must not cost the summary.
    ReleaseRecorder seeder =
        recorder(List.of(baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L)));
    PlatformRelease published = PlatformRelease.fromBaked(
        baked(RUNNING_SHA, "docs: overhaul the README", 1756200000L),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(1756190000L));
    published.setSummary("Already written.");
    published.setSummaryStatus(ReleaseSummaryStatus.READY);
    repository.save(published);

    seeder.record();

    PlatformRelease after = repository.findById(RUNNING_SHA).orElseThrow();
    assertThat(after.getSource()).isEqualTo(ReleaseSource.RUNNING);
    assertThat(after.getSummary()).isEqualTo("Already written.");
  }

  @Test
  void recordsNothingWhenThereIsNoBuildInfo() {
    ReleaseRecorder devBuild =
        new ReleaseRecorder(new RunningVersion(null), List::of, repository);

    assertThat(devBuild.record()).isZero();
    assertThat(repository.count()).isZero();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ReleaseRecorderTest'`
Expected: FAIL — `ReleaseRecorder` does not exist.

- [ ] **Step 3: Create `ReleaseRecorder`**

`backend/src/main/java/com/simonrowe/platform/ReleaseRecorder.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code platform_releases} from the history baked into this image, and records that this
 * build booted.
 *
 * <p>Runs on every startup and is insert-only: a release already present is left completely
 * alone, because its summary cost an LLM call. The single exception is promoting an existing
 * {@code PUBLISHED_HISTORY} record to {@code RUNNING} when this build boots on it — the history
 * is baked before the deploy, so the running SHA is normally already there from an earlier
 * boot, and booting on it is the evidence that upgrades "published" to "ran". That promotion
 * touches {@code source} and nothing else.
 *
 * <p><b>Why this is not a Mongock change unit</b> despite the repo's Mongock-first rule: these
 * are derived, self-healing records that a restore drops and this component re-establishes on
 * the next boot. Seeding in a change unit would also mean a change unit whose records feed LLM
 * calls, run against the shared Testcontainers Mongo in every integration test.
 */
@Component
public class ReleaseRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(ReleaseRecorder.class);

  private final RunningVersion runningVersion;
  private final Supplier<List<BakedRelease>> history;
  private final PlatformReleaseRepository repository;

  public ReleaseRecorder(
      final RunningVersion runningVersion,
      final BakedReleaseHistory history,
      final PlatformReleaseRepository repository) {
    this(runningVersion, history::releases, repository);
  }

  /**
   * Test seam taking the history as a supplier, so a test can inject commits without a
   * classpath resource.
   *
   * @param runningVersion this process's version
   * @param history supplies the baked commits
   * @param repository where releases are stored
   */
  ReleaseRecorder(
      final RunningVersion runningVersion,
      final Supplier<List<BakedRelease>> history,
      final PlatformReleaseRepository repository) {
    this.runningVersion = runningVersion;
    this.history = history;
    this.repository = repository;
  }

  /** Seeds on startup. Failure here must never stop the application from serving. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      int inserted = record();
      LOG.info("Release history seeded: {} new release(s) recorded", inserted);
    } catch (RuntimeException e) {
      LOG.warn("Could not seed release history: {}", e.getMessage());
    }
  }

  /**
   * Seeds every baked release not already stored, and marks the running one.
   *
   * @return how many records were inserted
   */
  public int record() {
    Instant now = Instant.now();
    String runningSha = runningVersion.commit();
    int inserted = 0;
    for (BakedRelease baked : history.get()) {
      ReleaseSource source =
          baked.sha().equals(runningSha) ? ReleaseSource.RUNNING : ReleaseSource.PUBLISHED_HISTORY;
      if (insert(baked, source, now)) {
        inserted++;
      } else if (source == ReleaseSource.RUNNING) {
        promoteToRunning(baked.sha());
      }
    }
    return inserted;
  }

  /**
   * Inserts a release, treating an existing row as success-by-someone-else.
   *
   * @return true when this call created the record
   */
  private boolean insert(
      final BakedRelease baked, final ReleaseSource source, final Instant now) {
    if (repository.existsById(baked.sha())) {
      return false;
    }
    try {
      repository.insert(PlatformRelease.fromBaked(baked, source, now));
      return true;
    } catch (DuplicateKeyException e) {
      // Another instance inserted it between the check and the insert. Not an error:
      // the _id is the SHA precisely so this race resolves itself.
      return false;
    }
  }

  private void promoteToRunning(final String sha) {
    Optional<PlatformRelease> stored = repository.findById(sha);
    if (stored.isEmpty() || stored.get().getSource() == ReleaseSource.RUNNING) {
      return;
    }
    PlatformRelease release = stored.get();
    release.setSource(ReleaseSource.RUNNING);
    repository.save(release);
    LOG.info("Release {} promoted to RUNNING: this build booted on it", release.getShortSha());
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ReleaseRecorderTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run Checkstyle**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/simonrowe/platform/ReleaseRecorder.java backend/src/test/java/com/simonrowe/platform/ReleaseRecorderTest.java
git commit -m "feat: seed the release changelog from baked history on startup"
```

---

### Task 6: Generate release summaries with a scheduled sweep

**Files:**
- Create: `backend/src/main/java/com/simonrowe/platform/ReleaseSummarySweep.java`
- Modify: `backend/src/main/resources/application.yml` (add the `platform.releases` block)
- Test: `backend/src/test/java/com/simonrowe/platform/ReleaseSummarySweepTest.java`

**Interfaces:**
- Consumes: `PlatformRelease`, `PlatformReleaseRepository`, `ReleaseSummaryStatus` (Task 4), Embabel `Ai`.
- Produces: `ReleaseSummarySweep.sweep()` — public, returns `int` (the number summarised in this tick). `ReleaseSummarySweep.SUMMARY_FORMAT_VERSION` is a `static final String` adjacent to the prompt.

- [ ] **Step 1: Add the configuration block**

Add to `backend/src/main/resources/application.yml`, as a new top-level block:

```yaml
# The /status page's changelog. Summaries are generated at INGEST — when a release record is
# created — never on view: a public endpoint that called an LLM per request would be both a
# cost problem and an abuse vector.
platform:
  releases:
    summaries:
      # A one-off backfill of 50 releases costs 50 small calls; steady state is one per merge.
      enabled: ${PLATFORM_RELEASE_SUMMARIES_ENABLED:true}
      # 3 per tick every 2 minutes clears a 50-release backfill in ~35 minutes without
      # holding a scheduler thread for long or spiking the OpenAI bill in one burst.
      batch-size: 3
      max-attempts: 3
      model: ${PLATFORM_RELEASE_SUMMARY_MODEL:gpt-4o-mini}
```

Verify the `model` value is one the project already uses: run
`grep -rn "aggregation.summary.model\|digest.model" backend/src/main/resources/application.yml`
and use the same model string those set. Do not introduce a model the project does not already call.

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/ReleaseSummarySweepTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReleaseSummarySweepTest extends AbstractIntegrationTest {

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearCollection() {
    mongoTemplate.dropCollection(PlatformRelease.class);
  }

  private PlatformRelease pending(final String sha, final long epoch) {
    PlatformRelease release = PlatformRelease.fromBaked(
        new BakedRelease(
            sha, Instant.ofEpochSecond(epoch), "feat: add a thing", "It does a thing.",
            List.of("Thing.java")),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(epoch));
    return repository.insert(release);
  }

  /**
   * Stubs the Embabel inline-LLM chain used by ArticleSectionWriter:
   * {@code ai.withLlm(model).respond(List.of(new UserMessage(prompt))).getContent()}.
   *
   * <p>PIN THESE TWO TYPES BEFORE IMPLEMENTING — see the note under this test in the plan.
   * {@code PromptRunner} is the expected return of {@code withLlm(String)} and
   * {@code AssistantMessage} the expected return of {@code respond(...)}, but confirm both
   * against ArticleSectionWriter and the Embabel jar rather than trusting these names.
   */
  private ReleaseSummarySweep sweepReturning(final String completion) {
    Ai stubAi = mock(Ai.class);
    PromptRunner runner = mock(PromptRunner.class);
    when(stubAi.withLlm(anyString())).thenReturn(runner);
    when(runner.respond(any())).thenReturn(new AssistantMessage(completion));
    return new ReleaseSummarySweep(repository, stubAi, true, 3, 3, "test-model");
  }

  private ReleaseSummarySweep sweepThatFails() {
    Ai stubAi = mock(Ai.class);
    when(stubAi.withLlm(anyString())).thenThrow(new IllegalStateException("model down"));
    return new ReleaseSummarySweep(repository, stubAi, true, 3, 3, "test-model");
  }

  @Test
  void summarisesPendingReleases() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);

    int summarised = sweepReturning("This release added a thing.").sweep();

    assertThat(summarised).isEqualTo(1);
    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
    assertThat(stored.getSummary()).isEqualTo("This release added a thing.");
  }

  @Test
  void honoursTheBatchSize() {
    pending("aaa0000abcdef0123456789abcdef0123456789a", 1756200000L);
    pending("bbb0000abcdef0123456789abcdef0123456789a", 1756100000L);
    pending("ccc0000abcdef0123456789abcdef0123456789a", 1756000000L);
    pending("ddd0000abcdef0123456789abcdef0123456789a", 1755900000L);

    assertThat(sweepReturning("A paragraph.").sweep()).isEqualTo(3);
    assertThat(repository.findPending(10)).hasSize(1);
  }

  @Test
  void treatsAnEmptyCompletionAsAFailedAttempt() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);

    sweepReturning("   ").sweep();

    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.PENDING);
    assertThat(stored.getSummaryAttempts()).isEqualTo(1);
  }

  @Test
  void givesUpAfterTheAttemptLimit() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    ReleaseSummarySweep sweep = sweepThatFails();

    sweep.sweep();
    sweep.sweep();
    sweep.sweep();

    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.FAILED);
    assertThat(stored.getSummaryAttempts()).isEqualTo(3);
  }

  @Test
  void doesNothingWhenDisabled() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    Ai stubAi = mock(Ai.class);
    ReleaseSummarySweep disabled =
        new ReleaseSummarySweep(repository, stubAi, false, 3, 3, "test-model");

    assertThat(disabled.sweep()).isZero();
    verify(stubAi, never()).withLlm(anyString());
  }

  @Test
  void leavesReadyReleasesAlone() {
    PlatformRelease ready = pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    ready.setSummaryStatus(ReleaseSummaryStatus.READY);
    ready.setSummary("Already written.");
    repository.save(ready);

    assertThat(sweepReturning("Overwritten!").sweep()).isZero();
    assertThat(repository.findById(ready.getId()).orElseThrow().getSummary())
        .isEqualTo("Already written.");
  }
}
```

**Note for the implementer:** the `sweepReturning` helper above mocks Embabel's fluent chain.
Before writing the implementation, confirm the exact return type of `Ai.withLlm(String)` and of
`respond(List<Message>)` by reading
`backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java` and, if needed,
`./gradlew :backend:dependencies --configuration compileClasspath | grep embabel`. Replace the
awkward `mock(...)` expression in `sweepReturning` with a direct
`mock(<ActualPromptRunnerType>.class)` and a stub of the actual message type returned by
`respond(...)`. The behavioural assertions in this test are correct as written; only the two
mock type references need pinning to the real API.

- [ ] **Step 3: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ReleaseSummarySweepTest'`
Expected: FAIL — `ReleaseSummarySweep` does not exist.

- [ ] **Step 4: Create `ReleaseSummarySweep`**

`backend/src/main/java/com/simonrowe/platform/ReleaseSummarySweep.java`:

```java
package com.simonrowe.platform;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes the release-notes paragraph for each release that does not have one yet.
 *
 * <p><b>Generation happens here, at ingest, and never on the read path.</b>
 * {@code GET /api/platform/releases} serves whatever is stored; a public endpoint that called
 * an LLM per request would be a cost and abuse problem, and {@code RateLimitInterceptor} does
 * not meter {@code /api/platform/**} (deliberately — the page issues two requests per view).
 *
 * <p>Batched rather than done in one pass at startup: a 50-release backfill in a single loop
 * would hold a thread for minutes and spike the bill in one burst. Three per tick every two
 * minutes clears the backfill in about 35 minutes.
 *
 * <p>Failure is never fatal to an entry. After {@code maxAttempts} the release is
 * {@code FAILED} and the page renders it from its commit subject alone.
 */
@Component
public class ReleaseSummarySweep {

  private static final Logger LOG = LoggerFactory.getLogger(ReleaseSummarySweep.class);

  private static final int MAX_FILES_IN_PROMPT = 40;

  /**
   * Versions {@link #SUMMARY_PROMPT}. Unlike {@code article-summary-v1} this does <em>not</em>
   * feed a document id — the id is the commit SHA — so bumping it does not invalidate stored
   * summaries. To regenerate after a prompt change, set every {@code READY} release back to
   * {@code PENDING} deliberately.
   */
  static final String SUMMARY_FORMAT_VERSION = "platform-release-v1";

  private static final String SUMMARY_PROMPT = """
      Write a short release note for one change to a personal website's codebase, for a \
      technically literate reader browsing a public changelog.

      Requirements:
      - One paragraph, 2 to 4 sentences. Plain prose, no Markdown, no heading, no bullet \
      list, no links.
      - Say what changed and why it matters to someone using or reading about the site. \
      Lead with the effect, not the mechanism.
      - Do not restate the commit subject verbatim, and do not open with "This commit" or \
      "This release".
      - Be concrete where the material allows: name the feature, page or component that \
      changed. Use the file list as evidence of scope, but do not list file names.
      - Say nothing the material below does not support. If it is thin, write one short \
      factual sentence rather than padding.

      Commit subject: %s

      Commit message body:
      %s

      Files changed (%d total, showing up to %d):
      %s
      """;

  private final PlatformReleaseRepository repository;
  private final Ai ai;
  private final boolean enabled;
  private final int batchSize;
  private final int maxAttempts;
  private final String model;

  public ReleaseSummarySweep(
      final PlatformReleaseRepository repository,
      final Ai ai,
      @Value("${platform.releases.summaries.enabled:true}") final boolean enabled,
      @Value("${platform.releases.summaries.batch-size:3}") final int batchSize,
      @Value("${platform.releases.summaries.max-attempts:3}") final int maxAttempts,
      @Value("${platform.releases.summaries.model}") final String model) {
    this.repository = repository;
    this.ai = ai;
    this.enabled = enabled;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.model = model;
  }

  /** Runs the sweep on a fixed delay, so a slow model call cannot overlap the next tick. */
  @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT2M")
  public void scheduledSweep() {
    try {
      sweep();
    } catch (RuntimeException e) {
      LOG.warn("Release summary sweep failed: {}", e.getMessage());
    }
  }

  /**
   * Summarises up to {@code batchSize} pending releases.
   *
   * @return how many were summarised in this tick
   */
  public int sweep() {
    if (!enabled) {
      return 0;
    }
    List<PlatformRelease> pending = repository.findPending(batchSize);
    int summarised = 0;
    for (PlatformRelease release : pending) {
      if (summarise(release)) {
        summarised++;
      }
    }
    return summarised;
  }

  private boolean summarise(final PlatformRelease release) {
    try {
      String completion = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt(release))))
          .getContent();
      if (completion == null || completion.isBlank()) {
        LOG.warn("Empty completion summarising release {}", release.getShortSha());
        return recordFailedAttempt(release);
      }
      release.setSummary(completion.trim());
      release.setSummaryStatus(ReleaseSummaryStatus.READY);
      release.setSummaryAttempts(release.getSummaryAttempts() + 1);
      release.setUpdatedAt(Instant.now());
      repository.save(release);
      LOG.info("Summarised release {} ({})", release.getShortSha(), SUMMARY_FORMAT_VERSION);
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Failed to summarise release {}: {}", release.getShortSha(), e.getMessage());
      return recordFailedAttempt(release);
    }
  }

  /**
   * Counts an attempt and gives up at the limit.
   *
   * @return always false — nothing was summarised
   */
  private boolean recordFailedAttempt(final PlatformRelease release) {
    int attempts = release.getSummaryAttempts() + 1;
    release.setSummaryAttempts(attempts);
    release.setSummaryStatus(
        attempts >= maxAttempts ? ReleaseSummaryStatus.FAILED : ReleaseSummaryStatus.PENDING);
    release.setUpdatedAt(Instant.now());
    repository.save(release);
    return false;
  }

  private static String prompt(final PlatformRelease release) {
    List<String> files = release.getFilesChanged();
    String shown = String.join(
        "\n", files.subList(0, Math.min(MAX_FILES_IN_PROMPT, files.size())));
    return String.format(
        SUMMARY_PROMPT,
        release.getSubject(),
        release.getBody() == null || release.getBody().isBlank() ? "(none)" : release.getBody(),
        files.size(),
        MAX_FILES_IN_PROMPT,
        shown.isBlank() ? "(none recorded)" : shown);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.ReleaseSummarySweepTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Confirm the sweep never runs in the test profile against a real model**

Run: `grep -rn "platform.releases" backend/src/test/resources/application-test.yml`

If the file does not already disable it, add:

```yaml
platform:
  releases:
    summaries:
      enabled: false
      model: test-model
```

`Ai` is a `@MockitoBean` on `AbstractIntegrationTest`, so no real call could happen anyway, but a
scheduled component firing in every integration test is noise. The sweep tests construct their
own instance with `enabled = true`, so disabling the bean does not weaken them.

- [ ] **Step 7: Run Checkstyle and the full platform suite**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest :backend:test --tests 'com.simonrowe.platform.*'`
Expected: PASS, 0 warnings.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/simonrowe/platform/ReleaseSummarySweep.java backend/src/test/java/com/simonrowe/platform/ReleaseSummarySweepTest.java backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml
git commit -m "feat: generate AI release notes on a scheduled sweep"
```

---

### Task 7: software-factory reports its own version

**Files:**
- Modify: `software-factory/build.gradle.kts` (add `springBoot { buildInfo }`)
- Create: `software-factory/src/main/java/com/simonrowe/factory/version/FactoryVersion.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/version/VersionController.java`
- Test: `software-factory/src/test/java/com/simonrowe/factory/version/VersionControllerTest.java`

**Interfaces:**
- Consumes: nothing from the backend module — these are separate Gradle modules with no shared code.
- Produces: `GET /api/version` on port 8090 returning JSON `{commit, shortCommit, commitSubject, commitTime, startedAt}`. Task 8's `FactoryVersionClient` deserialises exactly this shape.

- [ ] **Step 1: Write the failing test**

`software-factory/src/test/java/com/simonrowe/factory/version/VersionControllerTest.java`:

```java
package com.simonrowe.factory.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class VersionControllerTest {

  private static BuildProperties buildProperties() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "1756200000");
    properties.put("commitSubject", "feat: deploy automatically");
    return new BuildProperties(properties);
  }

  @Test
  void reportsTheBakedCommit() {
    FactoryVersion version = new VersionController(buildProperties()).version();

    assertThat(version.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(version.shortCommit()).isEqualTo("840c311");
    assertThat(version.commitSubject()).isEqualTo("feat: deploy automatically");
    assertThat(version.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
  }

  @Test
  void reportsADevBuildWhenNoBuildInfoIsPresent() {
    FactoryVersion version = new VersionController(null).version();

    assertThat(version.commit()).isEqualTo("unknown");
    assertThat(version.shortCommit()).isEqualTo("dev");
    assertThat(version.commitTime()).isNull();
  }

  @Test
  void startedAtIsStableAcrossCalls() {
    VersionController controller = new VersionController(buildProperties());

    assertThat(controller.version().startedAt()).isEqualTo(controller.version().startedAt());
  }

  @Test
  void treatsAnEpochCommitTimeAsUnknown() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "0");

    assertThat(new VersionController(new BuildProperties(properties)).version().commitTime())
        .isNull();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from repo root: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.version.VersionControllerTest'`
Expected: FAIL — the classes do not exist.

- [ ] **Step 3: Create the `FactoryVersion` record**

`software-factory/src/main/java/com/simonrowe/factory/version/FactoryVersion.java`:

```java
package com.simonrowe.factory.version;

import java.time.Instant;

/**
 * This container's version, as served to the backend for the public status page.
 *
 * @param commit the full commit SHA, or {@code unknown}
 * @param shortCommit the seven-character SHA, or {@code dev}
 * @param commitSubject the commit subject line, or null
 * @param commitTime when the commit was authored, or null when unknown
 * @param startedAt when this JVM started
 */
public record FactoryVersion(
    String commit,
    String shortCommit,
    String commitSubject,
    Instant commitTime,
    Instant startedAt) {
}
```

- [ ] **Step 4: Create `VersionController`**

`software-factory/src/main/java/com/simonrowe/factory/version/VersionController.java`:

```java
package com.simonrowe.factory.version;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports which commit this container was built from.
 *
 * <p><b>Deliberately unauthenticated, unlike every other endpoint in this module.</b> nginx
 * routes only {@code POST /webhooks/github}, so this path is reachable only from inside the
 * Docker network, and the sole thing it discloses is a commit SHA from a public repository.
 *
 * <p>The alternative — reusing {@code X-Factory-Token} as {@code ReviewController} does —
 * would mean giving the backend a token that also authorises {@code /api/reviews}. Widening
 * the backend's privileges to publish a public SHA is a bad trade. Do not add the token check
 * here; add nothing else to this controller either, because the no-auth reasoning holds only
 * for this one payload.
 *
 * <p>This is also the surface that makes deployer drift visible: {@code deployer} excludes
 * itself from its own recreate list, so it does not self-update, and the status page comparing
 * its SHA against the backend's is how that gets noticed rather than discovered months later.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

  private static final String UNKNOWN_COMMIT = "unknown";
  private static final String DEV_SHORT_COMMIT = "dev";
  private static final int SHORT_SHA_LENGTH = 7;

  private final BuildProperties buildProperties;
  private final Instant startedAt;

  @Autowired
  public VersionController(@Nullable final BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
    this.startedAt = Instant.now();
  }

  /**
   * This container's version.
   *
   * @return the version; never null
   */
  @GetMapping
  public FactoryVersion version() {
    String commit = property("commit");
    String resolved = commit == null || commit.isBlank() ? UNKNOWN_COMMIT : commit;
    return new FactoryVersion(
        resolved,
        UNKNOWN_COMMIT.equals(resolved)
            ? DEV_SHORT_COMMIT
            : resolved.substring(0, Math.min(SHORT_SHA_LENGTH, resolved.length())),
        property("commitSubject"),
        commitTime(),
        startedAt);
  }

  private Instant commitTime() {
    String value = property("commitTime");
    if (value == null || value.isBlank()) {
      return null;
    }
    long epochSeconds = Long.parseLong(value.trim());
    // The Gradle task writes 0 when git was unavailable, to keep the output deterministic.
    return epochSeconds == 0L ? null : Instant.ofEpochSecond(epochSeconds);
  }

  private String property(final String key) {
    return buildProperties == null ? null : buildProperties.get(key);
  }
}
```

- [ ] **Step 5: Add `buildInfo` to `software-factory/build.gradle.kts`**

Insert after the `jacoco { ... }` block, before `tasks.jacocoTestReport`:

```kotlin
// Same mechanism and same reasoning as backend/build.gradle.kts: the commit SHA is baked in
// so GET /api/version can report it, and `time` is the COMMIT time so the output is
// deterministic and does not invalidate the Gradle build cache on every build.
//
// This is what makes deployer drift visible. `deployer` excludes itself from its own
// recreate list, so it does not self-update; without a reported SHA that goes unnoticed.
val factoryGitDir = rootProject.file(".git")

fun factoryGitText(vararg args: String): Provider<String> =
    if (!factoryGitDir.exists()) {
        providers.provider { "" }
    } else {
        providers.exec {
            workingDir = rootProject.projectDir
            commandLine(listOf("git") + args)
            isIgnoreExitValue = true
        }.standardOutput.asText
    }

val factoryHeadSha: Provider<String> = factoryGitText("rev-parse", "HEAD").map { it.trim() }
val factoryHeadSubject: Provider<String> =
    factoryGitText("log", "-1", "--format=%s").map { it.trim() }
val factoryHeadEpoch: Provider<String> =
    factoryGitText("log", "-1", "--format=%ct").map { it.trim() }

springBoot {
    buildInfo {
        properties {
            time.set(factoryHeadEpoch.map {
                java.time.Instant.ofEpochSecond(it.ifBlank { "0" }.toLong())
            })
            additional.put("commit", factoryHeadSha.map { it.ifBlank { "unknown" } })
            additional.put("commitTime", factoryHeadEpoch.map { it.ifBlank { "0" } })
            additional.put("commitSubject", factoryHeadSubject.map { it.ifBlank { "" } })
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run from repo root: `./gradlew :software-factory:test --tests 'com.simonrowe.factory.version.VersionControllerTest'`
Expected: PASS, 4 tests.

- [ ] **Step 7: Verify the endpoint is not routed by nginx**

Run: `grep -n "api/version" config/nginx/nginx-proxy.conf`
Expected: **no output.** If there is any output, the endpoint is internet-reachable and the
no-authentication reasoning in the class Javadoc no longer holds — stop and reconsider.

- [ ] **Step 8: Run Checkstyle**

Run from repo root: `./gradlew :software-factory:checkstyleMain :software-factory:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 9: Commit**

```bash
git add software-factory/build.gradle.kts software-factory/src/main/java/com/simonrowe/factory/version software-factory/src/test/java/com/simonrowe/factory/version
git commit -m "feat: report the software-factory and deployer commit over the internal network"
```

---

### Task 8: Fetch the factory and deployer versions from the backend

**Files:**
- Create: `backend/src/main/java/com/simonrowe/platform/FactoryVersionClient.java`
- Modify: `backend/src/main/resources/application.yml` (add `platform.services` under the `platform` block)
- Test: `backend/src/test/java/com/simonrowe/platform/FactoryVersionClientTest.java`

**Interfaces:**
- Consumes: `ServiceVersion` (Task 1).
- Produces: `FactoryVersionClient.versions()` returning `List<ServiceVersion>`, one per configured service, in configuration order. Unreachable services come back as `ServiceVersion.unreachable(name)`.

- [ ] **Step 1: Add the configuration**

Add to `backend/src/main/resources/application.yml`, inside the existing `platform:` block added in Task 6:

```yaml
  # The other first-party JVMs, asked over the Docker network. Neither is routed by nginx.
  # Note the port: software-factory serves on 8090, not 8080.
  services:
    factory-base-url: ${PLATFORM_FACTORY_URL:http://software-factory:8090}
    deployer-base-url: ${PLATFORM_DEPLOYER_URL:http://deployer:8090}
    # A status page must not be able to hang on a restarting sibling container.
    timeout: 1s
    cache-ttl: 60s
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/simonrowe/platform/FactoryVersionClientTest.java`:

```java
package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactoryVersionClientTest {

  private MockWebServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stopServer() throws IOException {
    server.close();
  }

  private FactoryVersionClient client(final String factoryUrl, final String deployerUrl) {
    return new FactoryVersionClient(
        factoryUrl, deployerUrl, Duration.ofSeconds(1), Duration.ZERO);
  }

  @Test
  void readsTheReportedVersion() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""
            {"commit":"840c311abcdef0123456789abcdef0123456789a","shortCommit":"840c311",
             "commitSubject":"feat: deploy automatically","commitTime":"2026-08-26T14:02:11Z",
             "startedAt":"2026-08-24T09:15:03Z"}
            """)
        .build());
    String url = server.url("/").toString();

    List<ServiceVersion> versions = client(url, "http://127.0.0.1:1/").versions();

    ServiceVersion factory = versions.get(0);
    assertThat(factory.name()).isEqualTo("software-factory");
    assertThat(factory.reachable()).isTrue();
    assertThat(factory.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(factory.shortCommit()).isEqualTo("840c311");
    assertThat(factory.commitSubject()).isEqualTo("feat: deploy automatically");
  }

  @Test
  void reportsUnreachableRatherThanFailing() {
    // Port 1 is never listening.
    List<ServiceVersion> versions = client("http://127.0.0.1:1/", "http://127.0.0.1:1/").versions();

    assertThat(versions).hasSize(2);
    assertThat(versions).allSatisfy(v -> assertThat(v.reachable()).isFalse());
    assertThat(versions).extracting(ServiceVersion::name)
        .containsExactly("software-factory", "deployer");
  }

  @Test
  void reportsUnreachableOnAnErrorStatus() {
    server.enqueue(new MockResponse.Builder().code(503).build());

    List<ServiceVersion> versions =
        client(server.url("/").toString(), "http://127.0.0.1:1/").versions();

    assertThat(versions.get(0).reachable()).isFalse();
  }

  @Test
  void reportsUnreachableOnMalformedJson() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("not json")
        .build());

    List<ServiceVersion> versions =
        client(server.url("/").toString(), "http://127.0.0.1:1/").versions();

    assertThat(versions.get(0).reachable()).isFalse();
  }

  @Test
  void cachesWithinTheTtl() {
    server.enqueue(new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("{\"commit\":\"840c311a\",\"shortCommit\":\"840c311\"}")
        .build());
    FactoryVersionClient cached = new FactoryVersionClient(
        server.url("/").toString(), "http://127.0.0.1:1/",
        Duration.ofSeconds(1), Duration.ofMinutes(5));

    cached.versions();
    cached.versions();

    // One enqueued response, two calls: the second must not have hit the network.
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void alwaysReturnsBothServicesInConfigurationOrder() {
    assertThat(client("http://127.0.0.1:1/", "http://127.0.0.1:1/").versions())
        .extracting(ServiceVersion::name)
        .containsExactly("software-factory", "deployer");
  }
}
```

**Note for the implementer:** confirm the `MockWebServer` API available in this project before
writing the implementation — `grep -rn "MockWebServer" backend/src/test` and check
`gradle/libs.versions.toml` for `mockwebserver`. If it is not already a test dependency, do
**not** add one (Global Constraints): replace `MockWebServer` with a `com.sun.net.httpserver.HttpServer`
started on port 0, which is in the JDK. The assertions above are unchanged either way; only the
fake-server plumbing differs. For the caching test with `HttpServer`, count requests with an
`AtomicInteger` in the handler.

- [ ] **Step 3: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.FactoryVersionClientTest'`
Expected: FAIL — `FactoryVersionClient` does not exist.

- [ ] **Step 4: Create `FactoryVersionClient`**

`backend/src/main/java/com/simonrowe/platform/FactoryVersionClient.java`:

```java
package com.simonrowe.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks {@code software-factory} and {@code deployer} which commit they are running.
 *
 * <p>Both are on the Docker network and neither is routed by nginx, so this is an
 * unauthenticated internal call. Note the port: those containers serve on <b>8090</b>.
 *
 * <p>Two properties are load-bearing for a public page:
 * <ul>
 *   <li><b>A short timeout.</b> The status endpoint must not hang because a sibling container
 *       is restarting, so a failed or slow probe degrades to {@code reachable: false} inside
 *       one second rather than propagating.</li>
 *   <li><b>A cache.</b> The page is public and cheap to hit; without a TTL every view would
 *       put two extra HTTP calls on the two containers that run deploys.</li>
 * </ul>
 */
@Component
public class FactoryVersionClient {

  private static final Logger LOG = LoggerFactory.getLogger(FactoryVersionClient.class);

  private static final String FACTORY = "software-factory";
  private static final String DEPLOYER = "deployer";
  private static final String VERSION_PATH = "/api/version";

  private final RestClient factoryClient;
  private final RestClient deployerClient;
  private final Duration cacheTtl;
  private final AtomicReference<Cached> cache = new AtomicReference<>(null);

  public FactoryVersionClient(
      // Defaults on every one of these, not just the durations: an integration test context
      // has no platform.services block, and a @Value with no default would fail every
      // @SpringBootTest in the module rather than only this feature's tests.
      @Value("${platform.services.factory-base-url:http://software-factory:8090}")
      final String factoryBaseUrl,
      @Value("${platform.services.deployer-base-url:http://deployer:8090}")
      final String deployerBaseUrl,
      @Value("${platform.services.timeout:1s}") final Duration timeout,
      @Value("${platform.services.cache-ttl:60s}") final Duration cacheTtl) {
    this.factoryClient = client(factoryBaseUrl, timeout);
    this.deployerClient = client(deployerBaseUrl, timeout);
    this.cacheTtl = cacheTtl;
  }

  private static RestClient client(final String baseUrl, final Duration timeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(new RestTemplateBuilder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .buildRequestFactory())
        .build();
  }

  /**
   * The two sibling services' versions, in a fixed order.
   *
   * @return exactly two entries, {@code software-factory} then {@code deployer}; unreachable
   *     services report {@link ServiceVersion#unreachable(String)} rather than being omitted,
   *     because "not reporting" is information the page should show
   */
  public List<ServiceVersion> versions() {
    Cached current = cache.get();
    if (current != null && !current.isStale(cacheTtl)) {
      return current.versions();
    }
    List<ServiceVersion> fetched =
        List.of(fetch(FACTORY, factoryClient), fetch(DEPLOYER, deployerClient));
    cache.set(new Cached(fetched, Instant.now()));
    return fetched;
  }

  private static ServiceVersion fetch(final String name, final RestClient client) {
    try {
      ReportedVersion reported =
          client.get().uri(VERSION_PATH).retrieve().body(ReportedVersion.class);
      if (reported == null || reported.commit() == null) {
        return ServiceVersion.unreachable(name);
      }
      return new ServiceVersion(
          name,
          reported.commit(),
          reported.shortCommit(),
          reported.commitSubject(),
          reported.commitTime(),
          reported.startedAt(),
          true);
    } catch (RuntimeException e) {
      LOG.debug("Could not read {} version: {}", name, e.getMessage());
      return ServiceVersion.unreachable(name);
    }
  }

  /** The wire shape of software-factory's {@code GET /api/version}. */
  private record ReportedVersion(
      String commit,
      String shortCommit,
      String commitSubject,
      Instant commitTime,
      Instant startedAt) {
  }

  private record Cached(List<ServiceVersion> versions, Instant fetchedAt) {

    boolean isStale(final Duration ttl) {
      return ttl.isZero() || ttl.isNegative()
          || fetchedAt.plus(ttl).isBefore(Instant.now());
    }
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.FactoryVersionClientTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Run Checkstyle**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/simonrowe/platform/FactoryVersionClient.java backend/src/test/java/com/simonrowe/platform/FactoryVersionClientTest.java backend/src/main/resources/application.yml
git commit -m "feat: read sibling service versions over the Docker network"
```

---

### Task 9: The two public endpoints

**Files:**
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformStatusResponse.java`
- Create: `backend/src/main/java/com/simonrowe/platform/ReleaseResponse.java`
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformStatusService.java`
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformStatusController.java`
- Create: `backend/src/main/java/com/simonrowe/platform/PlatformReleasesController.java`
- Test: `backend/src/test/java/com/simonrowe/platform/PlatformStatusControllerTest.java`
- Test: `backend/src/test/java/com/simonrowe/platform/PlatformReleasesControllerTest.java`

**Interfaces:**
- Consumes: `RunningVersion` (1), `ProdImageCatalog` / `PlatformComponent` (2), `PlatformReleaseRepository` / `PlatformRelease` (4), `FactoryVersionClient` / `ServiceVersion` (8).
- Produces: `GET /api/platform/status` → `PlatformStatusResponse(List<ServiceVersion> services, List<PlatformComponent> components)`. `GET /api/platform/releases?limit=20` → `List<ReleaseResponse>` where `ReleaseResponse(String sha, String shortSha, String type, String subject, Instant commitTime, boolean running, String summary, ReleaseSummaryStatus summaryStatus)`. **The frontend types in Task 12 mirror these two shapes field-for-field.**

**No `SecurityConfig` change is required** — it ends with `.anyRequest().permitAll()`. Task 9 only asserts that.

- [ ] **Step 1: Write the failing status test**

`backend/src/test/java/com/simonrowe/platform/PlatformStatusControllerTest.java`:

```java
package com.simonrowe.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

class PlatformStatusControllerTest extends AbstractIntegrationTest {

  @Test
  void isPublicAndNeedsNoAuthentication() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk());
  }

  @Test
  void reportsTheBackendFirstAndAlwaysReachable() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services[0].name").value("backend"))
        .andExpect(jsonPath("$.services[0].reachable").value(true))
        .andExpect(jsonPath("$.services[0].commit").exists())
        .andExpect(jsonPath("$.services[0].shortCommit").exists());
  }

  @Test
  void reportsTheSiblingServicesEvenWhenUnreachable() throws Exception {
    // Nothing is listening on software-factory:8090 in a test, so both must come back
    // as not reporting rather than being omitted or failing the request.
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services[1].name").value("software-factory"))
        .andExpect(jsonPath("$.services[1].reachable").value(false))
        .andExpect(jsonPath("$.services[2].name").value("deployer"))
        .andExpect(jsonPath("$.services[2].reachable").value(false));
  }

  @Test
  void listsThirdPartyComponentsWithoutFirstPartyOnes() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[?(@.name == 'mongodb')].tag").value("8"))
        .andExpect(jsonPath("$.components[?(@.name == 'backend')]").isEmpty())
        .andExpect(jsonPath("$.components[?(@.name == 'alloy')].floating").value(true));
  }
}
```

- [ ] **Step 2: Write the failing releases test**

`backend/src/test/java/com/simonrowe/platform/PlatformReleasesControllerTest.java`:

```java
package com.simonrowe.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class PlatformReleasesControllerTest extends AbstractIntegrationTest {

  private static final String NEWER = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER = "39e0f7aabcdef0123456789abcdef0123456789a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RunningVersion runningVersion;

  @BeforeEach
  void seed() {
    mongoTemplate.dropCollection(PlatformRelease.class);
    store(NEWER, 1756200000L, "docs: overhaul the README (#118)", ReleaseSummaryStatus.READY,
        "The README was rewritten.");
    store(OLDER, 1756100000L, "feat: deploy automatically (#116)", ReleaseSummaryStatus.PENDING,
        null);
  }

  private void store(
      final String sha,
      final long epoch,
      final String subject,
      final ReleaseSummaryStatus status,
      final String summary) {
    PlatformRelease release = PlatformRelease.fromBaked(
        new BakedRelease(sha, Instant.ofEpochSecond(epoch), subject, "", List.of("a.java")),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(epoch));
    release.setSummaryStatus(status);
    release.setSummary(summary);
    repository.save(release);
  }

  @Test
  void isPublicAndNeedsNoAuthentication() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk());
  }

  @Test
  void returnsReleasesNewestFirst() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sha").value(NEWER))
        .andExpect(jsonPath("$[0].shortSha").value("840c311"))
        .andExpect(jsonPath("$[0].type").value("docs"))
        .andExpect(jsonPath("$[0].subject").value("docs: overhaul the README (#118)"))
        .andExpect(jsonPath("$[0].summary").value("The README was rewritten."))
        .andExpect(jsonPath("$[0].summaryStatus").value("READY"))
        .andExpect(jsonPath("$[1].sha").value(OLDER))
        .andExpect(jsonPath("$[1].type").value("feat"));
  }

  @Test
  void exposesAPendingSummaryAsPendingRatherThanHidingTheEntry() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[1].summaryStatus").value("PENDING"))
        .andExpect(jsonPath("$[1].subject").value("feat: deploy automatically (#116)"));
  }

  @Test
  void honoursTheLimitParameter() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].sha").value(NEWER));
  }

  @Test
  void clampsAnAbsurdLimitRatherThanServingTheWholeCollection() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(Matchers.lessThanOrEqualTo(100))));
  }

  @Test
  void rejectsANonPositiveLimit() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void marksTheRunningReleaseWhenThisBuildMatchesOne() throws Exception {
    // In a test build the running SHA is whatever HEAD was at compile time, which will not
    // match the seeded fixtures — so assert the flag is present and false rather than
    // asserting a specific entry is running.
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].running")
            .value(runningVersion.commit().equals(NEWER)));
  }

  @Test
  void returnsAnEmptyArrayWhenNothingHasBeenSeeded() throws Exception {
    mongoTemplate.dropCollection(PlatformRelease.class);

    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(0)));
  }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.Platform*ControllerTest'`
Expected: FAIL — the controllers do not exist (404 / compilation error).

- [ ] **Step 4: Create the two response records**

`backend/src/main/java/com/simonrowe/platform/PlatformStatusResponse.java`:

```java
package com.simonrowe.platform;

import java.util.List;

/**
 * What {@code GET /api/platform/status} returns.
 *
 * <p>The frontend adds its own entry to {@code services} client-side: the backend cannot know
 * which bundle a browser loaded, and a guess would be wrong exactly when it mattered.
 *
 * @param services the first-party JVM services, backend first
 * @param components the third-party images production declares
 */
public record PlatformStatusResponse(
    List<ServiceVersion> services, List<PlatformComponent> components) {
}
```

`backend/src/main/java/com/simonrowe/platform/ReleaseResponse.java`:

```java
package com.simonrowe.platform;

import java.time.Instant;

/**
 * One changelog entry as served.
 *
 * <p>{@code summary} is null while {@code summaryStatus} is not {@code READY}. The entry is
 * still returned: the page renders the commit subject with a pending note rather than hiding
 * a release because its paragraph has not been written yet.
 *
 * @param sha the full commit SHA
 * @param shortSha the seven-character SHA, as rendered and linked
 * @param type the conventional-commit type, or {@code other}
 * @param subject the commit subject line
 * @param commitTime when the commit was authored
 * @param running true when the backend serving this response was built from this commit
 * @param summary the AI-written release note, or null
 * @param summaryStatus where that summary has got to
 */
public record ReleaseResponse(
    String sha,
    String shortSha,
    String type,
    String subject,
    Instant commitTime,
    boolean running,
    String summary,
    ReleaseSummaryStatus summaryStatus) {

  /**
   * Maps a stored release for the wire.
   *
   * @param release the stored release
   * @param runningSha the SHA this backend was built from
   * @return the response entry
   */
  static ReleaseResponse from(final PlatformRelease release, final String runningSha) {
    return new ReleaseResponse(
        release.getId(),
        release.getShortSha(),
        release.getType(),
        release.getSubject(),
        release.getCommitTime(),
        release.getId().equals(runningSha),
        release.getSummaryStatus() == ReleaseSummaryStatus.READY ? release.getSummary() : null,
        release.getSummaryStatus());
  }
}
```

- [ ] **Step 5: Create `PlatformStatusService`**

`backend/src/main/java/com/simonrowe/platform/PlatformStatusService.java`:

```java
package com.simonrowe.platform;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Assembles the status payload and the changelog.
 *
 * <p><b>Nothing reachable from here may call an LLM.</b> Summaries are written at ingest by
 * {@code ReleaseSummarySweep}; this class only reads what is stored.
 */
@Service
public class PlatformStatusService {

  /** Hard ceiling on the changelog page size, so a crafted request cannot dump the collection. */
  static final int MAX_LIMIT = 100;

  private final RunningVersion runningVersion;
  private final FactoryVersionClient factoryVersionClient;
  private final ProdImageCatalog imageCatalog;
  private final PlatformReleaseRepository releaseRepository;

  public PlatformStatusService(
      final RunningVersion runningVersion,
      final FactoryVersionClient factoryVersionClient,
      final ProdImageCatalog imageCatalog,
      final PlatformReleaseRepository releaseRepository) {
    this.runningVersion = runningVersion;
    this.factoryVersionClient = factoryVersionClient;
    this.imageCatalog = imageCatalog;
    this.releaseRepository = releaseRepository;
  }

  /**
   * What is running right now.
   *
   * @return the status; the backend is always first and always reachable
   */
  public PlatformStatusResponse status() {
    List<ServiceVersion> services = new ArrayList<>();
    services.add(runningVersion.current());
    services.addAll(factoryVersionClient.versions());
    return new PlatformStatusResponse(List.copyOf(services), imageCatalog.components());
  }

  /**
   * The changelog, newest first.
   *
   * @param limit how many entries to return; clamped to {@link #MAX_LIMIT}
   * @return the entries; empty when nothing has been seeded yet
   */
  public List<ReleaseResponse> releases(final int limit) {
    String runningSha = runningVersion.commit();
    return releaseRepository.findRecent(Math.min(limit, MAX_LIMIT)).stream()
        .map(release -> ReleaseResponse.from(release, runningSha))
        .toList();
  }
}
```

- [ ] **Step 6: Create the two controllers**

`backend/src/main/java/com/simonrowe/platform/PlatformStatusController.java`:

```java
package com.simonrowe.platform;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is running in production right now.
 *
 * <p>Public, no authentication — {@code SecurityConfig} ends with
 * {@code .anyRequest().permitAll()}, so this needs no matcher; {@code SecurityConfigTest}
 * asserts that posture deliberately rather than by accident. Everything here is already
 * public information: image tags come from a public compose file and commit SHAs from a public
 * repository.
 *
 * <p><b>Deliberately not metered by {@code RateLimitInterceptor}.</b> The page issues this
 * request plus one for the changelog on every view, and the footer badge on every page reads
 * from the bundle rather than from here precisely so that a site-wide fetch is avoided.
 * Adding this path to the interceptor would break the page for ordinary readers.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformStatusController {

  private final PlatformStatusService statusService;

  public PlatformStatusController(final PlatformStatusService statusService) {
    this.statusService = statusService;
  }

  /**
   * The running services and the platform components.
   *
   * @return the status; never a 404, an empty component list is a valid answer
   */
  @GetMapping("/status")
  public PlatformStatusResponse status() {
    return statusService.status();
  }
}
```

`backend/src/main/java/com/simonrowe/platform/PlatformReleasesController.java`:

```java
package com.simonrowe.platform;

import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The changelog: recent releases with their AI-written release notes.
 *
 * <p>Public, no authentication, and serves only stored data — see
 * {@link PlatformStatusController} for why neither is metered.
 *
 * <p>Entries other than the one flagged {@code running} evidence that an image was
 * <em>published</em>, not that it was deployed: {@code deploy_runs} is empty because
 * auto-deploy is off, so deployment history does not exist to report. The page's wording
 * carries that distinction.
 */
@RestController
@RequestMapping("/api/platform")
@Validated
public class PlatformReleasesController {

  private static final int DEFAULT_LIMIT = 20;

  private final PlatformStatusService statusService;

  public PlatformReleasesController(final PlatformStatusService statusService) {
    this.statusService = statusService;
  }

  /**
   * Recent releases, newest first.
   *
   * @param limit how many to return; clamped server-side to 100
   * @return the entries; an empty array when nothing has been seeded, never a 404
   */
  @GetMapping("/releases")
  public List<ReleaseResponse> releases(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) final int limit) {
    return statusService.releases(limit);
  }
}
```

- [ ] **Step 7: Run both tests to verify they pass**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.platform.Platform*ControllerTest'`
Expected: PASS, 12 tests.

If `rejectsANonPositiveLimit` returns 500 rather than 400, the project has no handler mapping
`ConstraintViolationException` to 400. Check with
`grep -rn "ConstraintViolationException" backend/src/main/java`. If none exists, replace the
`@Min(1)` validation with an explicit check in the controller that throws
`new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be at least 1")` — matching
whatever the codebase already does for parameter validation. Do not add a new global exception
handler for this one endpoint.

- [ ] **Step 8: Assert the public posture in `SecurityConfigTest`**

Find the test: `grep -rn "class SecurityConfigTest" backend/src/test/java`

Add two cases following the file's existing style (read the neighbouring cases first and match
them — some assert via `mockMvc`, some via `FilterChainProxy`):

```java
  @Test
  void platformStatusIsPublic() throws Exception {
    mockMvc.perform(get("/api/platform/status"))
        .andExpect(status().isOk());
  }

  @Test
  void platformReleasesArePublic() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk());
  }
```

- [ ] **Step 9: Run the security test**

Run from `backend/`: `../gradlew :backend:test --tests '*SecurityConfigTest'`
Expected: PASS.

- [ ] **Step 10: Verify the endpoints are unmetered**

Run: `grep -n "platform" backend/src/main/java/com/simonrowe/ratelimit/RateLimitInterceptor.java`
Expected: **no output.** Any output means the page will 429 for ordinary readers.

- [ ] **Step 11: Run Checkstyle and the whole platform suite**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest :backend:test --tests 'com.simonrowe.platform.*'`
Expected: PASS, 0 warnings.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/simonrowe/platform backend/src/test/java/com/simonrowe/platform backend/src/test/java/com/simonrowe/auth
git commit -m "feat: serve the platform status and release changelog publicly"
```

---

### Task 10: Include `platform_releases` in backup and restore

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/dataops/BackupService.java:40-48`
- Modify: `backend/src/main/java/com/simonrowe/dataops/RestoreService.java:30-39`
- Test: `backend/src/test/java/com/simonrowe/dataops/BackupCollectionsTest.java`

**Interfaces:**
- Consumes: `V022CreatePlatformReleaseIndexes.COLLECTION` (Task 4) — reference the constant rather than re-typing the string, so the two cannot drift.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/simonrowe/dataops/BackupCollectionsTest.java`:

```java
package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Guards a mistake this repository has made twice: adding a collection holding paid-for
 * generated content and forgetting the backup lists, so a restore silently discards it.
 */
class BackupCollectionsTest {

  private static final String PLATFORM_RELEASES = "platform_releases";

  @SuppressWarnings("unchecked")
  private static Collection<String> readList(final Class<?> type, final String fieldName)
      throws ReflectiveOperationException {
    Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Collection<String>) field.get(null);
  }

  @Test
  void platformReleasesAreBackedUp() throws ReflectiveOperationException {
    assertThat(readList(BackupService.class, "BACKUP_COLLECTIONS"))
        .contains(PLATFORM_RELEASES);
  }

  @Test
  void platformReleasesAreRestored() throws ReflectiveOperationException {
    assertThat(readList(RestoreService.class, "IMPORT_ORDER_INDEPENDENT"))
        .contains(PLATFORM_RELEASES);
  }

  @Test
  void everyGeneratedContentCollectionIsBackedUp() throws ReflectiveOperationException {
    // article_summaries and narrations each cost money to produce; so does platform_releases.
    assertThat(readList(BackupService.class, "BACKUP_COLLECTIONS"))
        .contains("article_summaries", "narrations", PLATFORM_RELEASES);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.dataops.BackupCollectionsTest'`
Expected: FAIL — `platform_releases` is in neither list.

- [ ] **Step 3: Add the collection to `BackupService`**

In `backend/src/main/java/com/simonrowe/dataops/BackupService.java`, extend `BACKUP_COLLECTIONS`:

```java
  private static final Set<String> BACKUP_COLLECTIONS = Set.of(
      "blogs", "tags", "skills", "skill_groups", "jobs",
      "profiles", "social_medias", "tourSteps", "media_assets",
      "code_examples", "aggregated_articles", "aggregated_events",
      "content_sources", "favourites", "narrations",
      // Generated article summaries cost an LLM call each, so a backup that skipped them
      // would silently discard paid-for content on the next restore.
      "article_summaries",
      // Release notes on /status are the same: one LLM call per release, and the backfill
      // of history is only baked into the image that produced it — a restore into a newer
      // image could not regenerate the older entries at all.
      "platform_releases"
  );
```

- [ ] **Step 4: Add the collection to `RestoreService`**

In `backend/src/main/java/com/simonrowe/dataops/RestoreService.java`, extend
`IMPORT_ORDER_INDEPENDENT`:

```java
  private static final List<String> IMPORT_ORDER_INDEPENDENT = List.of(
      "tags", "skills", "profiles", "social_medias", "tourSteps", "media_assets",
      "content_sources", "aggregated_articles", "aggregated_events",
      // favourites hold no @DBRef, but they point at aggregated_articles and
      // aggregated_events by plain id, so they follow both.
      "favourites",
      // Article summaries are the same shape: no @DBRef, one plain articleId pointing at
      // aggregated_articles, so they follow it here rather than in the ordered list.
      "article_summaries",
      // Releases reference nothing at all — the _id is a commit SHA — so order is free.
      "platform_releases"
  );
```

- [ ] **Step 5: Re-create the indexes after a restore**

A restore drops collections, taking their indexes with them. Find where `RestoreService`
re-establishes indexes for the other generated collections:

```bash
grep -n "ensureIndexes\|createIndexes\|FAVOURITES_UNIQUE_INDEX\|narrationRestoreValidator" backend/src/main/java/com/simonrowe/dataops/RestoreService.java
```

Add a call alongside those, following whatever pattern is already there:

```java
    // Mongock will not re-run a change unit it has already recorded, so the indexes a
    // restore just dropped have to be re-created directly. Same reason
    // NarrationRestoreValidator.ensureIndexes() exists.
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);
```

This requires making `V022CreatePlatformReleaseIndexes.COLLECTION` and the class itself
importable from `com.simonrowe.dataops` — the class is already `public` with a `public static`
method, so only the import is needed. If Checkstyle objects to the cross-package import,
mirror exactly how `RestoreService` already reaches the narration validator.

- [ ] **Step 6: Run the test to verify it passes**

Run from `backend/`: `../gradlew :backend:test --tests 'com.simonrowe.dataops.*'`
Expected: PASS, including the existing `dataops` tests.

- [ ] **Step 7: Run Checkstyle**

Run from `backend/`: `../gradlew :backend:checkstyleMain :backend:checkstyleTest`
Expected: PASS, 0 warnings.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/simonrowe/dataops backend/src/test/java/com/simonrowe/dataops/BackupCollectionsTest.java
git commit -m "feat: back up and restore the release changelog"
```

---

### Task 11: Wire the build metadata through CI

**Files:**
- Modify: `.github/workflows/publish.yml:14-20` (backend job checkout), `:47-52` (frontend job checkout), `:60-72` (frontend build args), `:75-82` (software-factory job checkout)
- Modify: `Dockerfile.frontend`
- Test: manual verification steps below, plus a CI run

**Interfaces:**
- Consumes: the Gradle `buildInfo` and `generateReleaseHistory` tasks (Tasks 1, 3, 7).
- Produces: `VITE_GIT_SHA` and `VITE_BUILD_TIME` in the frontend bundle's `import.meta.env`, consumed by Task 12's `config/version.ts`.

**This task is where the feature silently half-works if done wrong.** A shallow checkout
produces a one-entry changelog that looks like a successful build.

- [ ] **Step 1: Add `fetch-depth: 0` to the three image-building checkouts**

In `.github/workflows/publish.yml`, replace each of the first three `- uses: actions/checkout@v4`
(the backend, frontend and software-factory publish jobs) with:

```yaml
      - uses: actions/checkout@v4
        with:
          # fetch-depth: 0 is REQUIRED, not an optimisation. The default is a depth-1
          # shallow clone, so `git log` sees exactly ONE commit and the /status changelog
          # ships with a single entry — a failure that looks identical to success.
          # backend/build.gradle.kts's generateReleaseHistory task reads 50 commits.
          fetch-depth: 0
```

Leave the fourth checkout (the verification job at line ~105) alone unless it also runs a
Gradle build that bakes metadata — check with
`sed -n '98,135p' .github/workflows/publish.yml` and add it if it does.

- [ ] **Step 2: Add the two build args to the frontend job**

In the `publish-frontend` job's `docker/build-push-action@v6` step, extend `build-args`:

```yaml
          build-args: |
            VITE_API_BASE_URL=https://api.simonrowe.dev
            VITE_RECAPTCHA_SITE_KEY=${{ secrets.VITE_RECAPTCHA_SITE_KEY }}
            VITE_GA_MEASUREMENT_ID=${{ secrets.VITE_GA_MEASUREMENT_ID }}
            VITE_GIT_SHA=${{ github.sha }}
            VITE_BUILD_TIME=${{ github.event.head_commit.timestamp }}
```

If `github.event.head_commit.timestamp` is empty for this workflow's trigger (it is populated
for `push`, not for `workflow_dispatch`), fall back to a step that computes it:

```yaml
      - name: Resolve build time
        id: build-time
        run: echo "value=$(git log -1 --format=%cI)" >> "$GITHUB_OUTPUT"
```

and use `VITE_BUILD_TIME=${{ steps.build-time.outputs.value }}`. Prefer this second form — it
works for every trigger and is the commit time, matching the backend's `buildInfo` semantics.
Check the workflow's `on:` block first: `sed -n '1,14p' .github/workflows/publish.yml`.

- [ ] **Step 3: Accept the args in `Dockerfile.frontend`**

Add after the existing `ARG VITE_GA_MEASUREMENT_ID` block, before the `RUN rm -f .env` line:

```dockerfile
# The commit this bundle was built from. Reported by the footer badge and /status, which is
# how frontend/backend version drift becomes visible.
ARG VITE_GIT_SHA
ENV VITE_GIT_SHA=${VITE_GIT_SHA}

ARG VITE_BUILD_TIME
ENV VITE_BUILD_TIME=${VITE_BUILD_TIME}
```

Order matters: these must come **before** `RUN npm run build`, and the existing
`RUN rm -f .env .env.local .env.development` must stay before the build so args win over local
dev defaults.

- [ ] **Step 4: Verify the frontend build picks the args up**

```bash
docker build -f Dockerfile.frontend \
  --build-arg VITE_GIT_SHA=deadbeefcafe \
  --build-arg VITE_BUILD_TIME=2026-08-27T10:00:00Z \
  --build-arg VITE_API_BASE_URL=https://api.simonrowe.dev \
  -t status-page-arg-check . \
  && docker run --rm status-page-arg-check sh -c 'grep -rl deadbeefcafe /usr/share/nginx/html || echo "SHA NOT IN BUNDLE"'
```

Expected: a path under `/usr/share/nginx/html/assets/` is printed. `SHA NOT IN BUNDLE` means
Task 12's `config/version.ts` does not exist yet — that is expected at this point in the plan;
re-run this check after Task 12 and require a path.

- [ ] **Step 5: Verify the backend bakes a multi-commit history the way CI will**

Simulate CI's checkout depth locally to prove the fix is needed and works:

```bash
# Shallow: proves the failure mode.
git clone --depth 1 file://$(pwd) /tmp/shallow-check 2>/dev/null
git -C /tmp/shallow-check log --oneline | wc -l   # expect 1
# Full: what fetch-depth: 0 gives CI.
git -C /tmp/shallow-check fetch --unshallow 2>/dev/null || true
git -C /tmp/shallow-check log --oneline | wc -l   # expect > 1
rm -rf /tmp/shallow-check
```

Expected: 1, then a number greater than 1. This is the exact difference `fetch-depth: 0` makes.

- [ ] **Step 6: Validate the workflow YAML**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/publish.yml')); print('valid')"
```

Expected: `valid`.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/publish.yml Dockerfile.frontend
git commit -m "ci: bake commit metadata into the published images"
```

---

### Task 12: Frontend types, API client and hooks

**Files:**
- Create: `frontend/src/types/platform.ts`
- Create: `frontend/src/config/version.ts`
- Create: `frontend/src/services/platformApi.ts`
- Create: `frontend/src/hooks/usePlatformStatus.ts`
- Create: `frontend/src/hooks/useReleases.ts`
- Modify: `frontend/src/vite-env.d.ts`
- Test: `frontend/tests/platformApi.test.ts`

**Interfaces:**
- Consumes: the JSON shapes from Task 9 — `PlatformStatusResponse` and `ReleaseResponse`.
- Produces: `ServiceVersion`, `PlatformComponent`, `PlatformStatus`, `Release`, `ReleaseSummaryStatus` types; `FRONTEND_COMMIT`, `FRONTEND_SHORT_COMMIT`, `FRONTEND_BUILD_TIME`, `frontendServiceVersion()`; `fetchPlatformStatus()`, `fetchReleases(limit?)`; `usePlatformStatus()` returning `{status, loading, error, retry}` and `useReleases(limit?)` returning `{releases, loading, error, retry}`. Task 13 and 14 consume all of these.

- [ ] **Step 1: Declare the new env vars**

Replace `frontend/src/vite-env.d.ts` with:

```typescript
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_RECAPTCHA_SITE_KEY?: string
  readonly VITE_GA_MEASUREMENT_ID?: string
  // Baked in by Dockerfile.frontend from the Publish workflow's github.sha. Absent in
  // local dev, which is why every consumer treats absence as "dev build" rather than
  // an error.
  readonly VITE_GIT_SHA?: string
  readonly VITE_BUILD_TIME?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
```

- [ ] **Step 2: Write the failing test**

`frontend/tests/platformApi.test.ts`:

```typescript
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchPlatformStatus, fetchReleases } from '../src/services/platformApi'
import type { PlatformStatus, Release } from '../src/types/platform'

const STATUS: PlatformStatus = {
  services: [
    {
      name: 'backend',
      commit: '840c311abcdef0123456789abcdef0123456789a',
      shortCommit: '840c311',
      commitSubject: 'docs: overhaul the README',
      commitTime: '2026-08-26T14:02:11Z',
      startedAt: '2026-08-24T09:15:03Z',
      reachable: true,
    },
  ],
  components: [{ name: 'mongodb', image: 'mongo', tag: '8', floating: false }],
}

const RELEASES: Release[] = [
  {
    sha: '840c311abcdef0123456789abcdef0123456789a',
    shortSha: '840c311',
    type: 'docs',
    subject: 'docs: overhaul the README (#118)',
    commitTime: '2026-08-26T14:02:11Z',
    running: true,
    summary: 'The README was rewritten.',
    summaryStatus: 'READY',
  },
]

describe('platformApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function respondWith(body: unknown, ok = true, statusCode = 200) {
    vi.mocked(fetch).mockResolvedValue({
      ok,
      status: statusCode,
      json: async () => body,
    } as Response)
  }

  it('fetches the platform status', async () => {
    respondWith(STATUS)

    await expect(fetchPlatformStatus()).resolves.toEqual(STATUS)
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/platform/status'))
  })

  it('fetches releases with the default limit of 20', async () => {
    respondWith(RELEASES)

    await expect(fetchReleases()).resolves.toEqual(RELEASES)
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('limit=20'))
  })

  it('fetches releases with an explicit limit', async () => {
    respondWith(RELEASES)

    await fetchReleases(5)

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('limit=5'))
  })

  it('throws a readable error on a failed status response', async () => {
    respondWith(null, false, 503)

    await expect(fetchPlatformStatus()).rejects.toThrow(/status/i)
  })

  it('throws a readable error on a failed releases response', async () => {
    respondWith(null, false, 500)

    await expect(fetchReleases()).rejects.toThrow(/releases/i)
  })
})
```

- [ ] **Step 3: Run the test to verify it fails**

Run from `frontend/`: `npm test -- platformApi`
Expected: FAIL — cannot resolve `../src/services/platformApi`.

- [ ] **Step 4: Create the types**

`frontend/src/types/platform.ts`:

```typescript
/**
 * Mirrors the backend's `ReleaseSummaryStatus` enum. `PENDING` and `GENERATING` both mean
 * "no paragraph yet"; `FAILED` means there never will be one for this release.
 */
export type ReleaseSummaryStatus = 'PENDING' | 'GENERATING' | 'READY' | 'FAILED'

/**
 * One first-party service's version. Every field but `name` and `reachable` may be absent:
 * a service built outside a git checkout has no commit, and an unreachable one reports
 * nothing at all.
 */
export interface ServiceVersion {
  name: string
  commit: string
  shortCommit: string
  commitSubject: string | null
  commitTime: string | null
  startedAt: string | null
  reachable: boolean
}

/** One third-party image the compose file declares. */
export interface PlatformComponent {
  name: string
  image: string
  tag: string
  /** True when the tag does not pin a version, so the running digest is unknown. */
  floating: boolean
}

export interface PlatformStatus {
  services: ServiceVersion[]
  components: PlatformComponent[]
}

/** One changelog entry. `summary` is null until the sweep has written it. */
export interface Release {
  sha: string
  shortSha: string
  type: string
  subject: string
  commitTime: string
  running: boolean
  summary: string | null
  summaryStatus: ReleaseSummaryStatus
}
```

- [ ] **Step 5: Create the version config**

`frontend/src/config/version.ts`:

```typescript
import type { ServiceVersion } from '../types/platform'

/**
 * This bundle's own commit, baked in by Dockerfile.frontend at build time.
 *
 * The frontend reports its own version rather than letting the backend assert one for it:
 * the backend cannot know which bundle a browser loaded, and a guess would be wrong exactly
 * when it mattered — during a partial deploy, which is the case /status exists to surface.
 *
 * Absent in local development, where the page shows a dev build rather than an error.
 */
export const FRONTEND_COMMIT = import.meta.env.VITE_GIT_SHA ?? 'unknown'

export const FRONTEND_SHORT_COMMIT =
  FRONTEND_COMMIT === 'unknown' ? 'dev' : FRONTEND_COMMIT.slice(0, 7)

export const FRONTEND_BUILD_TIME = import.meta.env.VITE_BUILD_TIME ?? null

/**
 * This bundle as a `ServiceVersion`, so it can sit in the same list as the backend-reported
 * services without the page special-casing it.
 *
 * `startedAt` is null by design: a static bundle served by nginx has no process start time,
 * and inventing the page-load time would be a different fact wearing the same label.
 */
export function frontendServiceVersion(): ServiceVersion {
  return {
    name: 'frontend',
    commit: FRONTEND_COMMIT,
    shortCommit: FRONTEND_SHORT_COMMIT,
    commitSubject: null,
    commitTime: FRONTEND_BUILD_TIME,
    startedAt: null,
    reachable: true,
  }
}
```

- [ ] **Step 6: Create the API client**

`frontend/src/services/platformApi.ts`:

```typescript
import { API_BASE_URL } from '../config/api'
import type { PlatformStatus, Release } from '../types/platform'

const DEFAULT_LIMIT = 20

/**
 * What is running in production right now.
 *
 * @throws Error with a readable message when the request fails, so the page can show it
 */
export async function fetchPlatformStatus(): Promise<PlatformStatus> {
  const response = await fetch(`${API_BASE_URL}/api/platform/status`)
  if (!response.ok) {
    throw new Error(`Unable to load platform status (${response.status}).`)
  }
  return (await response.json()) as PlatformStatus
}

/**
 * Recent releases, newest first.
 *
 * @param limit how many to request; the backend clamps this to 100
 */
export async function fetchReleases(limit: number = DEFAULT_LIMIT): Promise<Release[]> {
  const response = await fetch(`${API_BASE_URL}/api/platform/releases?limit=${limit}`)
  if (!response.ok) {
    throw new Error(`Unable to load releases (${response.status}).`)
  }
  return (await response.json()) as Release[]
}
```

- [ ] **Step 7: Create the two hooks**

`frontend/src/hooks/usePlatformStatus.ts` — follows `useProfile`'s cancelled-flag and
retry-counter shape exactly:

```typescript
import { useCallback, useEffect, useState } from 'react'

import { fetchPlatformStatus } from '../services/platformApi'
import type { PlatformStatus } from '../types/platform'

interface UsePlatformStatusResult {
  status: PlatformStatus | null
  loading: boolean
  error: string | null
  retry: () => void
}

export function usePlatformStatus(): UsePlatformStatusResult {
  const [status, setStatus] = useState<PlatformStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const fetched = await fetchPlatformStatus()
        if (!cancelled) setStatus(fetched)
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load platform status.')
          setStatus(null)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return { status, loading, error, retry }
}
```

`frontend/src/hooks/useReleases.ts`:

```typescript
import { useCallback, useEffect, useState } from 'react'

import { fetchReleases } from '../services/platformApi'
import type { Release } from '../types/platform'

interface UseReleasesResult {
  releases: Release[]
  loading: boolean
  error: string | null
  retry: () => void
}

export function useReleases(limit?: number): UseReleasesResult {
  const [releases, setReleases] = useState<Release[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const fetched = await fetchReleases(limit)
        if (!cancelled) setReleases(fetched)
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load releases.')
          setReleases([])
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [attempt, limit])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return { releases, loading, error, retry }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run from `frontend/`: `npm test -- platformApi`
Expected: PASS, 5 tests.

- [ ] **Step 9: Run lint and typecheck**

Run from `frontend/`: `npm run lint && npx tsc --noEmit`
Expected: 0 errors. (`npm run lint` exits 0 today with 5 pre-existing `react-refresh` warnings —
do not add new ones.)

- [ ] **Step 10: Commit**

```bash
git add frontend/src/types/platform.ts frontend/src/config/version.ts frontend/src/services/platformApi.ts frontend/src/hooks/usePlatformStatus.ts frontend/src/hooks/useReleases.ts frontend/src/vite-env.d.ts frontend/tests/platformApi.test.ts
git commit -m "feat: add the platform status API client and hooks"
```

---

### Task 13: The footer version badge

**Files:**
- Create: `frontend/src/components/layout/VersionBadge.tsx`
- Modify: `frontend/src/components/layout/Footer.tsx` (add the badge to the single footer bar)
- Modify: `frontend/src/styles.css` (append the `.version-badge` block)
- Test: `frontend/tests/VersionBadge.test.tsx`

**Interfaces:**
- Consumes: `FRONTEND_SHORT_COMMIT`, `FRONTEND_BUILD_TIME` from `config/version.ts` (Task 12).
- Produces: `<VersionBadge />` — no props. Rendered inside `Footer`, so it appears on every public page.

- [ ] **Step 1: Write the failing test**

`frontend/tests/VersionBadge.test.tsx`:

```typescript
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

describe('VersionBadge', () => {
  async function renderBadge(sha?: string, buildTime?: string) {
    vi.resetModules()
    vi.doMock('../src/config/version', () => ({
      FRONTEND_COMMIT: sha ?? 'unknown',
      FRONTEND_SHORT_COMMIT: sha ? sha.slice(0, 7) : 'dev',
      FRONTEND_BUILD_TIME: buildTime ?? null,
    }))
    const { VersionBadge } = await import('../src/components/layout/VersionBadge')
    render(
      <MemoryRouter>
        <VersionBadge />
      </MemoryRouter>,
    )
  }

  it('renders the bundle short SHA', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByText('840c311')).toBeInTheDocument()
  })

  it('links to the status page', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAttribute('href', '/status')
  })

  it('renders a dev build when no SHA was baked in', async () => {
    await renderBadge(undefined)

    expect(screen.getByText('dev')).toBeInTheDocument()
  })

  it('carries an accessible name explaining what the SHA is', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link')).toHaveAccessibleName(/version/i)
  })

  it('puts the build time in the title when it is known', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a', '2026-08-26T14:02:11Z')

    expect(screen.getByRole('link').getAttribute('title')).toMatch(/2026/)
  })

  it('omits the build time from the title when it is unknown', async () => {
    await renderBadge('840c311abcdef0123456789abcdef0123456789a')

    expect(screen.getByRole('link').getAttribute('title')).not.toMatch(/\d{4}/)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `frontend/`: `npm test -- VersionBadge`
Expected: FAIL — cannot resolve `../src/components/layout/VersionBadge`.

- [ ] **Step 3: Create `VersionBadge`**

`frontend/src/components/layout/VersionBadge.tsx`:

```typescript
import { Link } from 'react-router-dom'

import { FRONTEND_BUILD_TIME, FRONTEND_SHORT_COMMIT } from '../../config/version'

/**
 * The running frontend's commit, linking to /status.
 *
 * Rendered from the bundle's own baked SHA rather than from the API on purpose: this sits in
 * the footer of every page, so a fetch here would put a request on every single page view to
 * report a value the bundle already knows.
 *
 * It also makes /status discoverable without spending a seventh TopNav slot, which is the
 * change that would have hurt the mobile nav.
 */
export function VersionBadge() {
  const buildTime = FRONTEND_BUILD_TIME ? new Date(FRONTEND_BUILD_TIME) : null
  const validBuildTime = buildTime && !Number.isNaN(buildTime.getTime()) ? buildTime : null
  const title = validBuildTime
    ? `Version ${FRONTEND_SHORT_COMMIT}, built ${validBuildTime.toLocaleString()}`
    : `Version ${FRONTEND_SHORT_COMMIT}`

  return (
    <Link aria-label={title} className="version-badge" title={title} to="/status">
      <span className="version-badge__label">v</span>
      <span className="version-badge__sha">{FRONTEND_SHORT_COMMIT}</span>
    </Link>
  )
}
```

- [ ] **Step 4: Add the badge to the footer**

In `frontend/src/components/layout/Footer.tsx`, add the import:

```typescript
import { VersionBadge } from './VersionBadge'
```

and place the badge immediately after the copyright paragraph, inside `.footer__bar`:

```tsx
        <p className="footer__copyright">
          &copy; {new Date().getFullYear()} {name}
        </p>

        <VersionBadge />
```

- [ ] **Step 5: Add the styles**

Append to `frontend/src/styles.css`:

```css
/* ---------------------------------------------------------------------------
   Version badge — the footer's link to /status.
   Sits in the single .footer__bar flex row, so it must stay small enough not
   to force that bar to wrap on a narrow phone.
   --------------------------------------------------------------------------- */
.version-badge {
  display: inline-flex;
  align-items: baseline;
  gap: 0.15rem;
  padding: 0.15rem 0.45rem;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.7rem;
  line-height: 1.4;
  color: var(--color-text-muted);
  text-decoration: none;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.version-badge:hover,
.version-badge:focus-visible {
  color: var(--color-text);
  border-color: var(--color-text-muted);
}

.version-badge__label {
  opacity: 0.6;
}

.version-badge__sha {
  letter-spacing: 0.02em;
}
```

Before committing, confirm every custom property used above exists:
`grep -n "\-\-color-border\|--color-text-muted\|--font-mono" frontend/src/styles.css | head`.
Substitute the project's actual names for any that do not exist — do not introduce new
custom properties for this one component.

- [ ] **Step 6: Run the test to verify it passes**

Run from `frontend/`: `npm test -- VersionBadge`
Expected: PASS, 6 tests.

- [ ] **Step 7: Run the whole frontend suite, lint and typecheck**

Run from `frontend/`: `npm test && npm run lint && npx tsc --noEmit`
Expected: all tests pass (the existing 67 plus the new ones), 0 lint errors, 0 type errors.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/layout/VersionBadge.tsx frontend/src/components/layout/Footer.tsx frontend/src/styles.css frontend/tests/VersionBadge.test.tsx
git commit -m "feat: show the running frontend version in the footer"
```

---

### Task 14: The status page

**Files:**
- Create: `frontend/src/components/status/ServiceVersionCard.tsx`
- Create: `frontend/src/components/status/DriftWarning.tsx`
- Create: `frontend/src/components/status/ComponentTable.tsx`
- Create: `frontend/src/components/status/ReleaseEntry.tsx`
- Create: `frontend/src/components/status/ReleaseList.tsx`
- Create: `frontend/src/pages/StatusPage.tsx`
- Modify: `frontend/src/App.tsx` (lazy import + route)
- Modify: `frontend/src/styles.css` (append the `.status-page` block)
- Test: `frontend/tests/StatusPage.test.tsx`

**Interfaces:**
- Consumes: `usePlatformStatus`, `useReleases`, `frontendServiceVersion`, all types from Task 12.
- Produces: `StatusPage` as a **named export** (`App.tsx`'s `named()` helper requires it), route `/status`.

Component props, fixed here so the subcomponents and the test agree:

```typescript
// ServiceVersionCard
{ version: ServiceVersion }
// DriftWarning — renders null when there is no drift
{ services: ServiceVersion[] }
// ComponentTable
{ components: PlatformComponent[] }
// ReleaseEntry
{ release: Release }
// ReleaseList
{ releases: Release[] }
```

- [ ] **Step 1: Write the failing test**

`frontend/tests/StatusPage.test.tsx`:

```typescript
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { StatusPage } from '../src/pages/StatusPage'
import type { PlatformStatus, Release, ServiceVersion } from '../src/types/platform'

vi.mock('../src/services/analytics', () => ({ trackPageView: vi.fn() }))

vi.mock('../src/config/version', () => ({
  FRONTEND_COMMIT: '840c311abcdef0123456789abcdef0123456789a',
  FRONTEND_SHORT_COMMIT: '840c311',
  FRONTEND_BUILD_TIME: '2026-08-26T14:02:11Z',
  frontendServiceVersion: () => ({
    name: 'frontend',
    commit: '840c311abcdef0123456789abcdef0123456789a',
    shortCommit: '840c311',
    commitSubject: null,
    commitTime: '2026-08-26T14:02:11Z',
    startedAt: null,
    reachable: true,
  }),
}))

const { mockStatus, mockReleases } = vi.hoisted(() => ({
  mockStatus: vi.fn(),
  mockReleases: vi.fn(),
}))

vi.mock('../src/hooks/usePlatformStatus', () => ({ usePlatformStatus: mockStatus }))
vi.mock('../src/hooks/useReleases', () => ({ useReleases: mockReleases }))

const BACKEND: ServiceVersion = {
  name: 'backend',
  commit: '840c311abcdef0123456789abcdef0123456789a',
  shortCommit: '840c311',
  commitSubject: 'docs: overhaul the README',
  commitTime: '2026-08-26T14:02:11Z',
  startedAt: '2026-08-24T09:15:03Z',
  reachable: true,
}

const STATUS: PlatformStatus = {
  services: [
    BACKEND,
    { ...BACKEND, name: 'software-factory' },
    {
      name: 'deployer',
      commit: 'unknown',
      shortCommit: 'dev',
      commitSubject: null,
      commitTime: null,
      startedAt: null,
      reachable: false,
    },
  ],
  components: [
    { name: 'mongodb', image: 'mongo', tag: '8', floating: false },
    { name: 'alloy', image: 'grafana/alloy', tag: 'latest', floating: true },
  ],
}

const RELEASES: Release[] = [
  {
    sha: '840c311abcdef0123456789abcdef0123456789a',
    shortSha: '840c311',
    type: 'docs',
    subject: 'docs: overhaul the README (#118)',
    commitTime: '2026-08-26T14:02:11Z',
    running: true,
    summary: 'The README was rewritten to explain the architecture.',
    summaryStatus: 'READY',
  },
  {
    sha: '39e0f7aabcdef0123456789abcdef0123456789a',
    shortSha: '39e0f7a',
    type: 'feat',
    subject: 'feat: deploy automatically on merge to main (#116)',
    commitTime: '2026-08-25T10:00:00Z',
    running: false,
    summary: null,
    summaryStatus: 'PENDING',
  },
]

function renderPage() {
  render(
    <MemoryRouter>
      <StatusPage />
    </MemoryRouter>,
  )
}

describe('StatusPage', () => {
  beforeEach(() => {
    mockStatus.mockReturnValue({ status: STATUS, loading: false, error: null, retry: vi.fn() })
    mockReleases.mockReturnValue({
      releases: RELEASES,
      loading: false,
      error: null,
      retry: vi.fn(),
    })
  })

  it('renders a card for every reported service plus the frontend', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('backend')).toBeInTheDocument()
    })
    expect(screen.getByText('frontend')).toBeInTheDocument()
    expect(screen.getByText('software-factory')).toBeInTheDocument()
    expect(screen.getByText('deployer')).toBeInTheDocument()
  })

  it('shows an unreachable service as not reporting rather than hiding it', () => {
    renderPage()

    expect(screen.getByText(/not reporting/i)).toBeInTheDocument()
  })

  it('warns when the frontend and backend SHAs differ', () => {
    mockStatus.mockReturnValue({
      status: {
        ...STATUS,
        services: [{ ...BACKEND, commit: 'aaaaaaabbbbbb', shortCommit: 'aaaaaaa' }],
      },
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent(/different/i)
  })

  it('does not warn when every first-party SHA matches', () => {
    renderPage()

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('lists third-party components with their tags', () => {
    renderPage()

    // Scoped to the component row rather than a bare getByText('8'): a formatted date
    // elsewhere on the page can contain "8" and getByText throws on multiple matches.
    const row = screen.getByText('mongodb').closest('.component-table__row')
    expect(row).not.toBeNull()
    expect(row).toHaveTextContent('mongo')
    expect(row).toHaveTextContent('8')
  })

  it('labels a floating tag rather than presenting it as a version', () => {
    renderPage()

    expect(screen.getByText(/floating/i)).toBeInTheDocument()
  })

  it('renders the AI release note for a ready release', () => {
    renderPage()

    expect(
      screen.getByText('The README was rewritten to explain the architecture.'),
    ).toBeInTheDocument()
  })

  it('renders a pending release from its subject with a pending note', () => {
    renderPage()

    expect(
      screen.getByText('feat: deploy automatically on merge to main (#116)'),
    ).toBeInTheDocument()
    expect(screen.getByText(/summary pending/i)).toBeInTheDocument()
  })

  it('badges the running release', () => {
    renderPage()

    expect(screen.getByText(/running now/i)).toBeInTheDocument()
  })

  it('links each release to its GitHub commit', () => {
    renderPage()

    // Several links share the short SHA — the backend card, the frontend card and this
    // release entry all render 840c311 — so scope the query to the release list rather
    // than using getByRole, which throws on multiple matches.
    const releaseLink = screen
      .getByText('docs: overhaul the README (#118)')
      .closest('.release')
      ?.querySelector('.release__sha')

    expect(releaseLink).toHaveAttribute(
      'href',
      'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/840c311abcdef0123456789abcdef0123456789a',
    )
  })

  it('says history is published rather than implying it was deployed', () => {
    renderPage()

    expect(screen.getByText(/published/i)).toBeInTheDocument()
  })

  it('shows an empty-history message rather than a blank section', () => {
    mockReleases.mockReturnValue({ releases: [], loading: false, error: null, retry: vi.fn() })

    renderPage()

    expect(screen.getByText(/no release history yet/i)).toBeInTheDocument()
  })

  it('shows an error with a retry when the status request fails', () => {
    mockStatus.mockReturnValue({
      status: null,
      loading: false,
      error: 'Unable to load platform status (503).',
      retry: vi.fn(),
    })

    renderPage()

    expect(screen.getByText(/unable to load platform status/i)).toBeInTheDocument()
  })

  it('shows a loading indicator while the status is in flight', () => {
    mockStatus.mockReturnValue({ status: null, loading: true, error: null, retry: vi.fn() })

    renderPage()

    // LoadingIndicator's exact markup decides this assertion. Read
    // frontend/src/components/common/LoadingIndicator.tsx and match how an existing test
    // asserts on it (grep the tests directory for LoadingIndicator) rather than assuming
    // it renders the word "loading".
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `frontend/`: `npm test -- StatusPage`
Expected: FAIL — cannot resolve `../src/pages/StatusPage`.

- [ ] **Step 3: Create `ServiceVersionCard`**

`frontend/src/components/status/ServiceVersionCard.tsx`:

```typescript
import type { ServiceVersion } from '../../types/platform'

const COMMIT_URL = 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/'

interface ServiceVersionCardProps {
  version: ServiceVersion
}

function formatDate(value: string | null): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString()
}

function formatUptime(startedAt: string | null): string | null {
  if (!startedAt) return null
  const started = new Date(startedAt)
  if (Number.isNaN(started.getTime())) return null
  const hours = Math.floor((Date.now() - started.getTime()) / 3_600_000)
  if (hours < 1) return 'less than an hour'
  if (hours < 48) return `${hours} hour${hours === 1 ? '' : 's'}`
  return `${Math.floor(hours / 24)} days`
}

/** One first-party service's version facts. Absent facts are omitted, never faked. */
export function ServiceVersionCard({ version }: ServiceVersionCardProps) {
  const commitTime = formatDate(version.commitTime)
  const startedAt = formatDate(version.startedAt)
  const uptime = formatUptime(version.startedAt)

  return (
    <article className="service-card">
      <header className="service-card__header">
        <h3 className="service-card__name">{version.name}</h3>
        {version.reachable ? (
          <a
            className="service-card__sha"
            href={`${COMMIT_URL}${version.commit}`}
            rel="noopener noreferrer"
            target="_blank"
          >
            {version.shortCommit}
          </a>
        ) : (
          <span className="service-card__sha service-card__sha--unknown">not reporting</span>
        )}
      </header>

      {version.commitSubject ? (
        <p className="service-card__subject">{version.commitSubject}</p>
      ) : null}

      <dl className="service-card__facts">
        {commitTime ? (
          <>
            <dt>Committed</dt>
            <dd>{commitTime}</dd>
          </>
        ) : null}
        {startedAt ? (
          <>
            <dt>Started</dt>
            <dd>
              {startedAt}
              {uptime ? ` (${uptime} ago)` : ''}
            </dd>
          </>
        ) : null}
      </dl>
    </article>
  )
}
```

- [ ] **Step 4: Create `DriftWarning`**

`frontend/src/components/status/DriftWarning.tsx`:

```typescript
import type { ServiceVersion } from '../../types/platform'

interface DriftWarningProps {
  services: ServiceVersion[]
}

/**
 * Warns when the first-party services are not all on the same commit.
 *
 * This is the most valuable single thing the page can say. A partial deploy — frontend
 * updated, backend not, or `deployer` left behind because it excludes itself from its own
 * recreate list — is a real and recurring state here, and once went unnoticed for months.
 *
 * Services that are not reporting are excluded rather than counted as drift: "unknown" is
 * not evidence of a mismatch.
 */
export function DriftWarning({ services }: DriftWarningProps) {
  const known = services.filter((s) => s.reachable && s.commit !== 'unknown')
  const commits = new Set(known.map((s) => s.commit))

  if (commits.size <= 1) {
    return null
  }

  return (
    <p className="status-page__drift" role="alert">
      These services are running <strong>different commits</strong>:{' '}
      {known.map((s) => `${s.name} (${s.shortCommit})`).join(', ')}. That usually means a
      partial deploy.
    </p>
  )
}
```

- [ ] **Step 5: Create `ComponentTable`**

`frontend/src/components/status/ComponentTable.tsx`:

```typescript
import type { PlatformComponent } from '../../types/platform'

interface ComponentTableProps {
  components: PlatformComponent[]
}

/**
 * The third-party images production declares.
 *
 * A definition list rather than a `<table>`: it is two columns of key/value pairs, it needs
 * to stack rather than scroll on a phone, and a semantic table would buy nothing here.
 */
export function ComponentTable({ components }: ComponentTableProps) {
  if (components.length === 0) {
    return <p className="status-page__empty">No component information available.</p>
  }

  return (
    <dl className="component-table">
      {components.map((component) => (
        <div className="component-table__row" key={component.name}>
          <dt className="component-table__name">{component.name}</dt>
          <dd className="component-table__version">
            <span className="component-table__image">{component.image}</span>
            {component.floating ? (
              <span className="component-table__tag component-table__tag--floating">
                {component.tag} — floating tag
              </span>
            ) : (
              <span className="component-table__tag">{component.tag}</span>
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}
```

- [ ] **Step 6: Create `ReleaseEntry` and `ReleaseList`**

`frontend/src/components/status/ReleaseEntry.tsx`:

```typescript
import type { Release } from '../../types/platform'

const COMMIT_URL = 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/commit/'

interface ReleaseEntryProps {
  release: Release
}

/** One changelog entry. Renders from the commit subject when no summary exists yet. */
export function ReleaseEntry({ release }: ReleaseEntryProps) {
  const date = new Date(release.commitTime)
  const formatted = Number.isNaN(date.getTime()) ? null : date.toLocaleDateString()

  return (
    <li className={`release${release.running ? ' release--running' : ''}`}>
      <header className="release__header">
        <span className={`release__type release__type--${release.type}`}>{release.type}</span>
        <a
          className="release__sha"
          href={`${COMMIT_URL}${release.sha}`}
          rel="noopener noreferrer"
          target="_blank"
        >
          {release.shortSha}
        </a>
        {formatted ? <time className="release__date">{formatted}</time> : null}
        {release.running ? <span className="release__badge">Running now</span> : null}
      </header>

      <h3 className="release__subject">{release.subject}</h3>

      {release.summaryStatus === 'READY' && release.summary ? (
        <p className="release__summary">{release.summary}</p>
      ) : release.summaryStatus === 'FAILED' ? null : (
        <p className="release__summary release__summary--pending">Summary pending.</p>
      )}
    </li>
  )
}
```

`frontend/src/components/status/ReleaseList.tsx`:

```typescript
import type { Release } from '../../types/platform'

import { ReleaseEntry } from './ReleaseEntry'

interface ReleaseListProps {
  releases: Release[]
}

export function ReleaseList({ releases }: ReleaseListProps) {
  if (releases.length === 0) {
    return <p className="status-page__empty">No release history yet.</p>
  }

  return (
    <ol className="release-list">
      {releases.map((release) => (
        <ReleaseEntry key={release.sha} release={release} />
      ))}
    </ol>
  )
}
```

- [ ] **Step 7: Create `StatusPage`**

`frontend/src/pages/StatusPage.tsx`:

```typescript
import { useEffect } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ComponentTable } from '../components/status/ComponentTable'
import { DriftWarning } from '../components/status/DriftWarning'
import { ReleaseList } from '../components/status/ReleaseList'
import { ServiceVersionCard } from '../components/status/ServiceVersionCard'
import { frontendServiceVersion } from '../config/version'
import { usePageTitle } from '../hooks/usePageTitle'
import { usePlatformStatus } from '../hooks/usePlatformStatus'
import { useReleases } from '../hooks/useReleases'
import { trackPageView } from '../services/analytics'

/**
 * What is running in production, and what shipped recently.
 *
 * The frontend's own entry comes from the bundle rather than from the API — the backend
 * cannot know which bundle a browser loaded.
 */
export function StatusPage() {
  const { status, loading, error, retry } = usePlatformStatus()
  const {
    releases,
    loading: releasesLoading,
    error: releasesError,
    retry: retryReleases,
  } = useReleases()

  usePageTitle('Platform Status')

  useEffect(() => {
    trackPageView('/status')
  }, [])

  // The backend reports itself first; the frontend inserts itself second so the two
  // versions that most often drift sit next to each other.
  const services = status
    ? [status.services[0], frontendServiceVersion(), ...status.services.slice(1)].filter(Boolean)
    : [frontendServiceVersion()]

  return (
    <div className="status-page">
      <header className="status-page__header">
        <h1 className="status-page__title">Platform Status</h1>
        <p className="status-page__intro">
          What is running in production right now, and what shipped recently. Versions are the
          commit each service was built from — there are no release tags, the SHA is the version.
        </p>
      </header>

      <section className="status-page__section">
        <h2 className="status-page__section-title">Running now</h2>
        {loading ? <LoadingIndicator /> : null}
        {error ? <ErrorMessage message={error} onRetry={retry} /> : null}
        <DriftWarning services={services} />
        <div className="status-page__services">
          {services.map((service) => (
            <ServiceVersionCard key={service.name} version={service} />
          ))}
        </div>
      </section>

      <section className="status-page__section">
        <h2 className="status-page__section-title">Platform components</h2>
        <p className="status-page__note">
          The third-party images the production compose file declares. Pinned tags are what is
          running; a floating tag means the running digest is not pinned and cannot be reported.
        </p>
        <ComponentTable components={status?.components ?? []} />
      </section>

      <section className="status-page__section">
        <h2 className="status-page__section-title">Recent releases</h2>
        <p className="status-page__note">
          Every merge to <code>main</code> publishes an image, so one commit is one release.
          Entries other than the one running now record what was <strong>published</strong>,
          not what was deployed — deploys are manual, so there is no deployment history to
          report. Summaries are written by a model when a release is first seen.
        </p>
        {releasesLoading ? <LoadingIndicator /> : null}
        {releasesError ? (
          <ErrorMessage message={releasesError} onRetry={retryReleases} />
        ) : null}
        <ReleaseList releases={releases} />
      </section>
    </div>
  )
}
```

Before running the test, confirm `ErrorMessage` and `LoadingIndicator`'s actual props:
`grep -n "interface\|Props" frontend/src/components/common/ErrorMessage.tsx frontend/src/components/common/LoadingIndicator.tsx`
and adjust the two call sites to match. `McpPage.tsx` uses both — copy its usage exactly.

- [ ] **Step 8: Add the route**

In `frontend/src/App.tsx`, add the lazy import alongside the other public pages:

```typescript
const StatusPage = named(() => import('./pages/StatusPage'), 'StatusPage')
```

and the route alongside the other public routes (find `path="/mcp"` and add after it):

```tsx
        <Route path="/status" element={<PublicLayout><StatusPage /></PublicLayout>} />
```

Match the exact wrapper pattern the neighbouring `/mcp` route uses — read the surrounding
lines first rather than assuming, since `PublicLayout` wraps each route individually here.

- [ ] **Step 9: Add the styles**

Append to `frontend/src/styles.css`:

```css
/* ---------------------------------------------------------------------------
   Platform status page (/status).
   Mobile: the service grid collapses to one column and the component list
   stacks its key/value pairs rather than scrolling sideways.
   --------------------------------------------------------------------------- */
.status-page {
  max-width: 60rem;
  margin: 0 auto;
  padding: 2rem 1.25rem 4rem;
}

.status-page__title {
  margin: 0 0 0.5rem;
}

.status-page__intro,
.status-page__note {
  color: var(--color-text-muted);
  max-width: 46rem;
  line-height: 1.6;
}

.status-page__note {
  font-size: 0.9rem;
}

.status-page__section {
  margin-top: 3rem;
}

.status-page__section-title {
  font-size: 1.1rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  padding-bottom: 0.5rem;
  margin-bottom: 1rem;
}

.status-page__empty {
  color: var(--color-text-muted);
  font-style: italic;
}

.status-page__drift {
  border-left: 3px solid var(--color-accent, #d97706);
  background: color-mix(in srgb, var(--color-accent, #d97706) 8%, transparent);
  padding: 0.75rem 1rem;
  border-radius: 0 4px 4px 0;
  line-height: 1.5;
}

.status-page__services {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 1rem;
}

.service-card {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 1rem;
}

.service-card__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}

.service-card__name {
  margin: 0;
  font-size: 1rem;
}

.service-card__sha {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.service-card__sha--unknown {
  font-style: italic;
}

.service-card__subject {
  margin: 0.5rem 0 0;
  font-size: 0.85rem;
  color: var(--color-text-muted);
}

.service-card__facts {
  margin: 0.75rem 0 0;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.2rem 0.75rem;
  font-size: 0.8rem;
}

.service-card__facts dt {
  color: var(--color-text-muted);
}

.service-card__facts dd {
  margin: 0;
}

.component-table {
  margin: 0;
  display: grid;
  gap: 0.35rem;
}

.component-table__row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.4rem 0;
  border-bottom: 1px solid var(--color-border);
}

.component-table__name {
  font-weight: 600;
  font-size: 0.9rem;
}

.component-table__version {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.5rem;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.component-table__tag--floating {
  font-style: italic;
}

.release-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1.5rem;
}

.release {
  border-left: 2px solid var(--color-border);
  padding-left: 1rem;
}

.release--running {
  border-left-color: var(--color-accent, #d97706);
}

.release__header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.6rem;
  font-size: 0.8rem;
}

.release__type {
  font-family: var(--font-mono, ui-monospace, monospace);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  border: 1px solid var(--color-border);
  border-radius: 3px;
  color: var(--color-text-muted);
}

.release__sha {
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-muted);
}

.release__date,
.release__badge {
  color: var(--color-text-muted);
}

.release__badge {
  font-weight: 600;
  color: var(--color-accent, #d97706);
}

.release__subject {
  margin: 0.4rem 0 0.4rem;
  font-size: 1rem;
  line-height: 1.4;
}

.release__summary {
  margin: 0;
  line-height: 1.65;
  color: var(--color-text-muted);
}

.release__summary--pending {
  font-style: italic;
  opacity: 0.7;
}

@media (max-width: 40rem) {
  .status-page {
    padding: 1.5rem 1rem 3rem;
  }

  .status-page__services {
    grid-template-columns: 1fr;
  }

  .component-table__row {
    flex-direction: column;
    gap: 0.15rem;
  }
}
```

Check the custom properties exist before committing:
`grep -c "\-\-color-accent" frontend/src/styles.css`. If it is 0, substitute the project's
actual accent variable — the fallbacks in the CSS above cover it, but matching the theme is
better than falling back.

- [ ] **Step 10: Run the test to verify it passes**

Run from `frontend/`: `npm test -- StatusPage`
Expected: PASS, 14 tests.

- [ ] **Step 11: Run the whole frontend suite, lint and typecheck**

Run from `frontend/`: `npm test && npm run lint && npx tsc --noEmit`
Expected: all tests pass, 0 lint errors, 0 type errors.

- [ ] **Step 12: See it in a real browser**

Start the local stack (`./scripts/start.sh` from the repo root, or the `local-env` skill) and
open `http://localhost:5173/status`. Verify, at 1280px and then at 375px width:

1. Four service cards, backend and frontend adjacent.
2. `software-factory` and `deployer` read "not reporting" locally (nothing is listening on 8090).
3. The component list shows pinned tags and marks the floating ones.
4. The changelog renders; entries show "Summary pending." until the sweep has run.
5. The footer badge appears on every page and links here.
6. At 375px nothing scrolls horizontally and the component rows stack.

- [ ] **Step 13: Commit**

```bash
git add frontend/src/components/status frontend/src/pages/StatusPage.tsx frontend/src/App.tsx frontend/src/styles.css frontend/tests/StatusPage.test.tsx
git commit -m "feat: add the public platform status page"
```

---

### Task 15: Document the page in a runbook

**Files:**
- Create: `docs/runbooks/platform-status.md`
- Modify: `CLAUDE.md` (add a bullet under "Recent Changes")

**Interfaces:** none — documentation only.

- [ ] **Step 1: Write the runbook**

`docs/runbooks/platform-status.md`:

```markdown
# Platform status page (`/status`)

Public page reporting which commit each first-party service is running, which third-party
image tags production declares, and a changelog of recent releases with AI-written notes.

## What it can and cannot evidence

- **"Running now" is evidenced.** Each service reports the commit baked into its own artifact.
  The frontend reports its SHA client-side from its bundle, so it cannot be wrong about which
  bundle you loaded.
- **The drift warning is the most useful thing on the page.** A partial deploy, or `deployer`
  left behind because it excludes itself from its own recreate list, shows up here.
- **"Platform components" states what the compose file declares**, not what Docker resolved.
  For pinned tags those match. Floating tags (`alloy`, `searxng`, `minio`, `FACTORY_IMAGE`)
  are labelled as such and no version is invented.
- **Changelog entries other than the running one record what was *published*, not deployed.**
  `deploy_runs` is empty because auto-deploy is off, so deployment history does not exist.

## How the data gets there

| Fact | Source |
|---|---|
| backend SHA / commit time | `springBoot { buildInfo }` in `backend/build.gradle.kts` |
| backend start time | captured in `RunningVersion`'s constructor |
| frontend SHA / build time | `VITE_GIT_SHA` / `VITE_BUILD_TIME` build args → the bundle |
| software-factory, deployer | their own `GET /api/version` on port **8090**, fetched by `FactoryVersionClient` |
| third-party tags | `ProdImageCatalog` parses the compose file shipped as a resource |
| changelog commits | `generateReleaseHistory` bakes `git log -n 50` into a resource |
| release notes | `ReleaseSummarySweep`, every 2 minutes, 3 per tick, Embabel `Ai` |

## Gotchas

- **`publish.yml` must keep `fetch-depth: 0`** on the image-building jobs. The default depth-1
  checkout makes `git log` return one commit, so the changelog ships with a single entry and
  looks like it worked. This is the single easiest way to silently break the page.
- **`buildInfo`'s `time` must stay pinned to the commit timestamp.** A wall-clock value
  invalidates `:backend:bootJar` in the Gradle build cache on every build.
- **`/api/platform/**` is deliberately not in `RateLimitInterceptor`.** The page issues two
  requests per view; metering it would 429 ordinary readers.
- **Do not add authentication to software-factory's `/api/version`.** It is unrouted by nginx
  and discloses only a public-repo SHA. Token-protecting it would mean giving the backend a
  token that also authorises `/api/reviews`.
- **Summaries are generated at ingest, never on view.** Nothing reachable from the two GET
  endpoints may call an LLM.
- **`platform_releases` is in the backup and restore lists** and must stay there — it holds
  paid-for LLM output, and older entries cannot be regenerated by a newer image because the
  history is baked per build.
- A restore drops collections, so `RestoreService` calls
  `V022CreatePlatformReleaseIndexes.createIndexes()` directly; Mongock will not re-run a
  change unit it has already recorded.

## Operations

Turn summary generation off:
`PLATFORM_RELEASE_SUMMARIES_ENABLED=false` in the deploy directory's `.env`, then recreate
the backend.

Regenerate every summary after a prompt change (there is no format-version invalidation,
because the document id is the commit SHA):

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
curl -s https://api.simonrowe.dev/api/platform/releases?limit=3 | jq '.[] | {shortSha, running, summaryStatus}'
```
```

- [ ] **Step 2: Add the CLAUDE.md entry**

Insert as the first bullet under `## Recent Changes` in `CLAUDE.md`:

```markdown
- 037-platform-status-page: A public `/status` page reports which commit each first-party
  service runs, the third-party image tags, and a changelog with AI-written release notes.
  Every version fact is **baked into the artifact at build time** (`springBoot { buildInfo }`
  with the commit SHA in `additional`, plus two generated resources) and self-reported — no
  Docker socket, so nothing new touches the one container that can mutate prod. Things that
  are load-bearing:
  - **`publish.yml`'s image jobs need `fetch-depth: 0`.** The default depth-1 checkout makes
    `git log` return ONE commit, so the changelog ships with a single entry and looks like it
    worked. Easiest way to silently break the page.
  - **`buildInfo`'s `time` is the COMMIT timestamp, not wall-clock** — a wall-clock value
    changes every build and invalidates `:backend:bootJar` in the cache `ci-build-speedup`
    only just got working.
  - **Summaries are generated at ingest by `ReleaseSummarySweep`, never on view.**
    `/api/platform/**` is deliberately absent from `RateLimitInterceptor` (the page makes two
    requests per view), so an LLM call on the read path would be both a cost and abuse problem.
  - **Release records are written by a startup component, not Mongock** — deliberate deviation:
    they are derived, self-healing data a restore has to re-establish, and change-unit LLM I/O
    would run against the shared Testcontainers Mongo. `V022` creates indexes only, and
    `RestoreService` calls `createIndexes()` directly because Mongock will not re-run.
  - **software-factory's `GET /api/version` is unauthenticated on purpose** — unrouted by
    nginx, discloses only a public-repo SHA. Token-protecting it would hand the backend a
    token that also authorises `/api/reviews`. This endpoint is what makes `deployer` drift
    visible, since it never recreates itself.
  - One commit == one release: `main` is squash-merged and Publish runs on every merge.
    Historical entries are labelled **published**, not deployed — `deploy_runs` is empty.
  - `platform_releases` is in `BackupService.BACKUP_COLLECTIONS` and
    `RestoreService.IMPORT_ORDER_INDEPENDENT`. See `docs/runbooks/platform-status.md`.
```

- [ ] **Step 3: Verify the runbook's commands are accurate**

Run: `curl -s http://localhost:8080/api/platform/status | jq '.services | length'`
Expected: `4` against a running local stack (backend, software-factory, deployer from the API;
the frontend entry is added client-side, so the API returns 3 — correct the runbook to say 3 if
that is what it returns).

- [ ] **Step 4: Commit**

```bash
git add docs/runbooks/platform-status.md CLAUDE.md
git commit -m "docs: document the platform status page"
```

---

## Final verification

Before opening a pull request, run the full gates:

- [ ] Backend: `cd backend && ../gradlew test checkstyleMain checkstyleTest jacocoTestCoverageVerification`
  — all pass, coverage floor of 0.78 still met.
- [ ] software-factory: `./gradlew :software-factory:test :software-factory:checkstyleMain :software-factory:checkstyleTest` — all pass.
- [ ] Frontend: `cd frontend && npm test && npm run lint && npx tsc --noEmit` — all pass.
- [ ] Whole build: `./gradlew build` — passes, including `NoHostProcessLaunchTest` (nothing in
      this feature launches a process; if that test fails, something reintroduced `ProcessBuilder`).
- [ ] `/status` renders correctly at 1280px and 375px against the local stack.
- [ ] `git log --oneline origin/main..HEAD` shows one commit per task, all conventional.

Then use the **`pr-review-loop` skill** to open the pull request and drive it to green. Do not
improvise the loop — it owns waiting on CI, the reviewer bot and SonarQube Cloud.
