# Contract: CI artifact hand-off

**Feature**: 033-sonarqube-static-analysis

This feature introduces no HTTP API. Its equivalent of an API contract is the
hand-off between CI jobs: the `sonar` job runs no tests, so everything it
analyses arrives as an artifact produced by an earlier job. A breach of this
contract does not fail the build — Sonar reports 0% coverage and carries on. That
is why each clause is stated as an assertion.

## Producers

### `backend` job

| Clause | Value |
| --- | --- |
| Produces | `backend/build/reports/jacoco/` (tree) |
| Via | `./gradlew :backend:jacocoTestReport` (existing step) |
| Uploads as | `jacoco-report` (existing step, unchanged) |
| Key file within | `test/jacocoTestReport.xml` |
| Removed by this change | the `SonarCloud analysis` step |

### `frontend` job

| Clause | Value |
| --- | --- |
| Produces | `frontend/coverage/` (tree) |
| Via | `npm run test:coverage` → `vitest run --coverage` (**new**) |
| Uploads as | `frontend-coverage` (**new**) |
| Key file within | `lcov.info` |
| Also added | `npm run lint`, blocking, no `--max-warnings` |

### `software-factory` job

| Clause | Value |
| --- | --- |
| Produces | `software-factory/build/reports/jacoco/` (tree) |
| Via | `./gradlew :software-factory:jacocoTestReport` (**new**) |
| Uploads as | `software-factory-jacoco-report` (**new**) |
| Key file within | `test/jacocoTestReport.xml` |

## Consumer

### `sonar` job

| Clause | Value |
| --- | --- |
| `needs` | `[backend, frontend, software-factory]` — all three, not two |
| Checkout | `actions/checkout@v4` with `fetch-depth: 0` |
| `continue-on-error` | `true` |
| Job-level `env` | `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` |
| Analysis step guard | `if: env.SONAR_TOKEN != ''` |
| Analysis command | `./gradlew classes testClasses sonar` |

### Download path reconstruction

`actions/download-artifact` restores an artifact's *contents*, not the path it was
uploaded from. Each download must therefore name the destination explicitly:

| Artifact | `path:` | Reconstructs |
| --- | --- | --- |
| `jacoco-report` | `backend/build/reports/jacoco` | `backend/build/reports/jacoco/test/jacocoTestReport.xml` |
| `software-factory-jacoco-report` | `software-factory/build/reports/jacoco` | `software-factory/build/reports/jacoco/test/jacocoTestReport.xml` |
| `frontend-coverage` | `frontend/coverage` | `frontend/coverage/lcov.info` |

## Assertions

Checked by the verification step in the `sonar` job, before the analysis runs, so
a breach is visible in the log even when the analysis itself is skipped.

| ID | Assertion | On breach |
| --- | --- | --- |
| A-1 | `backend/build/reports/jacoco/test/jacocoTestReport.xml` exists and is non-empty | backend coverage reports as 0% |
| A-2 | `software-factory/build/reports/jacoco/test/jacocoTestReport.xml` exists and is non-empty | software-factory coverage reports as 0% |
| A-3 | `frontend/coverage/lcov.info` exists and is non-empty | frontend coverage reports as 0% |
| A-4 | the `sonar` job's clone has full history (`git rev-parse --is-shallow-repository` is `false`) | new-code attribution silently wrong |
| A-5 | the analysis step is skipped, not failed, while `SONAR_TOKEN` is unset | ten wasted minutes and a red job per PR |
| A-6 | the `sonar` job's outcome never changes the workflow conclusion | gate becomes de facto blocking |

## Non-clauses

Stated so they are not assumed:

- The `sonar` job **does not** run any test suite. Coverage is only ever as fresh
  as the artifacts.
- The `sonar` job **does not** set `sonar.qualitygate.wait`. It therefore cannot
  block on, or fail over, gate status.
- No artifact retention or size setting is specified; repository defaults apply.
