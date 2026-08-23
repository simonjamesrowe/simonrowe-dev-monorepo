plugins {
    java
    id("org.springframework.boot") version libs.versions.springBoot.get() apply false
    id("io.spring.dependency-management") version libs.versions.springDependencyManagement.get() apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.sonarqube)
}

group = "com.simonrowe"
version = "0.0.1-SNAPSHOT"

// Static analysis for the whole monorepo — see docs/runbooks/static-analysis.md.
//
// Deliberately absent: sonar.qualitygate.wait. Setting it makes the scanner block on
// and then fail over gate status; the gate is advisory until a baseline exists.
// Also absent: sonar.eslint.reportPaths — Sonar's own TypeScript rules already run
// over frontend/src, so importing ESLint findings would duplicate them.
sonar {
    properties {
        property("sonar.projectKey", "simonjamesrowe_simonrowe-dev-monorepo")
        property("sonar.organization", "simonjamesrowe")
        property("sonar.host.url", "https://sonarcloud.io")

        // Both Java modules. The reports are produced by the backend and
        // software-factory CI jobs and reach the sonar job as artifacts — this job
        // runs no tests, so a missing report silently reads as 0% coverage rather
        // than failing. The sonar job asserts these paths exist before analysing.
        property("sonar.coverage.jacoco.xmlReportPaths",
            listOf(
                "backend/build/reports/jacoco/test/jacocoTestReport.xml",
                "software-factory/build/reports/jacoco/test/jacocoTestReport.xml"
            ).joinToString(","))

        // The frontend has no Gradle module, so its analysis is configured on the root
        // project. Safe: the root applies the `java` plugin but has no Java sources of
        // its own, and each subproject contributes its own source roots from its own
        // java plugin conventions.
        //
        // sonar.tests deliberately OVERLAPS sonar.sources on frontend/src: 58 test
        // files live in frontend/tests but 9 sit beside the code they test. The two
        // filters below are what keep every file indexed exactly once — drop
        // sonar.exclusions and the scanner aborts ("File can't be indexed twice");
        // drop sonar.test.inclusions and those 9 are analysed as production code.
        // frontend/src/test/setup.ts is Vitest harness, not a test, and correctly
        // stays main source — it matches no *.test.* pattern.
        property("sonar.sources", "frontend/src")
        property("sonar.tests", "frontend/tests,frontend/src")
        property("sonar.exclusions",
            "frontend/src/**/*.test.ts,frontend/src/**/*.test.tsx")
        property("sonar.test.inclusions",
            listOf(
                "frontend/tests/**",
                "frontend/src/**/*.test.ts",
                "frontend/src/**/*.test.tsx"
            ).joinToString(","))
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/lcov.info")
        property("sonar.typescript.tsconfigPaths", "frontend/tsconfig.app.json")

        // Mirrors `jacocoExcludes` in backend/build.gradle.kts, one entry for one
        // entry. NOT a copy — a translation. JaCoCo matches compiled class files
        // relative to the class output root; Sonar matches source files relative to a
        // module base directory. Hence the .java suffixes and the `**/` anchor, which
        // makes the patterns resolve whichever basedir the scanner applies them
        // against. A literal copy of jacocoExcludes would match nothing while looking
        // configured.
        //
        // KEEP THIS LIST IN STEP WITH backend's jacocoExcludes. There is no automated
        // check. Drift presents as the Sonar and JaCoCo backend coverage percentages
        // disagreeing over the same code.
        property("sonar.coverage.exclusions",
            listOf(
                "**/com/simonrowe/migration/**",
                "**/com/simonrowe/dataops/**",
                "**/com/simonrowe/embedding/**",
                "**/com/simonrowe/agents/scrapers/SitemapHtmlScraper*.java",
                "**/com/simonrowe/agents/scrapers/LumaApiScraper*.java",
                "**/com/simonrowe/media/ExternalImageDownloader*.java",
                "**/com/simonrowe/aggregation/AdminAggregationController*.java",
                "**/com/simonrowe/agents/ContentAggregationAgent*.java",
                "**/com/simonrowe/agents/WeeklyDigestAgent*.java"
            ).joinToString(","))
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
