plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    alias(libs.plugins.graalvm.native)
    checkstyle
    jacoco
}

group = "com.simonrowe"
version = "0.0.1-SNAPSHOT"

ext["opentelemetry.version"] = "1.59.0"

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
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

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.elasticsearch)
}
