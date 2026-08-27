import java.time.Instant
import java.time.format.DateTimeFormatter

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    checkstyle
    jacoco
}

group = "com.simonrowe"
version = "0.1.0-SNAPSHOT"

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

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
            // BuildInfoProperties.time is Property<String> (ISO-8601), NOT Property<Instant> —
            // verified against the Spring Boot Gradle plugin 3.5.16 while implementing Task 1,
            // where passing an Instant did not compile. Format it explicitly.
            time.set(factoryHeadEpoch.map {
                DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochSecond(it.ifBlank { "0" }.toLong()))
            })
            additional.put("commit", factoryHeadSha.map { it.ifBlank { "unknown" } })
            additional.put("commitTime", factoryHeadEpoch.map { it.ifBlank { "0" } })
            additional.put("commitSubject", factoryHeadSubject.map { it.ifBlank { "" } })
        }
    }
}

// Report only — deliberately NO jacocoTestCoverageVerification and NO tasks.check
// wiring, unlike backend. This module's coverage has never been measured; inventing
// a floor before measuring it either fails the build on day one or sets the floor
// below actual coverage. The measured figure is reported and the floor set from it
// in a follow-up. The XML report feeds sonar.coverage.jacoco.xmlReportPaths.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.temporal.spring.boot.starter)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.temporal.testing)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
}
