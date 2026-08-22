# Phase 1 Data Model: SonarQube Cloud static analysis and the PR quality loop

**Feature**: 033-sonarqube-static-analysis
**Date**: 2026-08-21

This feature persists nothing. There is no MongoDB collection, no Mongock change
unit, no entity. What it *does* have is a set of configuration objects and
artefacts with real shapes, real producers and consumers, and a real set of
invariants that break the feature when violated. That is what this document
models.

---

## 1. Analysis project

The single hosted SonarQube Cloud project representing the whole monorepo.

| Field | Value | Owner | Notes |
| --- | --- | --- | --- |
| `sonar.projectKey` | `simonjamesrowe_simonrowe-dev-monorepo` | `build.gradle.kts` | Already present since commit #2, never used |
| `sonar.organization` | `simonjamesrowe` | `build.gradle.kts` | Must exist on SonarQube Cloud |
| `sonar.host.url` | `https://sonarcloud.io` | `build.gradle.kts` | |
| credential | `SONAR_TOKEN` | GitHub repository secret | Does not exist yet |

**Invariant PK-1**: the key in `build.gradle.kts` must equal the key on the
hosted account. On mismatch the analysis fails with "project not found". The
account is authoritative — if the key is taken, the code changes to match, not
the reverse.

**Invariant PK-2**: the project's analysis method must be **CI-based**, with
Automatic Analysis **off**. With both on, the two compete and the surviving
result carries no coverage data. This is unobservable from the repository — it is
account state, checked only by the operator.

---

## 2. Analysed module set

Three code trees roll into the one project.

| Module | Language | Source root | Test root | Analysed as |
| --- | --- | --- | --- | --- |
| `backend` | Java 21 | `backend/src/main/java` | `backend/src/test/java` | Gradle subproject (java plugin conventions) |
| `software-factory` | Java 21 | `software-factory/src/main/java` | `software-factory/src/test/java` | Gradle subproject (java plugin conventions) |
| frontend | TypeScript | `frontend/src` | `frontend/tests` **and** the nine `frontend/src/**/*.test.ts(x)` files | Root project, via explicit `sonar.sources` / `sonar.tests` |

**Invariant MS-1**: the root Gradle project applies the `java` plugin but has no
Java sources. Setting `sonar.sources` on the root to `frontend/src` is therefore
safe — it does not displace either subproject's own contribution, because each
subproject computes its own source roots from its own `java` plugin conventions.

**Invariant MS-2 (the indexing invariant)**: no file may be reachable as both
main source and test source. `sonar.sources` and `sonar.tests` deliberately
overlap on `frontend/src`, so the filters are what keep the sides disjoint:

```
sonar.sources         = frontend/src
sonar.tests           = frontend/tests,frontend/src
sonar.exclusions      = frontend/src/**/*.test.ts,frontend/src/**/*.test.tsx
sonar.test.inclusions = frontend/tests/**,
                        frontend/src/**/*.test.ts,
                        frontend/src/**/*.test.tsx
```

Removing either filter breaks it: without `sonar.exclusions` the nine co-located
tests are indexed twice and the scanner aborts; without `sonar.test.inclusions`
they are indexed nowhere and lose test-rule analysis.

**Invariant MS-3**: `frontend/src/test/setup.ts` is Vitest harness, not a test.
It matches no `*.test.*` pattern, so it stays main source. Deliberate — do not
add a pattern that would catch it.

---

## 3. Coverage report artefacts

Three reports, three producers, one consumer.

| Artefact | Path | Format | Produced by | CI artifact name | Status |
| --- | --- | --- | --- | --- | --- |
| Backend coverage | `backend/build/reports/jacoco/test/jacocoTestReport.xml` | JaCoCo XML | `:backend:jacocoTestReport` | `jacoco-report` | exists today |
| Software-factory coverage | `software-factory/build/reports/jacoco/test/jacocoTestReport.xml` | JaCoCo XML | `:software-factory:jacocoTestReport` | `software-factory-jacoco-report` | **new** |
| Frontend coverage | `frontend/coverage/lcov.info` | LCOV | `npm run test:coverage` | `frontend-coverage` | **new** |

Consumed by the `sonar` job via:

```
sonar.coverage.jacoco.xmlReportPaths = backend/build/reports/jacoco/test/jacocoTestReport.xml,
                                       software-factory/build/reports/jacoco/test/jacocoTestReport.xml
sonar.javascript.lcov.reportPaths    = frontend/coverage/lcov.info
```

**Invariant CR-1**: every path above must exist in the `sonar` job's workspace at
the moment `sonar` runs. Because the `sonar` job runs no tests, existence depends
entirely on artifact download reconstructing the exact path. A missing report is
**not** an error — Sonar reports 0% and carries on. This is the feature's single
most likely silent failure, and the reason the verification step is an explicit
path-existence assertion (research R4).

**Invariant CR-2**: `actions/download-artifact` restores contents, not the
original path prefix. Each download needs an explicit `path:` that rebuilds the
expected location.

**Invariant CR-3**: no threshold attaches to the frontend or `software-factory`
reports. Backend keeps its existing 0.78 floor, enforced by
`:backend:jacocoTestCoverageVerification`, untouched by this feature.

---

## 4. Coverage exclusion mirror

Two lists that must describe the same code in two different dialects.

**Source of truth** — `backend/build.gradle.kts`, `jacocoExcludes`, nine entries,
expressed as **class-file** patterns relative to the class output root:

| # | JaCoCo class pattern | Sonar source pattern |
| --- | --- | --- |
| 1 | `com/simonrowe/migration/**` | `**/com/simonrowe/migration/**` |
| 2 | `com/simonrowe/dataops/**` | `**/com/simonrowe/dataops/**` |
| 3 | `com/simonrowe/embedding/**` | `**/com/simonrowe/embedding/**` |
| 4 | `com/simonrowe/agents/scrapers/SitemapHtmlScraper*` | `**/com/simonrowe/agents/scrapers/SitemapHtmlScraper*.java` |
| 5 | `com/simonrowe/agents/scrapers/LumaApiScraper*` | `**/com/simonrowe/agents/scrapers/LumaApiScraper*.java` |
| 6 | `com/simonrowe/media/ExternalImageDownloader*` | `**/com/simonrowe/media/ExternalImageDownloader*.java` |
| 7 | `com/simonrowe/aggregation/AdminAggregationController*` | `**/com/simonrowe/aggregation/AdminAggregationController*.java` |
| 8 | `com/simonrowe/agents/ContentAggregationAgent*` | `**/com/simonrowe/agents/ContentAggregationAgent*.java` |
| 9 | `com/simonrowe/agents/WeeklyDigestAgent*` | `**/com/simonrowe/agents/WeeklyDigestAgent*.java` |

**Dialect differences that make this a translation, not a copy**:

- JaCoCo matches compiled `.class` files; Sonar matches source files. Hence the
  `.java` suffix on the single-type patterns.
- JaCoCo patterns are relative to the class output root; Sonar's are relative to a
  module base directory that differs between the root project and each subproject.
  Hence the `**/` anchor, which makes them basedir-independent.
- A `Foo*` class pattern also catches `Foo$Inner.class`, whose source lives in
  `Foo.java` — so the class dialect is strictly more expressive. In these nine
  cases the mapping is exact, but it is not exact in general, which is why this
  stays a hand-written literal list rather than a generated one (research R7).

**Invariant CE-1**: the two lists must describe the same code. Drift presents as
the Sonar coverage percentage and the JaCoCo percentage disagreeing over the same
backend — the documented failure-mode symptom. There is no automated check; the
runbook carries the obligation (FR-020).

**Invariant CE-2**: `software-factory` contributes **no** exclusions. Its coverage
is measured over everything, because there is no existing exclusion list to
mirror and inventing one would prejudge the follow-up floor decision.

---

## 5. CI job graph

```
pull_request → ┌─ backend ───────────┐
               ├─ frontend ──────────┼──→ sonar  (continue-on-error: true)
               └─ software-factory ──┘
```

| Job | Existing? | Changes |
| --- | --- | --- |
| `backend` | yes | Sonar step **removed** (moves to the new job) |
| `frontend` | yes | `npm run lint` step added (blocking); `npm run test:coverage` replaces/augments `npm test`; coverage artifact uploaded |
| `software-factory` | yes | `jacocoTestReport` step added; coverage artifact uploaded |
| `sonar` | **new** | `needs: [backend, frontend, software-factory]`, `fetch-depth: 0`, three artifact downloads, `./gradlew classes testClasses sonar` guarded by job-level `SONAR_TOKEN` |

**Invariant JG-1**: `needs` must list all three producers. The design said
`[backend, frontend]`; adding `software-factory` coverage makes that stale — a
`sonar` job that does not wait for `software-factory` races its artifact
(research R10).

**Invariant JG-2**: the `sonar` job must be incapable of failing the run. Two
independent mechanisms: the `if:` guard skips the step while no secret exists, and
`continue-on-error: true` absorbs any failure once it does. Belt and braces
because a tokenless run costs ten minutes and then fails hard (research R2).

**Invariant JG-3**: `sonar.qualitygate.wait` must **not** be set. Setting it makes
the scanner block on and then fail over gate status, which is the blocking
behaviour this change explicitly defers.

---

## 6. Signal (loop procedure)

The three independent verdicts on a pull request. Not persisted — read live.

| Signal | Identity | Read | Terminal states | Misreading trap |
| --- | --- | --- | --- | --- |
| CI checks | check-run names | `gh pr checks --watch` | all required green / a required check red | `evaluate` and `sonar` are advisory; `evaluate` is `paths:`-filtered so it is often *absent* rather than green |
| Reviewer verdict | `simonrowe-code-reviewer[bot]` | `gh api repos/{owner}/{repo}/issues/{pr}/comments` | comment present / comment absent | reading `/pulls/{pr}/reviews`, which is empty even on success; and reading silence as approval when it means failure |
| Analysis result | project key + PR number | `api/issues/search?pullRequest={pr}&resolved=false`, `api/qualitygates/project_status` | findings list + gate status | assuming a credential is needed (public project: try anonymous first); treating pre-existing findings as in scope |

**Invariant SG-1**: silence from the reviewer is failure, never approval. It hands
off to the existing `code-review-triage` skill.

**Invariant SG-2**: only findings attributable to the pull request's new code are
in scope for triage. Pre-existing debt is explicitly another piece of work.

**Invariant SG-3**: a finding is either fixed or declined **in the pull request**
with a stated reason. Marking it resolved in the Sonar UI is forbidden — it hides
the decision from the diff and from review.

---

## 7. Loop state (loop procedure)

Ephemeral, per pull request, held in the working session.

| Field | Type | Constraint |
| --- | --- | --- |
| PR URL | string | assigned once, never a draft |
| iteration | integer | bounded at ~3 fix-and-push rounds, then stop and report |
| findings addressed | list | each with what changed |
| findings declined | list | each with a reason stated in the PR |
| gate status | pass / fail / unknown | `unknown` while no account exists |

**Invariant LS-1**: the loop is bounded. On exhausting the bound it stops and
reports what was tried and what still fails — it does not keep pushing.

**Invariant LS-2**: the reviewer re-reviews **per pushed commit** (the Temporal
workflow id embeds the head SHA) but posts **one comment per pull request** (since
#103). So iteration count cannot be inferred from comment count.

---

## Entity relationship summary

```
Analysis project (1)
├── Analysed module (3) ── backend, software-factory, frontend
├── Coverage report (3) ─── one per module, path-coupled to CI artifacts
│   └── Coverage exclusion mirror (9 entries) ── must equal backend jacocoExcludes
└── per pull request:
    ├── Quality gate result (1) ── advisory, non-required check
    └── Finding (0..n) ─────────── new-code only in scope

Pull request
└── Signal (3) ── CI checks, reviewer verdict, analysis result
    └── Loop state (1) ── bounded at ~3 iterations
```
