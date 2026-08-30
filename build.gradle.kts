plugins {
    java
    id("org.springframework.boot") version libs.versions.springBoot.get() apply false
    id("io.spring.dependency-management") version libs.versions.springDependencyManagement.get() apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.sonarqube)
}

group = "com.simonrowe"
version = "0.0.1-SNAPSHOT"

// ---------------------------------------------------------------------------
// The SBOM uploaded to Dependency-Track as `simonrowe-dev/backend` describes what
// SHIPS, not what builds it.
//
// Left unconfigured, org.cyclonedx.bom resolves EVERY resolvable configuration of
// every module — `checkstyle`, `compileClasspath`, `testCompileClasspath` and
// `testRuntimeClasspath` included. That is not a theoretical concern: it is how
// commons-beanutils (Checkstyle's own tool classpath), and netty-codec plus
// commons-compress (Testcontainers' docker-java, test-only) got reported as
// production vulnerabilities in SIM-9. None of the three is in the deployed jar.
//
// Restricting this to runtimeClasspath makes a finding here mean "this ships and
// is exploitable in production". Build- and test-time dependencies are still
// covered, separately and correctly, by the three container-image SBOMs that
// publish.yml generates with anchore/sbom-action.
// ---------------------------------------------------------------------------
tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
}

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

    // -----------------------------------------------------------------------
    // Managed-version overrides that clear Dependency-Track findings (SIM-9).
    //
    // These live HERE, not in the module build files, because the SBOM uploaded to
    // Dependency-Track as `simonrowe-dev/backend` is produced by the ROOT
    // `cyclonedxBom` task, which spans every configuration of every module. An
    // override applied to `backend` alone leaves `software-factory` resolving the
    // vulnerable version, and the finding survives with both versions listed —
    // exactly what opentelemetry-api did before this change.
    //
    // They MUST be `ext[...]` property overrides, not Gradle `constraints`. The
    // io.spring.dependency-management plugin FORCES every version the Spring Boot
    // BOM manages, which beats a constraint outright — commons-lang3 is the proof:
    // embabel-common-util already requested the fixed 3.18.0 and the BOM silently
    // dragged it back down to the vulnerable 3.17.0. Overriding the BOM's own
    // property is the only thing dependency-management honours.
    //
    // Each value is the lowest released version that clears the advisory (the two
    // exceptions are noted below). Drop an entry once the Spring Boot BOM ships
    // that version or newer, or it will silently pin the dependency BELOW Boot.
    // -----------------------------------------------------------------------

    // GHSA-rcgg-9c38-7xpx, fixed in 1.62.0. 1.64.0 was already pinned by backend
    // before this change; kept so both modules stay on one OpenTelemetry version.
    ext["opentelemetry.version"] = "1.64.0"
    // GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v, GHSA-mhm7-754m-9p8w (Boot: 2.21.4)
    ext["jackson-bom.version"] = "2.21.5"
    // GHSA-j288-q9x7-2f5v (Boot: 3.17.0)
    ext["commons-lang3.version"] = "3.18.0"
    // GHSA-hjcp-jmpx-g3qm, fixed in 5.6.3. 5.6.4 is the current 5.6 patch and needs
    // httpcore5 5.4.x, which is what the line below pins.
    ext["httpclient5.version"] = "5.6.4"
    // GHSA-hf6x-8p5f-cgmf and GHSA-v3jc-474w-2wm6 — one property covers both
    // httpcore5 and httpcore5-h2, which the BOM versions together (Boot: 5.3.6)
    ext["httpcore5.version"] = "5.4.3"
    // GHSA-qv9r-c865-cp47 (Boot: 2.24.3)
    ext["log4j2.version"] = "2.25.5"

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
