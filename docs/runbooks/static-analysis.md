# Runbook: Static analysis (SonarQube Cloud)

Static analysis for the whole monorepo runs on **every push to `main`** and reports into
one SonarQube Cloud project, `simonjamesrowe_simonrowe-dev-monorepo`. Pull requests are
not analysed. What the analysis finds reaches Linear instead: the software factory's
`staticanalysis` module reads `main`'s analysis every night and files new-code findings
as tickets, which a person can hand to the ticket agent with `factory:agent`, exactly as
for a CVE. See [Findings reach Linear](#findings-reach-linear-the-staticanalysis-module).

**Why not on pull requests any more (2026-10-23).** The pull-request analysis was never a
required check and its gate was advisory, but it ran after all three build jobs and took
either ~2 minutes or ~16 (a full re-analysis, about half the time across 40 runs). That
held the `CI` workflow open, and the ticket agent waits on the whole workflow, so every
agent attempt and every `pr-review-loop` session waited for a check nobody was required
to read. The constitution's Principle III was amended (4.0.0) to match.

**The gate is advisory.** `sonar.qualitygate.wait` is deliberately unset, so the `sonar`
job cannot fail CI on gate status. A failing quality-gate condition on `main` is filed as
a ticket by the nightly scan instead.

**History worth knowing.** The `org.sonarqube` plugin and the `sonar {}` block have
been in `build.gradle.kts` since the first infrastructure commit (#2), and the
analysis **never ran once** until this change. The CI step was guarded by
`if: env.SONAR_TOKEN != ''` with `SONAR_TOKEN` declared only in that step's own
`env:` block — and a step's `if:` is evaluated before its own `env:` is applied, so
the condition could never be true. If you are ever tempted to move that variable
back to step level, this is what happens.

## What runs where

Five jobs in `.github/workflows/ci.yml`, on `pull_request` against `main` and on every push
to `main`. The `sonar` job runs on the push only:

```
changes ─┬─ backend            (Checkstyle, tests, JaCoCo + 0.78 floor, CycloneDX)
         ├─ frontend           (ESLint, Vitest + coverage, build)
         ├─ software-factory   (Checkstyle + tests, JaCoCo report, container build; shell tests)
         └─ sonar              needs all of them · push to main only · continue-on-error
```

On a pull request, `changes` runs only the jobs the change needs
([build-and-startup.md](build-and-startup.md)); a push to `main` runs everything. The `sonar`
job **runs no tests**. It checks out with `fetch-depth: 0`, downloads every module's coverage,
asserts it is present, compiles with `./gradlew classes testClasses`, then analyses. Coverage is
only ever as fresh as the artifacts. **It is skipped when any build job on `main` failed**,
because a failed job uploads no coverage, so a red `main` is also an unanalysed one. The nightly
scan notices: see [failure mode 7](#7-main-has-stopped-being-analysed).

| Module | Language | Coverage produced by | Artifact | Floor |
| --- | --- | --- | --- | --- |
| `backend` | Java 21 | `:backend:jacocoTestReport` | `jacoco-report` | **0.78 instruction, enforced** |
| `software-factory` | Java 21 | `:software-factory:jacocoTestReport` | `software-factory-jacoco-report` | none — report only |
| frontend | TypeScript | `npm run test:coverage` | `frontend-coverage` | **45% lines / 78% branches / 58% functions, enforced** |

The frontend floors are Vitest `coverage.thresholds` in `frontend/vite.config.ts`,
so they are enforced by `npm run test:coverage` — which is what the `frontend` CI
job already runs, and what the pre-commit hook runs for staged `frontend/` paths.
Nothing new had to be added to CI. **Sonar sees the same lcov but its gate is
advisory; this is the blocking half.** Each floor sits a few points under its
measurement, the same margin the backend's 0.78 leaves against its 82.5% — enough
that adding one untested file does not turn an unrelated pull request red. Raise
them when the number rises.

Baseline at the time this landed, all measured locally:

| Module | Coverage | Notes |
| --- | --- | --- |
| `backend` | **82.5%** instruction, 82.9% line, 61.9% branch | comfortably over its 0.78 floor |
| `software-factory` | **80.4%** instruction, 78.5% line, 67.0% branch | first ever measurement |
| frontend | **44.72%** statements, 80.03% branch | first ever measurement |

Re-measured 2026-08-27, when the frontend floors were set: **48.66% lines, 81.37%
branches, 62.73% functions**, over 77 test files / 596 tests.

`npm run lint` exits 0 with 5 `react-refresh/only-export-components` warnings and
0 errors.

**The backend figure is the number to compare against** when checking for failure
mode 5 — Sonar should report backend coverage within about a percentage point of
82.5%.

### Why a separate job

Frontend coverage is only reachable from a Gradle-driven analysis via a cross-job
artifact. The alternatives were both worse: running frontend tests inside the
backend job conflates two concerns, and having the `sonar` job run every test
itself duplicates the slowest work in CI.

### Why `classes testClasses` is named explicitly

sonar-java analyses **bytecode**, not source, and the `sonar` task pulls in no
compile tasks of its own — verify with `./gradlew sonar --dry-run`, which prints
`:sonar SKIPPED` and nothing else. Without compiled classes the Java analysis
degrades with a warning rather than failing.

**Never `./gradlew build sonar`** — that re-runs the Testcontainers suite.

### Why the token guard is load-bearing

A tokenless `./gradlew sonar` in this repository was measured at **9 minutes 53
seconds followed by a hard failure**. It is not a cheap no-op. If the guard ever
fails *open*, every pull request burns ten minutes and goes red.

Two independent mechanisms keep the `sonar` job harmless:

1. `if: env.SONAR_TOKEN != ''` on the analysis step, with `SONAR_TOKEN` at
   **job** level so the condition can actually evaluate.
2. `continue-on-error: true` on the job, which absorbs any failure once a token
   does exist.

## How to read the gate

There is nothing to read on a pull request any more. On `main`:

1. Read the nightly tickets labelled `factory:staticanalysis` in Linear. Each one is a
   single rule in a single module (or one failing quality-gate condition), with every
   location and a link to the issue in SonarCloud.
2. **Fix it, or decline it.** To fix, add `factory:agent` and the ticket agent opens a
   pull request; or fix it by hand. To decline, **cancel the ticket** with the reason in a
   comment: the Linear sink reads a cancelled fingerprint as "never tell me again", so it
   is not re-filed. Record a lasting decline in [Standing declines](#standing-declines)
   as well. Do not mark it "won't fix" in the SonarQube UI — that hides the decision from
   the diff and from review.
3. For a look at the whole picture, SonarCloud's **New Code** view of `main`, not
   Overall.

The project is public, so `api/issues/search`, `api/qualitygates/project_status`,
`api/project_analyses/search` and `api/rules/search` all answer anonymously, which is
why the module needs no SonarCloud credential.

## Findings reach Linear: the `staticanalysis` module

`com.simonrowe.factory.staticanalysis`, on the `static-analysis` Temporal queue, polled by
`software-factory` only. A daily schedule, `static-analysis-daily`, fires at 03:00 UTC and is
created **active**; the console's Software Factory page has **Dry run Sonar scan** and
**Read Sonar now**. Switched on by `FACTORY_STATICANALYSIS_ENABLED` (default `true` in
`docker-compose.prod.yml`). Each run:

1. **Judges the analysis before reading it.** SonarCloud goes on answering for its last
   analysis of `main` whatever has happened since, so a stale read looks exactly like a
   current one. The module compares the latest analysis's revision with `main`'s head on
   GitHub. Head analysed, or head younger than `analysis-grace` (6h; an analysis lands
   ~20 minutes after a merge) is **fresh**. Anything else is **stale**, and the run files
   one ticket saying so (key parts `source-health`, `STALE`) and does nothing else. GitHub
   being unreachable does not stop a run: freshness is then unconfirmed and the run detail
   says so.
2. **Reads open new-code issues on `main`** and the quality gate's failing conditions.
3. **Groups them by module and rule.** Code smells below `minimum-severity` (`MAJOR`) are
   dropped; bugs and vulnerabilities are filed at any severity. One group is one ticket:
   not a ticket per issue (forty "unused import" tickets after one careless merge), and not
   one consolidated report like the CVE scan (the ticket agent works one ticket per pull
   request, and "fix every finding" is not a change it can land). Key parts are
   `rule`, module, rule key, or `quality-gate`, metric key: never the rule's name, so a
   rename on SonarCloud's side does not fork tickets.
4. **Files at most `max-per-run` (5) groups**, quality-gate failures first, then groups
   with a bug or vulnerability, then by severity and size, in `REFRESH` mode: a persisting
   finding has its description rewritten each night with no comment. The rest are named
   to the absence sweep so their tickets are not closed while they wait.
5. **Closes what has gone.** After filing, the absence sweep closes this producer's
   tickets that went unreported for `resolve-after` (12h, not log watch's 7 days: a
   finding missing from a fresh, complete analysis is gone, not quiet). Never on a stale
   analysis, never on a truncated read. A stale-analysis ticket closes itself the same way
   once `main` is analysed again.

Ticket bodies tell the agent that `NOSONAR`, `@SuppressWarnings`, `eslint-disable` or a new
exclusion is not a fix; the ticket agent's `SilencingDetector` labels any pull request that
adds one `no-auto-merge` anyway, and `build.gradle.kts` (where exclusions live) is a
human-merge path.

**Operator actions.**

- **Create the Linear label `factory:staticanalysis` before the first run.** The sink
  does not create producer labels: without it every ticket is filed unlabelled (with a
  WARN in the log), and the label is how a person tells these tickets apart in Triage.
  `software-factory` caches labels for its lifetime, so a label created after it started
  needs a restart to be seen.
- First run: press **Dry run Sonar scan** and read the run detail before letting the
  schedule file for real (the schedule is active, so do it the day this ships).
- `FACTORY_STATICANALYSIS_SONAR_TOKEN` exists only for a private project. Leave it unset.

### New code on `main` is *30 days*, and that setting is not in this repository

On a pull request, "new code" is the diff, so the gate reads correctly whatever
the project is configured with. On `main` it is whatever
`sonar.leak.period` says, and the SonarQube Cloud default —
`previous_version` — is wrong for this repository in a way that is invisible
until you look for it:

- `previous_version` resets the period when the analysed **project version**
  changes. The root `build.gradle.kts` pins `version = "0.0.1-SNAPSHOT"` and
  nothing bumps it, so after the one analysis that introduced that string the
  period never reset again. It grows by one day, every day, forever.
- Measured on 2026-08-27, before the change: **286 of the project's 294 open
  issues counted as "new code"** — that is, almost the whole codebase. Every
  pre-existing vulnerability was scored against the new-code security condition,
  which is why the `main` gate read `ERROR` on `new_security_rating` while no
  recent commit had introduced a vulnerability at all.

It is now **`sonar.leak.period = 30`** (Number of days), set at project scope:

```bash
# The setting is server-side. A scanner property will not do it: the analysis
# reports into the period the server already holds.
curl -u "$SONAR_CLOUD_TOKEN:" -X POST https://sonarcloud.io/api/settings/set \
  --data-urlencode component=simonjamesrowe_simonrowe-dev-monorepo \
  --data-urlencode key=sonar.leak.period \
  --data-urlencode value=30

curl -u "$SONAR_CLOUD_TOKEN:" \
  "https://sonarcloud.io/api/settings/values?component=simonjamesrowe_simonrowe-dev-monorepo&keys=sonar.leak.period"
```

`SONAR_CLOUD_TOKEN` is in the repository `.env`. There is no
`api/new_code_periods/*` on SonarQube Cloud — that web service is SonarQube
Server only, and calling it returns `Unknown url`, which reads like a
permissions problem and is not one.

Confirm it took effect from the *next* analysis rather than from the write: the
`periods` block of `api/qualitygates/project_status` should report
`"mode":"days","parameter":"30"`.

**Checked on 2026-10-23, it had not.** `api/settings/values` returns
`sonar.leak.period = 30`, but the `main` gate's `periods` block still reports
`"mode":"previous_version"` from 2026-08-26, so "new code" on `main` is everything since
then. That matters more now: the nightly `staticanalysis` scan files `main`'s new-code
issues, and with the period never resetting it will eventually file pre-existing debt.
Harmless while `main` has almost nothing open (one issue on that date), but set the new
code definition in the UI (project → Administration → New Code) and confirm it here.

Because this lives in the SaaS project and not
in the repository, **it does not survive re-creating the project** — it is on the
[operator checklist](#operator-checklist--steps-only-a-human-can-do) below.

## Operator checklist — steps only a human can do

None of this can be done from a workspace. Until it is complete the `sonar` job
runs, skips the analysis step, and reports success — merging the platform change
ahead of this checklist is safe and changes no CI behaviour.

- [ ] **1. Sign in to [SonarQube Cloud](https://sonarcloud.io) with GitHub** and
      create or confirm the **`simonjamesrowe`** organisation.

- [ ] **2. Create the project under the existing key**
      `simonjamesrowe_simonrowe-dev-monorepo`.
      If that key is unavailable, change the key in `build.gradle.kts` to match the
      account — **the code follows the account, not the reverse.**

- [ ] **3. Install the SonarQube Cloud GitHub App** on the repository.
      Pull requests are no longer analysed, so it decorates nothing today; it is
      kept for the day they are again — see failure mode 3.

- [ ] **4. Set the analysis method to CI-based and turn Automatic Analysis OFF.**
      Left on, it competes with the Gradle scanner and yields results with no
      coverage data — a confusing half-working state rather than a clean failure.
      See failure mode 1.

- [ ] **5. Set the repository secret:**

      ```bash
      gh secret set SONAR_TOKEN --repo simonjamesrowe/simonrowe-dev-monorepo
      ```

      Paste the token into that prompt in your own shell. **Never** paste a token
      value into a chat, echo it to a terminal, or write it to a file.

- [ ] **6. Set the new code definition on `main` to Number of days: 30.**
      The default, Previous version, silently never resets here — see
      [New code on `main` is *30 days*](#new-code-on-main-is-30-days-and-that-setting-is-not-in-this-repository)
      for why, and for the one-line API call that sets it. Skipping this does not
      break anything visibly; it makes the `main` gate score the whole codebase as
      new code.

- [ ] **7. Verify on the next push to `main`**: the `sonar` job runs the analysis
      step instead of skipping it; coverage is non-zero for all three modules; the
      backend coverage percentage agrees with the JaCoCo figure (see failure mode 5);
      and the next `staticanalysis` run reports the analysis as fresh.

## Maintenance obligation: keep the two exclusion lists in step

`backend/build.gradle.kts` excludes nine packages and classes from its JaCoCo
coverage view (`jacocoExcludes`). The root `build.gradle.kts` carries a
nine-entry `sonar.coverage.exclusions` mirror of it. **There is no automated
check that they agree.**

The two lists are **not** copies of each other — they are translations between two
pattern dialects:

| | JaCoCo | Sonar |
| --- | --- | --- |
| Matches | compiled `.class` files | source files |
| Relative to | the class output root | a module base directory |
| Example | `com/simonrowe/migration/**` | `**/com/simonrowe/migration/**` |
| Single type | `WeeklyDigestAgent*` | `**/com/simonrowe/agents/WeeklyDigestAgent*.java` |

The `**/` anchor is what makes the Sonar patterns basedir-independent. The
`.java` suffix is what makes them match source rather than nothing. A literal
copy of `jacocoExcludes` into `sonar.coverage.exclusions` would match nothing
while looking configured.

**When you add or remove a `jacocoExcludes` entry, add or remove its Sonar
counterpart in the same commit.** A cheap count check:

```bash
sed -n '/^val jacocoExcludes/,/^)/p' backend/build.gradle.kts | grep -c '"com/simonrowe'
sed -n '/sonar.coverage.exclusions/,/joinToString/p' build.gradle.kts | grep -c '"\*\*/com/simonrowe'
```

Both should print `9`. This catches the count drifting; it cannot catch an entry
being edited to point somewhere else. Failure mode 5 is how that presents.

## Note on the frontend source/test split

`sonar.sources` and `sonar.tests` **deliberately overlap** on `frontend/src`.
58 frontend test files live in `frontend/tests`, but **9 sit beside the code they
test** under `frontend/src`. Two filters keep every file indexed exactly once:

- `sonar.exclusions` removes the nine `*.test.ts(x)` files from the main index.
- `sonar.test.inclusions` admits them to the test index.

Remove `sonar.exclusions` and the scanner aborts with *"File can't be indexed
twice"*. Remove `sonar.test.inclusions` and those nine are analysed as production
code — production rules applied to test code, analysed surface inflated, coverage
denominator depressed.

`frontend/src/test/setup.ts` is Vitest harness, not a test. It matches no
`*.test.*` pattern and correctly stays main source. Do not add a pattern that
catches it.

Related limitation, accepted: `frontend/tsconfig.app.json` has
`"include": ["src"]`, so `sonar.typescript.tsconfigPaths` resolves types for
`frontend/src` but not for `frontend/tests`. Type-aware TypeScript rules are
weaker over those 58 files. The rules still run, with less type information.

## Failure modes

### 1. Automatic Analysis left enabled

**Symptom**: analysis appears to work. The check is green, issues are reported,
and **coverage is 0% or absent** for every module.

**Cause**: SonarQube Cloud's Automatic Analysis is competing with the Gradle
scanner. Automatic Analysis never sees the JaCoCo or LCOV reports.

**Remedy**: project → Administration → Analysis Method → set **CI-based**, turn
Automatic Analysis **off**. Re-run the `main` CI run.

### 2. Shallow clone

**Symptom**: the analysis succeeds but New Code attribution is wrong — findings
attributed to the wrong author or the wrong commit, or new-code issues in files no
recent commit touched.

**Cause**: the `sonar` job checked out without `fetch-depth: 0`. Sonar derives new
code from SCM blame; with no blame data it guesses, and does not warn.

**Remedy**: confirm `fetch-depth: 0` on the `sonar` job's `actions/checkout` step.
The other jobs do not need it and are left at the default so they stay fast.

### 3. SonarQube Cloud GitHub App not installed

**Symptom** (only if pull-request analysis is ever switched back on): the analysis
succeeds — you can see the result in the SonarQube UI — but nothing appears on the
pull request. No check, no comment, no decoration.

**Cause**: pull request decoration is delivered by the GitHub App, not by the
scanner.

**Remedy**: install the SonarQube Cloud GitHub App on the repository (operator
checklist step 3).

### 4. Project key mismatch

**Symptom**: the `sonar` job fails with a project-not-found or authorisation
error, ~10 minutes in.

**Cause**: `sonar.projectKey` in `build.gradle.kts` does not match the key on the
SonarQube Cloud account, or the project does not exist yet.

**Remedy**: reconcile them. The account is authoritative — change the key in
`build.gradle.kts`, not the project on the account.

### 5. Sonar and JaCoCo coverage percentages disagree

**Symptom**: SonarQube reports one backend coverage figure and
`:backend:jacocoTestCoverageVerification` measures a materially different one over
the same code. Expect them within about one percentage point.

**Cause**: `sonar.coverage.exclusions` has drifted from `jacocoExcludes` — a new
exclusion was added to one list and not the other, or an entry was translated
incorrectly between the two dialects.

**Remedy**: reconcile the two lists entry by entry. See
[Maintenance obligation](#maintenance-obligation-keep-the-two-exclusion-lists-in-step).

### 6. A coverage artifact never arrives

**Symptom**: one module reports 0% coverage while the others look right.

**Cause**: an artifact upload or download broke — a renamed artifact, a changed
report path, or a producing job that was skipped. A job skipped by `changes` is expected and
its report is not required; the input check only demands reports from jobs that ran. **A missing coverage report is
not an analysis error**: Sonar reports 0% and carries on.

**Remedy**: the `sonar` job's **Verify analysis inputs** step exists precisely for
this. It runs unconditionally, before the analysis and even when the analysis is
skipped, and prints `ok` / `MISS` per input. Read its log first. Note that
`actions/download-artifact` restores an artifact's *contents*, not the path it was
uploaded from, so each download's `path:` has to reconstruct the location the
`sonar` properties expect.

### 7. `main` has stopped being analysed

**Symptom**: a Linear ticket titled *Static analysis: SonarCloud has stopped analysing main*,
or the `staticanalysis` run detail saying `STALE`.

**Cause**, most likely first: a build job failed on `main`, so the `sonar` job was skipped
(read the CI run for the commit the ticket names); `SONAR_TOKEN` expired, so the analysis
step was skipped while the job stayed green; or any of failure modes 1 to 4.

**Fix**: get `main` green, or fix the token or project setting. The next push analyses
`main` and the next nightly scan closes the ticket.

## Verifying locally, without an account

Everything below runs offline with no credential.

```bash
# Frontend coverage produces a non-empty LCOV report
cd frontend && npm run test:coverage && test -s coverage/lcov.info && echo OK

# Frontend lint still exits 0
cd frontend && npm run lint; echo "exit=$?"

# software-factory coverage report exists (report only — never fails on the number)
./gradlew :software-factory:jacocoTestReport

# The sonar task graph resolves, and pulls in no compile tasks
./gradlew sonar --dry-run

# Compilation for analysis (no tests run)
./gradlew classes testClasses
```

**Do not run `./gradlew sonar` locally without a token** — ten minutes, then a
failure. `-Dsonar.scanner.dumpToFile` does not help; the scanner contacts the
server before honouring it.

What cannot be verified without the account: the real analysis, server-side
interpretation of the properties, and Sonar/JaCoCo agreement.

## Follow-up work

Each of these was deliberately deferred, with the reason recorded:

- **Triage the pre-existing debt** the first analysis of `main` surfaces. Its
  volume is unknown until it runs, so it is separate work with its own plan — not
  a tail appended to an unrelated pull request.
- ~~**Make the quality gate blocking.**~~ Superseded on 2026-10-23: pull requests are
  no longer analysed, and a failing condition on `main` is filed as a ticket by the
  nightly scan instead.
- **Set a `software-factory` coverage floor.** The measurement now exists: 80.4%
  instruction, 78.5% line. A floor at the backend's 0.78 is immediately
  satisfiable.
- **Clear the five ESLint warnings** and add `--max-warnings 0` to the lint step.
  All five are `react-refresh/only-export-components` and all are fixable by
  moving a context or a constant to its own file:
  `src/components/skills/SkillRatingBar.tsx`,
  `src/components/tour/TourProvider.tsx`, `src/contexts/ChatContext.tsx`,
  `src/contexts/ThemeContext.tsx`, `src/hooks/useDrawer.tsx`.

## Standing declines

Findings that will keep reappearing on `main` and are deliberately not being
fixed. They are recorded here, and not marked "won't fix" in the SonarQube UI,
for the reason given in [How to read the gate](#how-to-read-the-gate): a decision
that lives only in the SaaS project is invisible to review. Re-open any of them
if the reasoning stops holding.

| Rule | Where | Why declined |
| --- | --- | --- |
| `java:S4502` — CSRF protection disabled | `auth/SecurityConfig.java` | The API is a stateless OAuth2 resource server: `SessionCreationPolicy.STATELESS`, bearer-token auth, no cookie the browser attaches ambiently. CSRF tokens defend session cookies; there is no session. Enabling it would break every admin write for no gain. |
| `java:S5443` — publicly writable directory (×3) | `dataops/BackupService.java`, `dataops/RestoreService.java` | All three are `Files.createTempFile`, the NIO form, which creates with `O_CREAT\|O_EXCL` and — on POSIX — owner-only permissions, unlike the legacy `File.createTempFile`. There is no symlink-preemption window to attack. Moving backups off `/tmp` is a separate change with disk-space consequences on the Pi. |
| `tssecurity:S8476` — tainted client-side request URL | `services/narrationApi.ts` | `contentId` is already `encodeURIComponent`-ed at both branches of the URL it builds, so it cannot introduce a path segment or a query. Sonar wants allowlist *validation* rather than encoding; encoding is sufficient for a path segment. |

## Deliberately not done

- **Self-hosting SonarQube.** SonarQube Community needs ~3GB of heap and bundles
  its own Elasticsearch. The production Pi already runs MongoDB, Elasticsearch,
  Kafka, Temporal, ClickHouse, Postgres, Langfuse and Dependency-Track, and it
  boots with `cgroup_disable=memory` — `mem_limit` is decorative there, so a
  SonarQube OOM would take neighbouring containers with it. The repository is
  public, so SonarQube Cloud is free with unlimited lines of code and includes PR
  decoration.
- **Additional Java analysers** (SpotBugs, PMD, Error Prone). Sonar's Java rules
  cover this ground; three more analysers buy overlap and noise.
- **Importing ESLint reports** via `sonar.eslint.reportPaths`. Sonar's own
  TypeScript rules already run over `frontend/src`; the import duplicates
  findings.
- **Dependency and container vulnerability scanning.** Owned by Dependency-Track
  and the CycloneDX BOM pipeline — see
  [dependency-track.md](dependency-track.md).

## Related

- [software-factory.md](software-factory.md) — the automated reviewer, and the
  `staticanalysis` module that files this analysis's findings
- [linear.md](linear.md) — the sink the findings are filed through, including
  cancel-to-suppress and the absence sweep
- [dependency-track.md](dependency-track.md) — dependency vulnerability scanning
- `pr-review-loop` skill — owns the open-PR → signals → triage → push loop. SonarQube is no
  longer one of its pull-request signals
- `code-review-triage` skill — when the reviewer posts nothing
