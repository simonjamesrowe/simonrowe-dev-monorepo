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
    implementation("com.embabel.agent:embabel-agent-starter:0.3.5")
    implementation("com.embabel.agent:embabel-agent-starter-openai:0.3.5")
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
    testImplementation("com.embabel.agent:embabel-agent-test:0.3.5")
}
