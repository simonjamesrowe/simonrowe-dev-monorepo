# Static analysis: activate SonarQube Cloud and own the PR quality loop

Date: 2026-08-21

## Problem

The monorepo has real static analysis on the backend and almost none anywhere
else, and its Sonar integration has never executed.

- `backend` — Checkstyle (`google_checks.xml`, `maxWarnings = 0`) and JaCoCo with
  a 78% floor, both enforced in CI. Healthy.
- `software-factory` — Checkstyle only. No coverage measurement at all, on the
  module that holds the GitHub App key and terminates untrusted webhook traffic.
- `frontend` — `eslint.config.js` and an `npm run lint` script exist and **CI
  never runs them**. 67 test files under `frontend/tests/`, zero coverage
  measurement.
- **SonarCloud is wired but has never run once.** `build.gradle.kts` has carried
  the `org.sonarqube` plugin and a `sonar {}` block pointing at
  `simonjamesrowe_simonrowe-dev-monorepo` since the first infrastructure commit
  (#2). The CI step is guarded by `if: env.SONAR_TOKEN != ''`, which can never be
  true — a step's own `env:` block is not in scope for its own `if:`, and the job
  declares no `env:`. There is also no `SONAR_TOKEN` secret on the repository.

Separately, no skill owns the loop that follows opening a pull request. The
`software-factory` reviewer comments on PRs and CI reports checks, but the
sequence *open PR → wait for reviewer + CI → address findings → push → re-wait*
is improvised each time. `dependency-cve-fix` contains a CI-only version of it in
its steps 5–7; `code-review-triage` covers only the failure mode where the
reviewer posts nothing. Adding a third signal (Sonar) to an improvised loop makes
the gap worse, so the loop and the tooling land together.

## Decision: SonarQube Cloud, not self-hosted

The repository is public, so SonarQube Cloud is free with unlimited lines of
code and includes pull request decoration.

Self-hosting is rejected. SonarQube Community needs roughly 3GB of heap and
bundles its own Elasticsearch. The production Pi already runs MongoDB,
Elasticsearch, Kafka, Temporal, ClickHouse, Postgres, Langfuse and
Dependency-Track, and it boots with `cgroup_disable=memory` — `mem_limit` is
decorative there, so a SonarQube OOM would take neighbouring containers with it.
Hosting also preserves the existing project key and `sonar {}` block unchanged.

The gate is **advisory in this change**. Sonar's quality gate arrives as its own
GitHub check via the SonarQube Cloud GitHub App and is left non-required;
`sonar.qualitygate.wait` is not set, so the `sonar` job cannot fail CI on gate
status. Making it blocking is a follow-up decision taken once a baseline exists.

## Scope

Two halves, landed together. Because skills are maintained in
`simonjamesrowe/agent-setup` (per `CLAUDE.md`: *"Maintained in
simonjamesrowe/agent-setup — edit there"*), "together" means one change landed as
two pull requests, one per repository, merged as a pair. Putting the skill in
this repository's `.claude/skills/` instead was rejected: it would be the only
skill outside `agent-setup` and would not be provisioned to `~/.claude/skills/`.

### Half 1 — platform (this repository)

**1. Fix the dead CI guard.** Move `SONAR_TOKEN` from the step's `env:` to
job-level `env:` on the job that runs analysis. This preserves the intended
"skip silently until the secret exists" behaviour and makes it work. Consequence:
merging this change alters no CI behaviour until the secret is added, so it is
safe to merge before the SonarQube Cloud account exists.

**2. New `sonar` job** in `ci.yml`, `needs: [backend, frontend]`.

- `actions/checkout@v4` with `fetch-depth: 0`. Sonar attributes new code by SCM
  blame; a shallow clone silently produces wrong new-code attribution.
- Downloads the backend JaCoCo XML (already uploaded today) and the frontend
  `lcov.info` (a new upload added to the frontend job) as artifacts.
- Runs `./gradlew classes testClasses sonar`. It compiles, because sonar-java
  analyses bytecode, but it does **not** re-run the Testcontainers suite —
  coverage comes from the downloaded reports.

A separate job is what makes frontend coverage reachable from a Gradle-driven
analysis. The alternatives were rejected: running frontend tests inside the
backend job conflates two concerns, and having the `sonar` job run every test
itself duplicates the slowest work in CI.

**3. Frontend into the analysis.**

- Add `@vitest/coverage-v8`; add a `coverage` block to the `test` section of
  `frontend/vite.config.ts` with `reporter: ['text', 'lcov']` and
  `reportsDirectory: 'coverage'`; add a `test:coverage` script. `coverage/` is
  already gitignored.
- On the root Gradle project: `sonar.sources = frontend/src`,
  `sonar.tests = frontend/tests`,
  `sonar.javascript.lcov.reportPaths = frontend/coverage/lcov.info`,
  `sonar.typescript.tsconfigPaths = frontend/tsconfig.app.json`. Setting
  `sonar.sources` on the root project is safe: the root applies the `java` plugin
  but has no Java sources, and each subproject contributes its own.
- **No frontend coverage threshold.** There is no baseline to derive one from.

**4. `npm run lint` becomes a CI step** in the frontend job. It has never
executed, so its current state is unknown. It is added as a blocking step only
if the existing code passes cleanly; if it does not, the violations are reported
and the step lands non-blocking, with the violation count reported to the
operator, rather than expanding this change into an ESLint cleanup.

**5. JaCoCo for `software-factory`** — `jacoco` plugin plus a
`jacocoTestReport` producing XML, added to `sonar.coverage.jacoco.xmlReportPaths`
alongside the backend report. **Report only, no verification rule.** The measured
figure is reported back and the floor set from it in a follow-up; inventing a
threshold before measuring either fails the build on day one or sets a floor
below actual coverage, and both are worse than no gate.

**6. Sonar coverage exclusions mirror `jacocoExcludes`.** The backend already
excludes nine packages (`migration`, `dataops`, `embedding`, several scrapers and
agents) from its JaCoCo view. Without matching `sonar.coverage.exclusions`, Sonar
reports those as 0% covered and its coverage number contradicts the Gradle gate
over the same code. The two lists must be kept in step, which is a maintenance
cost accepted in exchange for the numbers agreeing.

**7. Runbook** at `docs/runbooks/static-analysis.md`, following the existing
runbook conventions: what runs where, how to read the gate, the manual-only
steps, and the failure modes below.

**8. Operator checklist** — steps that cannot be performed from a workspace,
recorded in the runbook and reported at the end:

1. Sign in to SonarQube Cloud with GitHub; create or confirm the `simonjamesrowe`
   organisation.
2. Create the project under the **existing** key
   `simonjamesrowe_simonrowe-dev-monorepo`. If that key is unavailable, the key
   in `build.gradle.kts` changes instead — the code follows the account, not the
   reverse.
3. Install the SonarQube Cloud GitHub App on the repository. Pull request
   decoration does not work without it.
4. Set the analysis method to **CI-based** and **turn Automatic Analysis off**.
   Left on, it competes with the Gradle scanner and yields results with no
   coverage data — a confusing half-working state rather than a clean failure.
5. `gh secret set SONAR_TOKEN`. The token value is never pasted into a chat,
   echoed, or written to a file.

### Half 2 — the `pr-review-loop` skill (agent-setup)

A new skill in `agent-setup/components/skills/pr-review-loop/`, structured on
`dependency-cve-fix` and reusing its wording where it already says the right
thing.

It owns the sequence: pre-flight locally → open the pull request → wait on three
signals → triage → fix → push → re-wait, bounded → report.

**Pre-flight before opening the PR.** Run what CI will run — the backend
Checkstyle/test/coverage tasks, `:software-factory:check`, `npm run lint`,
`npm test` — because a local failure costs seconds and a CI failure costs a round
trip. Defers to the `backend-test` skill for the Gradle incantations rather than
restating them.

**Open the PR, never as a draft.** The reviewer bot ignores drafts, so a draft is
silently never reviewed, and drafts save no CI because `pull_request` fires for
them anyway. Title and body follow the repository conventions: conventional
commit prefix, no Jira reference in this org, no attribution to Claude.

**The three signals.**

| Signal | How | Trap |
| --- | --- | --- |
| CI checks | `gh pr checks --watch` | `evaluate` (Promptfoo) and the new `sonar` job are `continue-on-error` — advisory, not blocking |
| Reviewer comment | `gh api repos/{owner}/{repo}/issues/{pr}/comments`, filtered to `simonrowe-code-reviewer[bot]` | **Not** `/pulls/{pr}/reviews`, which is normally empty even on success. One comment per PR since #103. Silence means failure, not approval → hand off to `code-review-triage` |
| Sonar findings | `api/issues/search?pullRequest={pr}&resolved=false` and `api/qualitygates/project_status` | The project is public, so these reads should succeed anonymously; the skill tries unauthenticated first and only asks for a token on a 401 |

**Triage rules, baked in.**

- **New code only.** An unrelated pull request does not get dragged into whatever
  pre-existing debt the first `main` analysis surfaces.
- **Fix, or decline with a stated reason in the pull request.** Never silence a
  finding by marking it "won't fix" in the Sonar UI — that hides the decision
  from the diff and from review.
- References `superpowers:receiving-code-review`, so a questionable finding is
  verified rather than obeyed.

**Bounded loop.** Roughly three iterations, then stop and hand back with what was
tried and what still fails — the same bound `dependency-cve-fix` already applies
to CI. Note that the reviewer re-reviews per pushed commit (the Temporal workflow
id embeds the head SHA) but posts one comment per PR.

**Report.** PR URL, CI state, findings addressed, findings declined and why, and
the gate status.

## Out of scope

- **The pre-existing debt sweep.** The first analysis of `main` will surface
  accumulated issues. Their volume is unknown until it runs, so triaging them is
  separate work with its own plan, not a tail appended to this one.
- **Making the Sonar gate blocking.** Requires a baseline. Follow-up.
- **A `software-factory` coverage floor.** Requires the measurement this change
  produces. Follow-up.
- **Additional Java analysers** (SpotBugs, PMD, Error Prone). Sonar's Java rules
  cover this ground; adding three more analysers now buys overlap and noise.
- **Importing ESLint reports into Sonar** via `sonar.eslint.reportPaths`. Sonar's
  own TypeScript rules already run over `frontend/src`; the import duplicates
  findings.
- **Dependency and container vulnerability scanning.** Already owned by
  Dependency-Track and the CycloneDX BOM pipeline.

## Failure modes to document

- Automatic Analysis left enabled — analysis appears to work but reports no
  coverage.
- Shallow clone — new-code attribution silently wrong.
- Missing SonarQube Cloud GitHub App — analysis succeeds, no PR decoration.
- Project key mismatch between `build.gradle.kts` and the account.
- Sonar and JaCoCo coverage percentages disagreeing, meaning
  `sonar.coverage.exclusions` has drifted from `jacocoExcludes`.

## Testing

- **The Gradle wiring** is verified by running `./gradlew sonar --dry-run` (task
  graph resolves, properties present) and by asserting the generated properties
  contain the frontend and `software-factory` paths.
- **Frontend coverage** is verified by running `npm run test:coverage` and
  confirming `frontend/coverage/lcov.info` exists and is non-empty.
- **`software-factory` coverage** is verified by running its `jacocoTestReport`
  and reading the actual percentage, which is reported to the operator.
- **The CI job graph** is verified on the pull request itself: the `sonar` job
  must run, skip the analysis step cleanly while no secret exists, and not fail.
- **End-to-end Sonar** cannot be verified from a workspace — it needs the account
  and secret. After the operator checklist is complete, the first real pull
  request is the verification, and the skill's Sonar API calls are confirmed
  against actual responses at that point. The skill is written against documented
  API behaviour and this assumption is stated in it explicitly.
