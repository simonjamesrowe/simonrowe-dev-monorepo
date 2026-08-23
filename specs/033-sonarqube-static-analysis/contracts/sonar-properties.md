# Contract: Sonar analysis properties

**Feature**: 033-sonarqube-static-analysis

The complete `sonar { properties { ... } }` block on the root Gradle project after
this change. Existing properties are marked; everything else is new.

## Properties

| Property | Value | Status |
| --- | --- | --- |
| `sonar.projectKey` | `simonjamesrowe_simonrowe-dev-monorepo` | existing, unchanged |
| `sonar.organization` | `simonjamesrowe` | existing, unchanged |
| `sonar.host.url` | `https://sonarcloud.io` | existing, unchanged |
| `sonar.coverage.jacoco.xmlReportPaths` | `backend/build/reports/jacoco/test/jacocoTestReport.xml,`<br>`software-factory/build/reports/jacoco/test/jacocoTestReport.xml` | **extended** — second path added |
| `sonar.sources` | `frontend/src` | **new** |
| `sonar.tests` | `frontend/tests,frontend/src` | **new** |
| `sonar.exclusions` | `frontend/src/**/*.test.ts,frontend/src/**/*.test.tsx` | **new** |
| `sonar.test.inclusions` | `frontend/tests/**,`<br>`frontend/src/**/*.test.ts,`<br>`frontend/src/**/*.test.tsx` | **new** |
| `sonar.javascript.lcov.reportPaths` | `frontend/coverage/lcov.info` | **new** |
| `sonar.typescript.tsconfigPaths` | `frontend/tsconfig.app.json` | **new** |
| `sonar.coverage.exclusions` | the nine-entry mirror, below | **new** |

## `sonar.coverage.exclusions` — the nine-entry mirror

```
**/com/simonrowe/migration/**,
**/com/simonrowe/dataops/**,
**/com/simonrowe/embedding/**,
**/com/simonrowe/agents/scrapers/SitemapHtmlScraper*.java,
**/com/simonrowe/agents/scrapers/LumaApiScraper*.java,
**/com/simonrowe/media/ExternalImageDownloader*.java,
**/com/simonrowe/aggregation/AdminAggregationController*.java,
**/com/simonrowe/agents/ContentAggregationAgent*.java,
**/com/simonrowe/agents/WeeklyDigestAgent*.java
```

Mirrors `jacocoExcludes` in `backend/build.gradle.kts`, one entry for one entry,
translated from JaCoCo's class-file dialect into Sonar's source-file dialect.
See `data-model.md` §4 for the translation rules and why it is a literal list
rather than a generated one.

## Deliberately absent

| Property | Why absent |
| --- | --- |
| `sonar.qualitygate.wait` | Setting it makes the scanner block on and fail over gate status. The gate is advisory in this change; making it blocking is follow-up work requiring a baseline. |
| `sonar.eslint.reportPaths` | Sonar's own TypeScript rules already run over `frontend/src`; importing ESLint findings duplicates them. |
| `sonar.pullrequest.*` | Supplied automatically by the scanner's GitHub Actions autodetection. Setting them by hand is a way to get them wrong. |
| a frontend coverage threshold | No baseline exists to derive one from. |
| `software-factory` coverage exclusions | No existing exclusion list to mirror; inventing one would prejudge the follow-up floor decision. |

## Invariants

| ID | Invariant | Breach symptom |
| --- | --- | --- |
| P-1 | `sonar.projectKey` equals the key on the hosted account | analysis fails, "project not found" |
| P-2 | Every file under `sonar.sources` ∪ `sonar.tests` is indexed exactly once | scanner aborts: "File can't be indexed twice" |
| P-3 | `sonar.coverage.exclusions` describes the same code as `jacocoExcludes` | Sonar and JaCoCo backend coverage percentages disagree |
| P-4 | `sonar.qualitygate.wait` remains unset | gate becomes de facto blocking |
| P-5 | Every path-valued property resolves to an existing file at analysis time | that input silently contributes nothing |

## Why root-project placement is safe

The root project applies the `java` plugin but has no Java sources. Setting
`sonar.sources` on the root scopes the frontend tree to the root module only;
`backend` and `software-factory` each compute their own source and test roots from
their own `java` plugin conventions and are unaffected. The
`**/`-anchoring on `sonar.coverage.exclusions` exists precisely so those patterns
resolve correctly whichever module base directory the scanner applies them
against.

## Verification available offline

| What | How | What it proves |
| --- | --- | --- |
| Task graph resolves | `./gradlew sonar --dry-run` | the `sonar` task exists and configures |
| Compile tasks needed | same command shows `:sonar SKIPPED` alone | `classes testClasses` must be named explicitly |
| Paths resolve | assert each path-valued property exists after its producing task | the likeliest real failure — a wrong or missing path |

Not verifiable offline: whether the server *interprets* these properties as
intended. `-Dsonar.scanner.dumpToFile` does not work here — the scanner contacts
the server before honouring it (research R4). That half is the operator's
first-pull-request verification.
