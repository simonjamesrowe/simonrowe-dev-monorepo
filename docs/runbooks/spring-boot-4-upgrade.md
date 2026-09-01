# Spring Boot 4.1 upgrade

What the 3.5.16 → 4.1.1 upgrade actually involved, and the facts a future
framework upgrade will want first. Everything here was measured on this repo
during the upgrade (2026-08-31), not read from a migration guide.

Final state: **Spring Boot 4.1.1, Spring Framework 7.0.9, Java 25 LTS,
Gradle 9.7.1, Spring AI 2.0.1, Embabel 1.5.1, Jackson 3.1.5 (with the Jackson 2
line alongside), JUnit 6.0.3, Spring Kafka 4.1.1, Testcontainers 2.0.5,
Elasticsearch 9.4.5, Mongock 5.5.1 (unchanged).**

---

## 1. Why 4.1.1 and not 4.0.x

The OpenRewrite recipe pins the 4.0 line, and most write-ups target it. The
dependency graph rules it out here:

| Artifact | Declares |
| --- | --- |
| `embabel-agent-platform-autoconfigure:1.5.1` | `spring-boot-*:4.1.0`, `spring-ai-*:2.0.0` |
| `spring-ai-starter-model-openai:2.0.1` | `spring-boot-starter-restclient:4.1.1` |

Embabel 1.5.x is the **first** Embabel line supporting Boot 4 at all — 1.0.0 and
below compile against Boot 3.5.14. So there is no Boot 4.0 configuration of this
repo in which both Embabel and Spring AI are on a supported release.

There is no `UpgradeSpringBoot_4_1` recipe. The procedure is: run
`UpgradeSpringBoot_4_0`, then bump `springBoot` by hand.

Checked afterwards, and worth re-checking on any future bump — the concern is a
mixed tree, because Embabel requests Spring AI 2.0.0 while our BOM manages 2.0.1:

```bash
./gradlew :backend:dependencies --configuration runtimeClasspath \
  | grep -oE 'org\.springframework\.boot:[a-z0-9-]+:[0-9.]+'
```

Result: Boot 4.1.1 across all 44 artifacts, Framework 7.0.9, Spring AI 2.0.1
across all 26. `io.spring.dependency-management` holds.

## 2. Mongock: unsupported, and working

**Mongock declares no Boot 4 support and works anyway.** `spring-jdk17-5.5.1.pom`
declares `spring-boot-3.version = [3.0.0-RC1, 4.0.0)` and
`springframework-6.version = [6.0.0-RC2, 7.0.0)`, and there is no
`mongock-springboot-v4` artifact. Both are `provided`/`optional` scope, so the
ranges never reach a consumer classpath, and `io.spring.dependency-management`
forces the managed version regardless.

Verified in the real application, not just in isolation: all 31 change units ran
on Boot 4.1.1 / Spring Data MongoDB 5.x / mongodb-driver 5.8.1, with the
distributed lock acquired and released.

**A green test suite proves nothing about this.** `application-test.yml` sets
`mongock.enabled: false`. The gate is one named class:

```bash
./gradlew :backend:test --tests '*V011SeedAndBackfillDanVegaBlogIntegrationTest' \
  --rerun-tasks --info 2>&1 | grep -iE 'mongock|APPLIED'
```

Expect `Mongock acquired the lock`, a run of `APPLIED - {...}` lines, and
`Mongock has finished`. **A pass with no Mongock output means the property
override did not take and the gate did not run** — that is a failure, not a pass.

Because every production data change ships as a change unit, treat this as
unproven-but-observed rather than supported: re-run the gate on every Boot bump,
and watch <https://github.com/flamingock/mongock/releases>. Flamingock is the
successor but is a rewrite with a different API, and its MongoDB module is still
`0.0.38-beta` — a project of its own, not a step in an upgrade.

## 3. Java 25, not 26

Boot 4.1.1 supports Java 17–26, so 26 would compile. It should still not be
chosen: Java 26 is a short-term release that reaches end of support on
**18 September 2026**, and Java 27 is not an LTS either — the next LTS is Java 29
in September 2027. Java 25 is the current LTS.

Three things move with the toolchain, and none of them fail at compile time:

- **JaCoCo 0.8.12 cannot read Java 25 bytecode.** It fails the coverage tasks
  with a bare `Error while creating report` and no mention of class versions.
  0.8.14 added official Java 25 support; this repo is on 0.8.15.
- **`Dockerfile.software-factory` pinned `eclipse-temurin:21-jre`**, which cannot
  load Java 25 bytecode at all. Now `25-jre`.
- **`bootBuildImage` now pins `BP_JVM_VERSION`** rather than trusting the
  buildpack's default JRE. A lagging default produces an image that fails only at
  container start, after CI is green and the image is pushed.

`settings.gradle.kts` gained the **foojay toolchain resolver**. Without it Gradle
cannot provision a JDK, and the build only worked before because `/usr/bin/java`
happened to be 21.

## 4. What the OpenRewrite recipe did and did not do here

Recipe: `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` from
`org.openrewrite.recipe:rewrite-spring:6.37.1`, via the
`org.openrewrite.rewrite` Gradle plugin.

**Use plugin version 7.39.0.** 7.40.0 and 7.41.0 both resolve
`org.openrewrite:rewrite-bom:8.91.0`, which is **not on Maven Central** — Central
carries up to 8.90.4. This is the "recipes are moving to the Code Genome Project"
migration starting to bite; it was not yet a problem on 2026-08-21 and was by
2026-08-31. The plugin also needs a `repositories { mavenCentral() }` block in
the **root** build file, which this repo does not otherwise have.

It changed **73 files**, and the useful part was Java source:

- Jackson 2 → 3 across 78 `databind`/`core` imports.
- Boot 4 package moves: `boot.web.client` → `boot.restclient`,
  `boot.actuate.health` → `boot.health.contributor`, and the slice-test
  annotations into per-technology `autoconfigure` packages.
- JUnit 5 → 6 needed **no** changes.

Three parts of its output were rejected, and the reasons generalise:

- **It never touched `gradle/libs.versions.toml`.** Every version here is behind
  a version-catalogue alias, and `UpgradeDependencyVersion` /
  `MigrateToModularStarters` cannot see through that indirection. So Boot itself,
  the modular starter renames, Testcontainers, Spring AI and Embabel were all
  hand changes. **Expect this on any future recipe run in this repo** — the
  recipe is a source-code tool here, not a dependency tool.
- **Its YAML edits mangled comment-heavy config**, re-indenting comment blocks
  away from the keys they document. Its property renames were, on checking,
  *correct* (see §5) — but the files were reverted and the renames redone by hand.
- **13 files whose only change was a cosmetic text-block conversion**, including
  two already-applied Mongock change units and the guardrail classifier prompt.
  Rewriting the whitespace of a live LLM prompt is not a free refactor.

The recipe also edits the **root** `build.gradle.kts` (it bumps the Boot plugin
version), so the scaffolding cannot be removed with
`git checkout -- build.gradle.kts` without inspecting the diff first.

## 5. Config renames, verified against Boot's own metadata

Do not trust a migration guide for these; read
`META-INF/spring-configuration-metadata.json` out of the shipped jar. Two moved:

| Old | New | Module |
| --- | --- | --- |
| `spring.data.mongodb.uri` | `spring.mongodb.uri` | `spring-boot-mongodb` |
| `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` | `spring-boot-micrometer-tracing-opentelemetry` |
| `management.otlp.tracing.transport` | `management.opentelemetry.tracing.export.otlp.transport` | as above |
| `management.otlp.tracing.export.enabled` | `management.tracing.export.otlp.enabled` | as above |

Two traps in that table:

- **The tracing keys do not all move to the same prefix.** `.endpoint` goes under
  `management.opentelemetry.tracing.export.otlp`, while `.export.enabled` goes
  under `management.tracing.export.otlp`. That looks like an inconsistency and is
  not; both are in the metadata with explicit `deprecation.replacement` entries.
- **Mongo's old key is silently ignored, not rejected**, and the driver falls
  back to `localhost:27017`. That is how it surfaced — 14 `@DataMongoTest`
  failures — and `docker-compose.prod.yml` set `SPRING_DATA_MONGODB_URI`, so
  production would have done exactly the same thing. Grep for the env-var form
  (`SPRING_DATA_MONGODB_*`) as well as the dotted form.

**`spring.autoconfigure.exclude` entries naming a class that no longer exists are
silently ignored too.** Three such entries were left behind by the Spring AI
merge. Check each one still resolves after any upgrade.

## 6. Spring AI 1.1.8 → 2.0.1

Artifact changes:

| Declared before | After |
| --- | --- |
| `spring-ai-starter-model-openai` | unchanged, 2.0.1 |
| `spring-ai-starter-mcp-server-webmvc` | unchanged, 2.0.1 |
| `spring-ai-starter-vector-store-elasticsearch` | unchanged, 2.0.1 |
| `spring-ai-advisors-vector-store` | **renamed** `spring-ai-vector-store-advisor` |
| `spring-ai-starter-model-openai-sdk` | **deleted**, folded into `spring-ai-starter-model-openai` |

The openai-sdk merge (Spring AI 2.0.0-M5) is the load-bearing one: one module now
drives chat, embedding and image through the official `openai-java` SDK. So
`spring.ai.openai-sdk.*` is gone, and Spring AI 2.0 also flattened the
`.options.` segment out of every model property
(`spring.ai.openai.chat.options.model` → `spring.ai.openai.chat.model`).

**The test profile is where this bites.** It used to exclude
`OpenAiChatAutoConfiguration` to stop the SDK and non-SDK chat models colliding —
which means the *SDK's* model is what supplied the `ChatModel` bean in tests.
After the merge there is exactly one chat autoconfiguration, and excluding it
leaves the context with no `ChatModel` at all: **356 tests failed at context
startup**, all of them downstream of one bean.

Our own Spring AI code needed no changes. `CountingToolCallingManager` still
compiles and passes its tests: `ToolCallingManager.executeToolCalls` is still on
the execution path in 2.0.

## 7. Jackson 2 and 3 coexist, and that is the point

Boot 4 manages `jackson-bom` (3.1.5) and `jackson-2-bom` (2.21.5) side by side.
In Boot 4, **`jackson-bom.version` means Jackson 3** — carrying an old
`ext["jackson-bom.version"] = "2.21.5"` override forward would pin Jackson 3 to a
version that does not exist.

`jackson-annotations` keeps the `com.fasterxml.jackson.core` groupId, so
`@JsonValue`, `@JsonCreator`, `@JsonProperty`, `@JsonInclude` and
`@JsonIgnoreProperties` stay on `com.fasterxml.jackson.annotation`. All 19 of
ours did; verify that after any Jackson recipe run.

Renames the recipe moved the package for but not the name:

- `JsonProcessingException` → `JacksonException`
- `JsonNode.fieldNames()` → `propertyNames()` (a `Collection`, not an `Iterator`)

**Jackson 3 throws unchecked**, so nine `catch (IOException)` clauses became
provably dead (`exception IOException is never thrown in body of corresponding
try statement`). Each was converted to `catch (JacksonException)` rather than
deleted — two of them carry real fallback behaviour, and deleting them would have
turned a handled parse failure into a propagating one.

Two behavioural changes worth knowing:

- **`FAIL_ON_NULL_FOR_PRIMITIVES` is ON by default in Jackson 3** (it was off in
  2.x). Every request body that omits a primitive field starts returning 400.
  Verified directly: `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES
  .enabledByDefault() == true` in jackson-databind 3.1.5. Restored with
  `spring.jackson.deserialization.fail-on-null-for-primitives: false`, because
  silently 400ing previously-valid requests is a breaking API change, not an
  improvement. Note this property does **not** reach a
  `MockMvcBuilders.standaloneSetup` test, which builds its own converters — so a
  default that matters for safety should be encoded in the type, as
  `FactoryAdminController.ReviewRequest` now does in its compact constructor.
- **Third-party clients are still on Jackson 2.** The Elasticsearch client's
  `JacksonJsonpMapper` accepts only `com.fasterxml.jackson.databind.ObjectMapper`,
  and `spring-boot-jackson2` is not on the classpath, so there is no Jackson 2
  `ObjectMapper` bean to inject. `ElasticsearchJsonpMapperConfig` builds its own
  and must re-establish two things the injected Boot mapper used to provide:
  JSR-310 (without it, indexing anything carrying an `Instant` throws) and
  ISO-8601 dates (without it, ES infers a different mapping for every date field).

## 8. Elasticsearch 8.17 → 9.4.5 — the operator-facing part

Not optional. Boot 4.1.1 manages `elasticsearch-java` **9.4.5**, and a 9.x client
refuses an 8.x server, failing every request with:

```
node: http://…, status: 400, [es/indices.exists] Expecting a response body, but none was sent
```

The server is pinned to the same version as the client in `docker-compose.yml`,
`docker-compose.prod.yml`, `.github/workflows/evals.yml` and
`ApplicationTests`. Keep those four in step.

**Before recreating the production container:** take the `content-embeddings`
backup via `ElasticsearchBackupService`. The search indices are derived and
rebuild from MongoDB (`IndexService` / `SearchIndexSyncScheduler` /
`ContentChangeConsumer`), but the vector index costs real money in embedding
calls to regenerate. Elasticsearch will not start 9.x against a data directory
and then let you go back to 8.x.

`ProdImageCatalogTest` asserts the compose image tag, so a drift between the
compose file and that test is a build failure rather than a silent surprise.

## 9. Other Boot 4 API moves found by compiling

- `ClientHttpRequestFactorySettings` → `HttpClientSettings` (same
  `defaults()`/`withConnectTimeout()`/`withReadTimeout()` methods).
- Spring Kafka 4: `@RetryableTopic(backoff = @Backoff(...))` →
  `backOff = @BackOff(...)`, and the annotation is Kafka's own
  (`org.springframework.kafka.annotation.BackOff`), not Spring Retry's.
- **Tracing auto-configuration was split out.** `micrometer-tracing-bridge-otel`
  still supplies `OtelTracer`, but nothing builds the `Tracer` bean from it any
  more — that is `OpenTelemetryTracingAutoConfiguration` in
  `spring-boot-micrometer-tracing-opentelemetry`, which is now an explicit
  dependency. Without it the context has no `io.micrometer.tracing.Tracer` and
  **the Langfuse trace pipeline goes silent**. Caught by
  `ApplicationTests.micrometerObservationsBecomeTracingSpans`. The narrow module
  is used rather than `spring-boot-starter-opentelemetry`, which would also pull
  in `micrometer-registry-otlp` and stand a second metrics registry beside the
  Prometheus one.
- **Modular starters**: `spring-boot-starter-web` → `-webmvc`,
  `spring-boot-starter-oauth2-resource-server` →
  `spring-boot-starter-security-oauth2-resource-server`, `spring-kafka` →
  `spring-boot-starter-kafka`, `spring-security-test` →
  `spring-boot-starter-security-test`. Slice tests are no longer implicitly
  auto-configured, so `spring-boot-starter-webmvc-test` and
  `spring-boot-starter-data-mongodb-test` are now declared.
- **Testcontainers 2.x renamed every module artifact** to a `testcontainers-`
  prefix (`org.testcontainers:mongodb` → `org.testcontainers:testcontainers-mongodb`).
  This fails as an unresolvable dependency, not a compile error.

## 10. Managed-version overrides

Five of the six SIM-9 `ext[...]` overrides in the root `build.gradle.kts` were
deleted, because Boot 4.1.1 now ships at or above each fixed version and keeping
them would have pinned dependencies **below** Boot:

| Override | Was | Boot 4.1.1 | Outcome |
| --- | --- | --- | --- |
| `commons-lang3` | 3.18.0 | 3.20.0 | would downgrade |
| `jackson-bom` | 2.21.5 | 3.1.5 | would pin Jackson 3 to a nonexistent version |
| `httpclient5` | 5.6.4 | 5.6.4 | redundant |
| `httpcore5` | 5.4.3 | 5.4.3 | redundant |
| `log4j2` | 2.25.5 | 2.25.5 | redundant |
| `opentelemetry` | 1.64.0 | 1.62.0 | **kept** |

Re-run this comparison on every Boot bump; the whole block is a set of temporary
pins whose failure mode is silent.

## 11. Build tooling

- **Gradle 9.7.1.** Boot 4's floor is 8.14.
- **CycloneDX 3.x split the SBOM task in two.** `cyclonedxBom` is now an
  *aggregate* task; `includeConfigs` moved to a per-project `cyclonedxDirectBom`
  and must be configured across **all three** projects, or `backend` and
  `software-factory` contribute every configuration they have. The aggregate's
  output also moved to `build/reports/cyclonedx/`; it is pinned back to
  `build/reports/bom.json`, which is what `publish.yml` hands to
  `DependencyTrack/gh-upload-sbom` — and that upload is a `continue-on-error`
  step, so getting it wrong would go unnoticed. Verify with a component count,
  not an exit code (352 components, no `checkstyle`/Testcontainers leakage).
- **SonarQube plugin 7.4.0.8496**, bumped alongside Gradle 9.

## 12. Verification gates

In order, and read the output rather than the exit code:

```bash
./gradlew :backend:checkstyleMain :backend:checkstyleTest \
          :software-factory:checkstyleMain :software-factory:checkstyleTest
./gradlew :software-factory:test        # gates merging — see below
./gradlew :backend:test --rerun-tasks
./gradlew :backend:jacocoTestCoverageVerification
./gradlew :backend:test --tests '*V011SeedAndBackfillDanVegaBlogIntegrationTest' --info   # §2
./scripts/test/run-tests.sh
./gradlew cyclonedxBom                  # then count components
```

Baselines to compare against: **backend 1160 tests, software-factory 582**. A
count that *drops* means tests silently stopped being discovered; investigate
rather than accepting a green run.

`software-factory` hosts the `Code Review` check that
`.github/rulesets/main.json` requires on every PR to `main`, so it must be green
before a Boot-upgrade PR is opened. Repo-admin bypass is the only recovery.

`UP-TO-DATE` is not trustworthy after a recipe run — pass `--rerun-tasks`.

Finally, a runtime smoke test via `local-env`: a Boot major upgrade breaks at
context startup in ways that compile and unit-test perfectly. Then
`chat-e2e-verify` and `langfuse-verify`, because the Spring AI and tracing
changes are both on paths that unit tests mock.
