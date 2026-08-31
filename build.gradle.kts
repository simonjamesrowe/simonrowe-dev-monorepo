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
//
// CycloneDX 3.x split the old single `cyclonedxBom` task in two, so the shape of
// this block changed even though its intent did not:
//
//   * `cyclonedxDirectBom` (CyclonedxDirectTask) is registered per project and is
//     the only one carrying `includeConfigs`. It must be configured across ALL
//     projects — the root plus both modules — because the aggregate reads each
//     module's direct BOM, so filtering the root alone would leave `backend` and
//     `software-factory` contributing every configuration they have.
//   * `cyclonedxBom` (CyclonedxAggregateTask) merges those into the BOM we upload.
//
// The aggregate's output also moved to `build/reports/cyclonedx/`. It is pinned
// back to `build/reports/bom.json` here rather than chased through CI, because
// that exact path is what `publish.yml` hands to DependencyTrack/gh-upload-sbom
// and what `ci.yml` archives — and a BOM that fails to upload is reported as a
// `continue-on-error` step, so getting this wrong would go unnoticed.
// ---------------------------------------------------------------------------
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath"))
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    jsonOutput.set(layout.buildDirectory.file("reports/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/bom.xml"))
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
    // `cyclonedxBom` task, which spans every module. An override applied to
    // `backend` alone leaves `software-factory` resolving the vulnerable version,
    // and the finding survives with both versions listed.
    //
    // They MUST be `ext[...]` property overrides, not Gradle `constraints`. The
    // io.spring.dependency-management plugin FORCES every version the Spring Boot
    // BOM manages, which beats a constraint outright. Overriding the BOM's own
    // property is the only thing dependency-management honours.
    //
    // FIVE overrides were removed in the Boot 4.1.1 upgrade, because that BOM now
    // ships at or above the version that cleared each advisory. Keeping them would
    // have pinned those dependencies BELOW Boot, which is the exact failure the old
    // comment here warned about:
    //
    //   commons-lang3   3.18.0 override vs Boot 4.1.1's 3.20.0 -> downgrade
    //   httpclient5     5.6.4  == Boot 4.1.1's 5.6.4          -> redundant
    //   httpcore5       5.4.3  == Boot 4.1.1's 5.4.3          -> redundant
    //   log4j2          2.25.5 == Boot 4.1.1's 2.25.5         -> redundant
    //   jackson-bom     2.21.5 vs Boot 4.1.1's 3.1.5          -> BROKEN
    //
    // The jackson one is the dangerous one and is worth spelling out: in Boot 4
    // `jackson-bom.version` means JACKSON 3, and the Jackson 2 line moved to a
    // separate `jackson-2-bom.version` (4.1.1 manages 3.1.5 and 2.21.5
    // respectively). Carrying the old value forward would have pinned Jackson 3 to
    // "2.21.5", a version that does not exist.
    //
    // Drop the remaining entry once the Boot BOM ships that version or newer.
    // -----------------------------------------------------------------------

    // GHSA-rcgg-9c38-7xpx, fixed in 1.62.0, which is exactly what Boot 4.1.1
    // manages. Kept above it at 1.64.0 so both modules stay on one OpenTelemetry
    // version alongside the separately-pinned opentelemetry-spring-boot-starter.
    ext["opentelemetry.version"] = "1.64.0"

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
