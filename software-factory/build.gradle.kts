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
