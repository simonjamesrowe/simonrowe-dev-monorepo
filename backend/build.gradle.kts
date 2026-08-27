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

ext["opentelemetry.version"] = "1.64.0"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.embabel.com/artifactory/embabel-releases") }
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
    from(rootProject.file("docker-compose.prod.yml")) {
        into("platform")
    }
    from(generateReleaseHistory) {
        into("platform")
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
    "com/simonrowe/agents/WeeklyDigestAgent*"
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
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.elasticsearch)
    implementation(libs.spring.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.spring.boot.starter)
    // Bridges the Micrometer Observation API (used by Spring AI's ChatClient/ChatModel to
    // emit gen_ai observations) into OpenTelemetry spans. Without it, only the OTel
    // library instrumentation (HTTP, Mongo) produced spans and the chat generations never
    // reached Langfuse. See docs/runbooks/langfuse-observability.md.
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.openpdf)
    implementation(libs.commonmark)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.ai.starter.model.openai)
    implementation(libs.spring.ai.starter.model.openai.sdk)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.spring.ai.starter.vector.store.elasticsearch)
    implementation(libs.spring.ai.advisors.vector.store)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.bucket4j.core)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.thumbnailator)
    implementation(libs.mongock.springboot.v3)
    implementation(libs.mongock.mongodb.springdata.v4)
    implementation(libs.google.api.client)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.auth.library.oauth2.http)
    implementation(libs.google.http.client.apache.v5)
    implementation(libs.embabel.agent.starter)
    implementation(libs.embabel.agent.starter.openai)
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.rometools:rome:2.1.0")
    // Required for the <if>/<then>/<else> conditional in logback-spring.xml that picks
    // between plain-text and structured (JSON) console output. Version managed by the
    // Spring Boot BOM.
    runtimeOnly("org.codehaus.janino:janino")

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.test)
    // TestObservationRegistry, for asserting on Micrometer observations without a tracer.
    // Version managed by the Spring Boot BOM.
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.embabel.agent.test)
}
