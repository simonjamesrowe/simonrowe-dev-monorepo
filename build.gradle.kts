plugins {
    java
    id("org.springframework.boot") version libs.versions.springBoot.get() apply false
    id("io.spring.dependency-management") version libs.versions.springDependencyManagement.get() apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.sonarqube)
}

group = "com.simonrowe"
version = "0.0.1-SNAPSHOT"

sonar {
    properties {
        property("sonar.projectKey", "simonjamesrowe_simonrowe-dev-monorepo")
        property("sonar.organization", "simonjamesrowe")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths",
            "backend/build/reports/jacoco/test/jacocoTestReport.xml")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
