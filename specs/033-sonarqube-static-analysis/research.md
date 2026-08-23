# Phase 0 Research: SonarQube Cloud static analysis and the PR quality loop

**Feature**: 033-sonarqube-static-analysis
**Date**: 2026-08-21

Everything below was established by reading or running the actual repository, not
inferred. Where a finding contradicts the source design document, that is called
out explicitly.

---

## R1. Why the Sonar step has never run

**Decision**: Move `SONAR_TOKEN` from the step's `env:` block to job-level `env:`.

**Evidence** — `.github/workflows/ci.yml`, in the `backend` job:

```yaml
      - name: SonarCloud analysis
        if: env.SONAR_TOKEN != ''
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: ./gradlew sonar
```

A step's `if:` is evaluated *before* that step's own `env:` block is applied, and
neither the job nor the workflow declares `env:`. So `env.SONAR_TOKEN` is always
the empty string and the condition is always false. Confirmed: no `SONAR_TOKEN`
secret exists on the repository either, so even a correct guard would currently
skip.

**Rationale**: Job-level `env:` *is* in scope for a step's `if:`. This is the
minimal change that makes the intended behaviour work while preserving it —
skip silently until the secret exists.

**Alternatives considered**:

- `if: ${{ secrets.SONAR_TOKEN != '' }}` — rejected. `secrets` is not available
  in a step-level `if:` context in the way this implies, and it reads as if it
  should work, which is exactly the trap being fixed.
- Drop the guard and always run — rejected outright by R2 below.

---

## R2. A tokenless `sonar` run is expensive and fails hard — the guard is load-bearing

**Finding**: Running `./gradlew sonar` in this repository with no token took
**9 minutes 53 seconds** and then failed:

```
> Task :sonar FAILED
> The analysis has failed! See the logs for more details.
BUILD FAILED in 9m 53s
```

The project does not exist on SonarQube Cloud yet, so the scanner downloads its
engine and then fails resolving the project. The Gradle problems report carries
no useful detail.

**Consequence for the design**: this is not a cheap no-op. If the guard were
removed or broken *open*, every pull request would burn ten minutes and fail. So:

**Decision**: Keep the guard, **and** additionally set `continue-on-error: true`
on the `sonar` job.

**Rationale**: Belt and braces for FR-007 and SC-007. The guard prevents the
cost; `continue-on-error` guarantees that even once the token exists, a scanner
failure, a network failure or a future `sonar.qualitygate.wait` cannot fail CI.
This also matches the source design's own signals table, which lists the `sonar`
job as advisory alongside `evaluate`.

**Cross-check**: `evaluate` in `.github/workflows/evals.yml:43` is already
`continue-on-error: true`, so this is the established pattern for advisory jobs
in this repository, not a new one.

---

## R3. `./gradlew sonar` pulls in no compile tasks — they must be named explicitly

**Finding**: `./gradlew sonar --dry-run` resolves cleanly but shows exactly one
task:

```
:sonar SKIPPED
BUILD SUCCESSFUL in 4s
```

No `compileJava`, no `compileTestJava`, no `classes`.

**Decision**: The CI command is `./gradlew classes testClasses sonar`.

**Rationale**: sonar-java analyses **bytecode**, not source. Without compiled
classes the Java analysis is either skipped or badly degraded, and the scanner
warns rather than failing — a quiet-wrong outcome. Naming `classes testClasses`
compiles both source sets across all subprojects without running any test.

**Alternatives considered**:

- `./gradlew build sonar` — rejected. Runs the whole Testcontainers suite again,
  duplicating the slowest work in CI, which is the thing the separate job exists
  to avoid.
- Rely on the plugin adding compile dependencies — rejected. Measured above: it
  does not, at plugin version `6.0.1.5171`.

---

## R4. Effective Sonar properties cannot be dumped offline here

**Finding**: `./gradlew sonar -Dsonar.scanner.dumpToFile=/tmp/props.txt` did not
write the file — it failed the same way as R2 and left nothing behind. The
scanner reaches the server before honouring the dump.

**Decision**: Verify the property wiring by a different, cheaper assertion —
**every filesystem path referenced by the `sonar` block must exist after the
producing task has run.** A shell check over the literal paths, run after the
report-producing tasks.

**Rationale**: The property *values* are literals in `build.gradle.kts`; there is
no computation to get wrong. The failure mode that actually bites is a path that
does not exist or a report that was never generated — which the existence check
catches directly, offline, in seconds. The design's stated intent ("assert the
generated properties contain the frontend and `software-factory` paths") is met in
substance.

**Alternatives considered**:

- A custom Gradle task printing the computed property map — rejected. Requires
  `org.sonarqube.gradle` internals (`SonarPropertyComputer`) and adds
  test-only code to the production build for a check that a path existence test
  already covers.
- Accept "unverifiable until the account exists" — rejected. It is verifiable in
  the way that matters.

**Limitation recorded**: property *semantics* on the server side (does Sonar
interpret these paths as intended) genuinely cannot be verified until the account
exists. This is the operator's first-pull-request verification.

---

## R5. Frontend lint passes today — so the step lands blocking

**Finding**: `npm run lint` (i.e. `eslint .`) **exits 0**:

```
✖ 5 problems (0 errors, 5 warnings)
```

All five are `react-refresh/only-export-components`, configured as `warn` in
`frontend/eslint.config.js`:

| File | Line |
| --- | --- |
| `src/components/skills/SkillRatingBar.tsx` | 11 |
| `src/components/tour/TourProvider.tsx` | 71 |
| `src/contexts/ChatContext.tsx` | 74 |
| `src/contexts/ThemeContext.tsx` | 78 |
| `src/hooks/useDrawer.tsx` | 50 |

**Decision**: Add `npm run lint` as a **blocking** step in the frontend job, with
**no** `--max-warnings` flag.

**Rationale**: FR-012's condition is satisfied — the existing code passes cleanly
(zero errors, exit 0). Adding `--max-warnings 0` would convert five pre-existing
warnings into a red build, i.e. exactly the ESLint cleanup this change declares
out of scope (FR-013).

**Follow-up noted, not done here**: those five warnings are all the same rule and
all fixable by moving a context or constant to its own file. That is a candidate
for a later change, with `--max-warnings 0` added at the end of it.

---

## R6. The frontend test layout is not what the design assumed

**Finding**: the design says "67 test files under `frontend/tests/`". Measured:

| Location | Count |
| --- | --- |
| `frontend/tests/**` | 58 |
| `frontend/src/**/*.test.ts(x)` | 9 |

The nine co-located ones are:

```
src/pages/McpPage.test.tsx
src/components/home/HeroSection.test.tsx
src/components/chat/chatStreamReducer.test.ts
src/components/chat/ChatPanel.test.tsx
src/components/chat/widgets/ChatWidgetRegistry.test.tsx
src/components/chat/widgets/ChatWidgets.test.tsx
src/components/mcp/ConnectInstructions.test.tsx
src/components/mcp/ToolCard.test.tsx
src/components/blog/BlogNarration.test.tsx
```

The design's `sonar.sources = frontend/src` + `sonar.tests = frontend/tests`
split would therefore index those nine as **production source**: inflating the
analysed surface, applying production rules to test code, and depressing the
reported coverage denominator.

**Decision**: Use the documented Sonar pattern for co-located tests — overlap the
two roots and disambiguate with filters:

```
sonar.sources          = frontend/src
sonar.tests            = frontend/tests,frontend/src
sonar.exclusions       = frontend/src/**/*.test.ts,frontend/src/**/*.test.tsx
sonar.test.inclusions  = frontend/tests/**,frontend/src/**/*.test.ts,frontend/src/**/*.test.tsx
```

`sonar.exclusions` removes the nine from the main index; `sonar.test.inclusions`
admits them to the test index. Every file lands on exactly one side, so the
scanner's "File can't be indexed twice" error cannot occur.

**Rationale**: This is Sonar's own recommended handling for projects whose tests
live beside their source. Also note `frontend/src/test/setup.ts` is Vitest
harness, not a test file — it is not matched by `*.test.*`, and it is correctly
left as source rather than being special-cased.

**Alternatives considered**:

- `sonar.exclusions` only, without adding `frontend/src` to `sonar.tests` —
  rejected. It stops the misclassification but drops the nine files from analysis
  entirely, so test-code rules never run on them.
- Move the nine files into `frontend/tests/` — rejected. A 9-file refactor
  unrelated to static analysis, touching working tests, for a configuration
  problem that configuration solves.

**Also noted**: `frontend/tsconfig.app.json` has `"include": ["src"]`, so
`sonar.typescript.tsconfigPaths = frontend/tsconfig.app.json` resolves types for
`frontend/src` but not for `frontend/tests`. Type-aware TS rules will be weaker
over the 58 files in `frontend/tests`. Accepted: adding a second tsconfig for the
test tree is scope creep, and the rules still run, just with less type
information.

---

## R7. Coverage exclusions must be restated as file paths, not class paths

**Finding**: `backend/build.gradle.kts` excludes nine entries from its JaCoCo
class directories:

```kotlin
val jacocoExcludes = listOf(
    "com/simonrowe/migration/**",
    "com/simonrowe/dataops/**",
    "com/simonrowe/embedding/**",
    "com/simonrowe/agents/scrapers/SitemapHtmlScraper*",
    "com/simonrowe/agents/scrapers/LumaApiScraper*",
    "com/simonrowe/media/ExternalImageDownloader*",
    "com/simonrowe/aggregation/AdminAggregationController*",
    "com/simonrowe/agents/ContentAggregationAgent*",
    "com/simonrowe/agents/WeeklyDigestAgent*"
)
```

These are **class-file** patterns relative to the class output root.
`sonar.coverage.exclusions` takes **source-file** patterns relative to the module
base directory. They are not interchangeable.

**Decision**: Express the Sonar equivalents as `**/`-anchored source patterns —
e.g. `**/com/simonrowe/migration/**`,
`**/com/simonrowe/agents/scrapers/SitemapHtmlScraper*.java` — so they match
regardless of which module base directory the scanner resolves them against.

**Rationale**: `**/`-anchoring makes the patterns robust to the root-versus-module
basedir question, which is the part of the Gradle Sonar plugin's property
inheritance that is easiest to get wrong and hardest to see going wrong.

**Trade-off accepted and to be documented (FR-020)**: this is a hand-maintained
mirror of `jacocoExcludes` with no automated invariant. Drift shows up as the two
coverage percentages disagreeing — which is why that symptom is one of the five
documented failure modes.

**Alternatives considered**:

- Generate the Sonar list from `jacocoExcludes` in Kotlin, mapping `/**` →
  `/**` and `Foo*` → `Foo*.java` — attractive, and rejected for now. The mapping
  is not total (a class pattern can match a nested class whose source file is
  named differently), so a generator would be subtly wrong in a way a literal
  list is not. Recorded as a possible follow-up.

---

## R8. `software-factory` has real tests, so the measurement is worth taking

**Finding**: 101 main Java files, 33 test classes. Tests exist and cover the
security-relevant classes by name — `WebhookSignatureVerifierTest`,
`GitHubCredentialsTest`, `GitHubWebhookControllerTest`, `FindingSuppressorTest`.

**Decision**: Add the `jacoco` plugin and a `jacocoTestReport` producing XML.
**No `jacocoTestCoverageVerification`, no `tasks.check` wiring.**

**Rationale**: Per FR-017 — report only. Inventing a floor before measuring
either fails the build on day one or sets it below actual coverage. Both are
worse than no gate. The number gets reported to the operator (FR-018) and the
floor is chosen in follow-up.

**Note on `tasks.check`**: `backend` wires
`jacocoTestCoverageVerification` into `check`. `software-factory` must **not**
mirror that, because there is no verification task to wire. The CI job runs
`:software-factory:jacocoTestReport` as its own step.

---

## R9. Frontend coverage tooling

**Decision**: Add `@vitest/coverage-v8` at `^3.0.0`, matching the installed
`vitest ^3.0.0`.

**Rationale**: `@vitest/coverage-v8` is version-locked to the Vitest major — a
mismatched major refuses to load with an explicit error. V8 provider rather than
Istanbul: it needs no instrumentation transform, so it does not perturb the
existing 67 tests.

**Configuration** goes in the existing `test:` block of
`frontend/vite.config.ts`:

```ts
coverage: {
  provider: 'v8',
  reporter: ['text', 'lcov'],
  reportsDirectory: 'coverage',
}
```

plus a `test:coverage` script. `coverage/` is already gitignored
(`.gitignore:69`) and already in the ESLint ignore list
(`frontend/eslint.config.js`), so no ignore changes are needed.

**Note**: the existing `test` script is `vitest run`; `test:coverage` is
`vitest run --coverage` so CI produces coverage without changing what
`npm test` means for anyone running it locally.

**Side effect found during implementation, not anticipated above**:
`@vitest/coverage-v8` declares an **exact** peer dependency, not a range —
`{"vitest": "3.2.7", "@vitest/browser": "3.2.7"}`. So `npm install` resolved
`@vitest/coverage-v8@3.2.7` and bumped `vitest` and the eight `@vitest/*`
packages from `3.2.4` to `3.2.7` in `frontend/package-lock.json`. This is within
the declared `^3.0.0` range, so `package.json` is unchanged, but it is a real
change to what the test suite runs on.

Verified safe: all **67 test files / 450 tests pass** on 3.2.7. No package was
removed and no other dependency changed version. Recorded because a patch bump to
the test runner arriving as a side effect of adding coverage is the kind of thing
that should be stated rather than discovered later in a lockfile diff.

---

## R10. Artifact hand-off between CI jobs

**Decision**: `sonar` is a third job with `needs: [backend, frontend]`, consuming
three uploaded artifacts.

| Artifact | Produced by | Contains | Status |
| --- | --- | --- | --- |
| `jacoco-report` | `backend` job | `backend/build/reports/jacoco/` | **exists today** |
| `software-factory-jacoco-report` | `software-factory` job | `software-factory/build/reports/jacoco/` | new |
| `frontend-coverage` | `frontend` job | `frontend/coverage/` | new |

**Decision on `needs`**: the design says `needs: [backend, frontend]`. Since
`software-factory` coverage is now also consumed, `needs` must be
`[backend, frontend, software-factory]` — otherwise the download races the
producing job and the `software-factory` coverage is intermittently absent.
**This is a correction to the design**, forced by design item 5 (adding JaCoCo to
`software-factory`) which the design's own `needs:` list did not account for.

**Note on artifact download paths**: `actions/download-artifact` restores an
artifact's *contents*, not its original path prefix. Each download therefore needs
an explicit `path:` that reconstructs the location the `sonar` properties expect.

**Alternatives considered**:

- Have the `sonar` job re-run everything — rejected, duplicates the slowest work.
- Run Sonar inside the `backend` job as today — rejected. Frontend coverage would
  be unreachable without either running frontend tests in the backend job
  (conflates concerns) or a cross-job artifact anyway.

---

## R11. Full clone depth

**Decision**: `actions/checkout@v4` with `fetch-depth: 0` in the `sonar` job only.

**Rationale**: Sonar attributes "new code" by SCM blame. `actions/checkout`
defaults to `fetch-depth: 1`, under which blame data is absent and new-code
attribution is silently wrong rather than erroring. The other jobs do not need
history and are left at the default so they stay fast.

---

## R12. The three signals the loop procedure reads

Verified against the repository and the existing skills.

| Signal | Read via | Trap (verified) |
| --- | --- | --- |
| CI checks | `gh pr checks --watch` | `evaluate` is `continue-on-error: true` (`evals.yml:43`) and the new `sonar` job will be too (R2). Neither failing is blocking. `evaluate` is additionally `paths:`-filtered, so it is frequently *absent*, not just green. |
| Reviewer verdict | `gh api repos/{owner}/{repo}/issues/{pr}/comments`, filtered to `simonrowe-code-reviewer[bot]` | **Not** `/pulls/{pr}/reviews` — the reviewer posts an issue comment, so the reviews list is normally empty even on success. Silence is the normal presentation of failure, per the existing `code-review-triage` skill. One comment per PR since #103, but re-review happens per pushed commit. |
| Sonar findings | `api/issues/search?pullRequest={pr}&resolved=false` and `api/qualitygates/project_status` | Public project, so anonymous reads should work; try unauthenticated first, request a token only on `401`. Unverifiable until the account exists (FR-040). |

**Bot login confirmed**: `simonrowe-code-reviewer[bot]`, from
`agent-setup/components/skills/code-review-triage/SKILL.md`.

**Handoff target confirmed**: the existing `code-review-triage` skill owns the
"reviewer posted nothing" failure mode, so the new procedure delegates rather
than duplicating it.

---

## R13. Where the loop procedure lives

**Decision**: `agent-setup/components/skills/pr-review-loop/SKILL.md`, in the
separate `simonjamesrowe/agent-setup` repository (present locally at
`~/workspace/simonjamesrowe/agent-setup`).

**Rationale**: `CLAUDE.md` in this repository states skills are "Maintained in
simonjamesrowe/agent-setup — edit there". All 15 existing skills live there;
`dependency-cve-fix` is a single `SKILL.md` with no supporting files, which is
the shape to follow.

**Consequence**: this feature lands as **two pull requests**, one per repository,
merged as a pair. The monorepo half is independently mergeable and safe (it is a
no-op until the secret exists); the skill half has no build to break.

---

## Resolved unknowns summary

| Unknown from Technical Context | Resolution |
| --- | --- |
| Why has Sonar never run | R1 — step-level `env:` not in scope for its own `if:` |
| Cost of a tokenless run | R2 — ~10 min then hard failure; guard is load-bearing |
| Does `sonar` compile first | R3 — no; name `classes testClasses` explicitly |
| How to verify properties offline | R4 — assert referenced paths exist; server semantics deferred |
| Does frontend lint pass today | R5 — yes, exit 0, 5 warnings; step lands blocking |
| Frontend source/test split | R6 — design's 67-in-tests figure is wrong; 58/9 split needs overlap+filters |
| JaCoCo → Sonar exclusion translation | R7 — class patterns are not source patterns; `**/`-anchor them |
| `software-factory` coverage approach | R8 — report only, no floor, no `check` wiring |
| Frontend coverage provider/version | R9 — `@vitest/coverage-v8@^3.0.0`, v8 provider |
| Job graph and artifacts | R10 — three artifacts; `needs` must include `software-factory` |
| Clone depth | R11 — `fetch-depth: 0` on the `sonar` job only |
| Signal read mechanics | R12 — all three confirmed, traps documented |
| Skill location | R13 — `agent-setup`, two coordinated PRs |
