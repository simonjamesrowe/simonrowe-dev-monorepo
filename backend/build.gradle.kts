import java.time.Instant
import java.time.format.DateTimeFormatter

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    // Disabled: Embabel requires JVM mode (kotlin-reflect incompatible with native image)
    // alias(libs.plugins.graalvm.native)
    checkstyle
    jacoco
}

group = "com.simonrowe"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
        mavenBom(libs.mongock.bom.get().toString())
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

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
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(it.ifBlank { "0" }.toLong()))
            })
            additional.put("commit", headSha.map { it.ifBlank { "unknown" } })
            additional.put("commitTime", headEpoch.map { it.ifBlank { "0" } })
            additional.put("commitSubject", headSubject.map { it.ifBlank { "" } })
            additional.put("branch", headBranch.map { it.ifBlank { "unknown" } })
        }
    }
}

// The status page reports which third-party image tags production runs. Shipping the
// compose file itself — rather than a JSON summary generated in Gradle — keeps all the
// parsing in Java where it is unit-testable, and makes drift between parser and compose
// file a test failure rather than a silent wrong answer.
//
// The changelog is deliberately NOT baked in here any more. It used to be (`git log -n 50`
// into a resource), which tied the changelog to the backend image: once Publish stopped
// rebuilding images whose inputs had not changed, a frontend-only merge would never have
// reached /status until the next backend change. `GitHubCommitHistory` reads main's
// history from the GitHub API at runtime instead.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("docker-compose.prod.yml")) {
        into("platform")
    }
}

normalization {
    runtimeClasspath {
        // build-info.properties embeds HEAD's SHA, subject and commit time, so it changes on
        // every commit. It sits in build/resources/main, which is on the test runtime
        // classpath, so without this every commit changed :backend:test's cache key and the
        // Testcontainers suite re-ran in full even for a docs-only change — measured: a new
        // empty commit turned FROM-CACHE into a full run. No test reads the generated file;
        // every test that needs BuildProperties constructs its own.
        ignore("META-INF/build-info.properties")
    }
}

val jacocoExcludes = listOf(
    "com/simonrowe/migration/**",
    "com/simonrowe/dataops/**",
    "com/simonrowe/embedding/**",
    "com/simonrowe/agents/scrapers/SitemapHtmlScraper*",
    "com/simonrowe/agents/scrapers/LumaApiScraper*",
    "com/simonrowe/media/ExternalImageDownloader*",
    "com/simonrowe/aggregation/AdminAggregationController*",
    "com/simonrowe/agents/ContentAggregationAgent*",
    "com/simonrowe/agents/WeeklyDigestAgent*",
    // Term Time's outbound I/O, excluded on exactly the precedent set above: these are HTTP
    // clients and crawlers whose behaviour lives in the remote system, the same reason
    // SitemapHtmlScraper, LumaApiScraper and ExternalImageDownloader are here. Everything that
    // decides anything - tiering, dedup, precedence, the ingest cutoff, link classification,
    // date reading, rendering - is deliberately NOT excluded and is covered by tests.
    "com/simonrowe/school/ingest/GmailClient*",
    "com/simonrowe/school/ingest/SchoolWebsiteCrawler*",
    "com/simonrowe/school/ingest/CalendarFeedClient*",
    "com/simonrowe/school/ingest/SchoolPdfExtractor*",
    // SchoolLinkFetcher is excluded for its HTTP mechanics only, and that exclusion is NOT a
    // statement that its behaviour is unimportant: it carries the SSRF guard for URLs supplied
    // by whoever emailed the school. A review of this feature found the guard covered only the
    // first hop while the client auto-followed redirects. The security property is therefore
    // pinned directly by SchoolLinkFetcherRedirectTest rather than left to a coverage figure.
    "com/simonrowe/school/admin/SchoolLinkFetcher*",
    // Mirrors AdminAggregationController above: a thin admin HTTP surface over services that
    // are themselves tested.
    "com/simonrowe/school/admin/SchoolAdminController*"
)

val jacocoClassDirectories = sourceSets.main.get().output.asFileTree.matching {
    exclude(jacocoExcludes)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(jacocoClassDirectories)
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(jacocoClassDirectories)
    violationRules {
        rule {
            limit {
                minimum = "0.78".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.test {
    systemProperty("auth0.jwt.enabled", "false")
    maxHeapSize = "1536m"
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    runImage.set("paketobuildpacks/run-noble-base:latest")
    // BP_JVM_VERSION is pinned, not left to the buildpack's default, because the
    // consequence of getting it wrong is invisible until production. The JVM
    // buildpack picks a JRE from its own default when nothing asks for one, and a
    // default that lags the toolchain gives an image whose JRE cannot load our
    // Java 25 bytecode — an UnsupportedClassVersionError at container start, long
    // after CI has gone green and the image has been pushed.
    //
    // Keep this in step with the toolchain's languageVersion in the root build file.
    environment.put("BP_JVM_VERSION", "25")
    // Project Leyden AOT cache (JEP 483/514/515): the buildpack starts the app once at build
    // time with -Dspring.context.exit=onRefresh, records which classes were loaded and linked,
    // and the runtime JVM maps that cache instead of re-doing the work. It holds class
    // metadata, never the heap, so no secret from the build environment can end up in it.
    // A cache the JVM cannot use (different JDK, changed classpath) is ignored with a warning,
    // not a failure, so the worst case is today's startup time.
    //
    // One combination IS fatal: the JVM refuses to start when -XX:AOTCache, which the buildpack
    // adds at launch, meets any -Xshare option. So the cache is switched off with
    // BPL_JVM_AOTCACHE_ENABLED=false (BACKEND_AOT_CACHE_ENABLED in docker-compose.prod.yml),
    // never with -Xshare:off on its own.
    environment.put("BP_JVM_AOTCACHE_ENABLED", "true")
    // The training run inherits the build environment, so this activates the aot-training
    // profile for that run only: build-time variables are not persisted into the image
    // (verified with `docker inspect`, and asserted by the Publish workflow). The profile
    // swaps out the few beans that contact a datastore while being created — see
    // application-aot-training.yml and AotTrainingConfiguration.
    environment.put("SPRING_PROFILES_ACTIVE", "aot-training")
    // The build time, not the plugin's default of a fixed 1980-01-01 (chosen for
    // reproducible image ids). Production prunes unused images with
    // `docker image prune --filter until=72h`, and `until` reads this field: with
    // the fixed date every backend image looks 46 years old, so the keep window
    // that holds recent images for a manual rollback never applied to this one.
    // Each commit already builds a different image (the jar embeds build info),
    // so a reproducible id bought nothing. See scripts/lib/image-prune.sh.
    createdDate.set("now")
}

dependencies {
    // ---------------------------------------------------------------------------
    // Transitives NOT managed by the Spring Boot BOM, so a plain Gradle constraint
    // is enough here — dependency-management only forces what the BOM declares.
    // ---------------------------------------------------------------------------
    constraints {
        // GHSA-xx22-p4ch-683r — pulled in by kafka-clients via spring-kafka.
        implementation("at.yawk.lz4:lz4-java:1.11.1")
        // GHSA-6fmv-xxpf-w3cw — maven-artifact 3.6.1 requests the vulnerable 3.2.0,
        // and reaches runtimeClasspath through mongock-runner-core.
        implementation("org.codehaus.plexus:plexus-utils:3.6.1")
    }

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.elasticsearch)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.spring.boot.starter)
    // Bridges the Micrometer Observation API (used by Spring AI's ChatClient/ChatModel to
    // emit gen_ai observations) into OpenTelemetry spans. Without it, only the OTel
    // library instrumentation (HTTP, Mongo) produced spans and the chat generations never
    // reached Langfuse. See docs/runbooks/langfuse-observability.md.
    implementation(libs.micrometer.tracing.bridge.otel)
    // Boot 4 split the tracing auto-configuration out of the monolithic autoconfigure jar.
    // micrometer-tracing-bridge-otel above still supplies OtelTracer, but nothing creates the
    // Tracer bean from it any more — that is OpenTelemetryTracingAutoConfiguration, which
    // lives here, along with the OtlpTracingAutoConfiguration that reads
    // management.opentelemetry.tracing.export.otlp.*. Without this module the context has no
    // io.micrometer.tracing.Tracer at all and the Langfuse trace pipeline goes silent.
    //
    // The narrow module rather than spring-boot-starter-opentelemetry, which would also pull
    // in micrometer-registry-otlp and put a second metrics registry beside the Prometheus one.
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.openpdf)
    // Text extraction from the school's PDF newsletters, term dates and menus.
    // openpdf above WRITES pdfs; it cannot read text out of one.
    implementation(libs.pdfbox)
    implementation(libs.commonmark)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.spring.ai.starter.vector.store.elasticsearch)
    implementation(libs.spring.ai.vector.store.advisor)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.bucket4j.core)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.thumbnailator)
    implementation(libs.mongock.springboot.v3)
    implementation(libs.mongock.mongodb.springdata.v4)
    implementation(libs.google.api.client)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.auth.library.oauth2.http)
    implementation(libs.google.http.client.apache.v5)
    implementation(libs.embabel.agent.starter)
    implementation(libs.embabel.agent.starter.openai)
    // 1.23.1 clears GHSA-pmhh-3w7g-xqp8.
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("com.rometools:rome:2.1.0")

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.test)
    // Boot 4 no longer implicitly auto-configures slice-test infrastructure; the
    // @WebMvcTest / @DataMongoTest annotations moved into these per-technology starters.
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.mongodb.test)
    // TestObservationRegistry, for asserting on Micrometer observations without a tracer.
    // Version managed by the Spring Boot BOM.
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.embabel.agent.test)
}
