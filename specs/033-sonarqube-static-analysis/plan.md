# Implementation Plan: SonarQube Cloud static analysis and the PR quality loop

**Branch**: `simonrowe/sonarqube-static-analysis` (feature `033-sonarqube-static-analysis`) | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/033-sonarqube-static-analysis/spec.md`

**Source design**: `docs/superpowers/specs/2026-08-21-static-analysis-design.md`

## Summary

Activate the static analysis this repository has been configured for but has never
run, and give the post-pull-request loop a named owner.

The primary requirement is that opening a pull request produces a real
static-analysis result covering backend Java, frontend TypeScript and
`software-factory` Java — where today the Sonar step is unreachable dead code, the
frontend linter has never executed in CI, and `software-factory` has no coverage
measurement at all.

The technical approach is entirely build and CI configuration plus documentation.
No application code changes. Five moves:

1. Move `SONAR_TOKEN` to job-level `env:` so the existing guard can evaluate true
   (it currently cannot — a step's `if:` is evaluated before its own `env:`).
2. Extract analysis into a new `sonar` job with `fetch-depth: 0`, depending on all
   three build jobs, consuming their coverage artifacts and running
   `./gradlew classes testClasses sonar` — never re-running a test suite.
3. Add `@vitest/coverage-v8` and a `test:coverage` script; add `npm run lint` as a
   blocking CI step (verified: it exits 0 today).
4. Add the `jacoco` plugin to `software-factory` — report only, no floor.
5. Extend the root `sonar {}` block with the frontend source/test split, both
   LCOV and JaCoCo report paths, and a nine-entry coverage-exclusion mirror of
   `backend`'s `jacocoExcludes`.

Plus a runbook with the five failure modes and an operator checklist for the five
manual steps, and a `pr-review-loop` skill in the separate `agent-setup`
repository.

The gate is advisory throughout. Two independent mechanisms guarantee the
analysis cannot fail CI: the token guard, and `continue-on-error: true` on the
`sonar` job. This matters more than it sounds — a tokenless `sonar` run was
measured at **9m53s followed by a hard failure**, so a guard that fails open is
expensive, not harmless.

## Technical Context

**Language/Version**: No application language. Gradle Kotlin DSL (Gradle 8.13),
GitHub Actions workflow YAML, Markdown. Touches Java 21 and TypeScript 5.7
build configuration without touching their source.

**Primary Dependencies**:

| Dependency | Version | Status |
| --- | --- | --- |
| `org.sonarqube` Gradle plugin | `6.0.1.5171` | already in `gradle/libs.versions.toml`, already applied at root, never executed |
| JaCoCo | `0.8.12` | already in the catalog; plugin newly applied to `software-factory` |
| `@vitest/coverage-v8` | `^3.0.0` | **new** — must match the installed `vitest ^3.0.0` major |
| Checkstyle | `10.21.4` | untouched |
| SonarQube Cloud | hosted | **account does not exist yet** — operator action |

**Storage**: N/A — this feature persists nothing. No MongoDB collection, no
Mongock change unit.

**Testing**: Verification is offline assertion, not a new test suite. Six checks:
`./gradlew sonar --dry-run` (task graph), `npm run test:coverage` + non-empty
`lcov.info`, `:software-factory:jacocoTestReport` + read the actual percentage,
`npm run lint` exit code, path-existence for every path-valued Sonar property, and
`./gradlew classes testClasses`. See [quickstart.md](./quickstart.md).

**Target Platform**: GitHub Actions `ubuntu-latest` for CI; SonarQube Cloud for
the analysis. Deliberately **not** the production Raspberry Pi.

**Project Type**: Build/CI infrastructure change across an existing three-module
monorepo (`backend`, `software-factory`, `frontend`), plus one documentation
artifact in a second repository.

**Performance Goals**: The `sonar` job must not duplicate the slowest work in CI.
It runs `classes testClasses` (compile only, no tests) and consumes coverage as
artifacts. Net CI wall-clock cost is one extra job that compiles and analyses,
running in parallel-tail after the three existing jobs — not a second test run.
The frontend job gains coverage instrumentation (V8 provider, no transform, so
negligible) and a lint step (seconds).

**Constraints**:

- **Must be a no-op until the operator adds the secret.** This change merges
  before the SonarQube Cloud account exists, so it must alter no CI outcome at
  merge time (SC-001).
- **Must be incapable of failing CI on gate status** (FR-007, SC-007).
- **A tokenless `sonar` invocation costs ~10 minutes and fails** — measured. The
  guard is load-bearing, not decorative.
- **No credential value may ever be echoed, pasted into chat, or written to a
  file** (FR-025, SC-011).
- **No new thresholds.** No frontend coverage floor, no `software-factory` floor,
  no ESLint cleanup, no `--max-warnings 0`.
- Backend's existing 0.78 JaCoCo floor and Checkstyle `maxWarnings = 0` are
  untouched.

**Scale/Scope**: 6 files changed in this repository, 1 file created in
`agent-setup`, ~9 tasks. Analysed surface: 101 + 33 `software-factory` Java files,
the existing `backend` tree, `frontend/src` plus 67 frontend test files. Two
coordinated pull requests.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Checked against `.specify/memory/constitution.md` v1.11.0.

### Principle III — Quality Gates (NON-NEGOTIABLE)

This feature exists to **restore** compliance with this principle, which the
repository currently violates.

| Constitutional requirement | Before | After |
| --- | --- | --- |
| "SonarQube MUST run static analysis on every PR" | **VIOLATED** — configured since commit #2, never executed once | Compliant once the operator completes the checklist; mechanism in place and inert until then |
| "Code style MUST conform to the Google Java Style Guide" | Compliant (backend + software-factory Checkstyle) | Unchanged |
| "JaCoCo MUST enforce minimum test coverage thresholds" | Partially — `backend` enforces 0.78; `software-factory` measures nothing | `software-factory` now **measured**; threshold deferred (see Complexity Tracking) |
| "CycloneDX BOM MUST be generated" | Compliant | Unchanged |
| "Frontend tests MUST exist for critical user journeys" | Compliant (67 test files) | Now **measured** for the first time |
| "Manual overrides of quality gates are prohibited" | — | Honoured and reinforced: FR-036 forbids resolving a finding in the Sonar UI; it must be fixed or declined in the pull request |

**Note on the advisory gate**: leaving the Sonar gate non-required is *not* a
manual override of a quality gate — it is declining to add a new blocking gate
until a baseline exists. No existing gate is weakened. The pre-existing
Checkstyle, test and coverage gates all remain blocking.

### Principle V — Simplicity & Incremental Delivery

Honoured, and the source of several deliberate omissions.

- "Add complexity only when a concrete requirement demands it" — no thresholds
  invented before measurement; no ESLint cleanup bundled in; no generated
  exclusion list where a literal one is correct.
- "Features MUST be delivered as independently testable increments" — the five
  platform moves are independently verifiable (quickstart §1–§8) and the two
  repository halves are independently mergeable.
- "No premature abstractions" — the nine-entry coverage exclusion mirror is a
  literal list, not a generator. Research R7 records why a generator would be
  subtly wrong.

### Principle IV — Observability & Operability

"Debugging MUST NOT require SSH access or log tailing on hosts." Honoured: the
runbook makes all five failure modes diagnosable from pull-request and CI surfaces
plus the SonarQube Cloud UI. No host access needed.

### Principle IX — Shell Scripting Standards

No new shell scripts. The verification snippets in `quickstart.md` are inline
documentation commands, not project scripts, so the `#!/usr/bin/env bash` /
`set -euo pipefail` / `SCRIPT_DIR` conventions do not attach. **If any of them is
later promoted to a file under `scripts/`, it must adopt those conventions.**

### Technology Stack Constraints

The stack table already names `SonarQube — PR-level analysis` and
`JaCoCo — Enforced thresholds`. No new technology is introduced beyond
`@vitest/coverage-v8`, which is the coverage provider for the already-mandated
Vitest and adds no new concept to the stack.

### Development Workflow

"PRs MUST pass all quality gates (tests, coverage, style, static analysis) before
merge" — this feature is what makes the "static analysis" clause achievable.
Commits follow conventional prefixes; no Jira reference in this org; no
attribution to the assistant.

### Gate result

**PASS.** No violations. One deferral requiring justification, recorded in
Complexity Tracking.

### Post-Phase-1 re-check

**PASS, unchanged.** Phase 1 introduced three corrections to the source design —
`needs:` gaining `software-factory`, the frontend source/test overlap filters, and
class-to-source pattern translation for the exclusion mirror — none of which
touches a constitutional constraint. All three make the design *more* correct
against Principle III (the analysis measures what it claims to measure) without
adding technology or abstraction.

## Project Structure

### Documentation (this feature)

```text
specs/033-sonarqube-static-analysis/
├── spec.md                          # Phase -1: 5 user stories, FR-001..FR-041, SC-001..SC-011
├── checklists/
│   └── requirements.md              # Spec quality validation — all items pass
├── research.md                      # Phase 0: R1..R13, all unknowns resolved
├── data-model.md                    # Phase 1: configuration objects and their invariants
├── contracts/
│   ├── ci-artifact-contract.md      # Phase 1: producer/consumer hand-off, assertions A-1..A-6
│   ├── sonar-properties.md          # Phase 1: the exact property set, invariants P-1..P-5
│   └── signal-reads.md              # Phase 1: the three signal reads and their traps
├── quickstart.md                    # Phase 1: 8 offline verification steps
└── tasks.md                         # Phase 2 output (/speckit.tasks — not created here)
```

### Source Code (repository root)

Files touched, all configuration or documentation. No application source changes.

```text
.github/workflows/
└── ci.yml                     # MODIFY  remove Sonar step from `backend`;
                              #         add lint + coverage + upload to `frontend`;
                              #         add jacocoTestReport + upload to `software-factory`;
                              #         add new `sonar` job

build.gradle.kts              # MODIFY  extend root `sonar {}` block:
                              #         frontend sources/tests/exclusions/inclusions,
                              #         lcov + tsconfig paths,
                              #         software-factory jacoco XML path,
                              #         nine-entry coverage-exclusion mirror

software-factory/
└── build.gradle.kts          # MODIFY  apply `jacoco` plugin;
                              #         jacoco toolVersion from the catalog;
                              #         jacocoTestReport with XML enabled.
                              #         NO jacocoTestCoverageVerification,
                              #         NO tasks.check wiring

frontend/
├── package.json              # MODIFY  add devDependency @vitest/coverage-v8 ^3.0.0;
                              #         add script test:coverage
├── package-lock.json         # MODIFY  regenerated by npm install
└── vite.config.ts            # MODIFY  add coverage block to the existing test: section

docs/runbooks/
└── static-analysis.md        # CREATE  what runs where, how to read the gate,
                              #         operator checklist, 5 failure modes,
                              #         the exclusion-mirror maintenance obligation

CLAUDE.md                     # MODIFY  one line in Recent Changes (repo convention)
```

Unchanged and deliberately so: `backend/build.gradle.kts` (its `jacocoExcludes`
is the source of truth the mirror copies — editing it would move the target),
`gradle/libs.versions.toml` (both plugin versions already present),
`frontend/eslint.config.js` (already ignores `coverage/`), `.gitignore` (already
ignores `coverage/`), `.github/workflows/evals.yml` and `publish.yml`.

### Second repository

```text
~/workspace/simonjamesrowe/agent-setup/components/skills/
└── pr-review-loop/
    └── SKILL.md              # CREATE  single-file skill, shaped on dependency-cve-fix
```

**Structure Decision**: Three real directories carry this change —
`.github/workflows/` for the job graph, the Gradle build files at the root and in
`software-factory/`, and `frontend/` for the coverage tooling — plus
`docs/runbooks/` for the operator-facing half. The frontend analysis
configuration lives in the **root** `build.gradle.kts`, not in a frontend Gradle
module, because the frontend has no Gradle module and the root project has no Java
sources of its own to displace (data-model §2, invariant MS-1).

The skill lands in `agent-setup`, not in this repository's `.claude/skills/`,
because `CLAUDE.md` states skills are maintained there and all 15 existing skills
are. Putting it here would make it the only one outside `agent-setup` and it would
never be provisioned to `~/.claude/skills/`. Consequence: **two coordinated pull
requests, merged as a pair.** The monorepo half is independently safe to merge
first — it is inert until the secret exists.

## Implementation Sequence

Ordered by dependency, not by importance. Each step is independently verifiable.

| # | Step | Verifies with | Blocks |
| --- | --- | --- | --- |
| 1 | `software-factory` JaCoCo (report only) | quickstart §3 — report exists, percentage read | 5, 6 |
| 2 | Frontend coverage tooling | quickstart §1 — `lcov.info` non-empty | 5, 6 |
| 3 | Root `sonar {}` properties | quickstart §5, §7 — task graph resolves, 9==9 | 6 |
| 4 | Frontend lint into CI (blocking) | quickstart §2 — exit 0 confirmed | — |
| 5 | New `sonar` job + move `SONAR_TOKEN` to job-level `env:` | The pull request itself: job runs, step skips, nothing fails | — |
| 6 | Path-existence assertion over all Sonar property paths | quickstart §6 | — |
| 7 | Runbook + operator checklist | Readable end-to-end; 5 failure modes diagnosable | — |
| 8 | `pr-review-loop` skill in `agent-setup` | Second pull request | — |
| 9 | Report to operator: `software-factory` %, lint warning count, checklist | — | — |

Step 1 and 2 come first because steps 5 and 6 assert on the artifacts they
produce. Step 3 before 6 for the same reason. Step 4 is independent. Steps 7–9
are documentation and reporting.

## Corrections to the source design

Three, found during Phase 0/1 and carried into this plan rather than followed
silently. All three are recorded with evidence in `research.md`.

| # | Design said | Actually | Why it matters |
| --- | --- | --- | --- |
| 1 | `sonar` job `needs: [backend, frontend]` | Must be `[backend, frontend, software-factory]` | The design's own item 5 adds `software-factory` coverage; without the dependency the `sonar` job races that artifact and the coverage is intermittently absent. Self-inconsistency in the design, not a disagreement with it. |
| 2 | "67 test files under `frontend/tests/`", so `sonar.tests = frontend/tests` | 58 under `frontend/tests`, **9 co-located** under `frontend/src` | The simple split indexes those nine as production code — production rules applied to test code, analysed surface inflated, coverage denominator depressed. Fixed with the documented overlap-plus-filters pattern (research R6). |
| 3 | "`sonar.coverage.exclusions` mirror `jacocoExcludes`" | The two use different pattern dialects | JaCoCo matches class files relative to the class output root; Sonar matches source files relative to a module basedir. A literal copy would silently match nothing — the worst outcome, since it looks configured. Translated and `**/`-anchored (research R7). |

One measured fact the design did not anticipate: a tokenless `sonar` run takes
**9m53s and then fails hard** (research R2). This is why `continue-on-error: true`
is added to the `sonar` job on top of the token guard rather than relying on the
guard alone.

## Complexity Tracking

> Deferrals requiring justification against Principle III.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| No `software-factory` coverage floor, where Principle III says "JaCoCo MUST enforce minimum test coverage thresholds" | The module's coverage has never been measured. This change produces the first measurement and reports it, so a floor can be set from evidence in immediate follow-up. | Setting a floor now means guessing. Guess high → the build fails on day one on a module nobody has touched, and the floor gets lowered under pressure, which is worse than no floor. Guess low → a floor beneath actual coverage, which ratchets nothing and gives false assurance. Both are worse than one change measuring and the next enforcing. 33 test classes over 101 main classes suggests a real number exists to floor. |
| Sonar quality gate left non-required | No baseline exists. The first analysis of `main` will surface an unknown volume of accumulated debt; making the gate blocking before knowing that volume could block every pull request on pre-existing issues. | Making it blocking immediately conflates "this change is bad" with "this repository has history", and the predictable response is to make the gate non-required again — a gate that has been switched off once carries less authority than one introduced deliberately after a baseline. The debt sweep is explicitly separate work with its own plan. |
| No frontend coverage threshold | Frontend coverage has never been measured either. Same reasoning as `software-factory`. | Identical to the above. |
| No `--max-warnings 0` on the lint step | `npm run lint` exits 0 today with 5 pre-existing `react-refresh/only-export-components` warnings. | Adding `--max-warnings 0` turns those five into a red build, i.e. it silently expands this change into the ESLint cleanup that FR-013 puts out of scope. The five are all one rule and all fixable by moving a context or constant to its own file — a good follow-up change, which should add `--max-warnings 0` as its final step. |
