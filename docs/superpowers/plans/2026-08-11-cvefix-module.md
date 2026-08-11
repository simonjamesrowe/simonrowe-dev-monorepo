# software-factory `cvefix` Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scheduled software-factory Temporal workflow that reads Dependency-Track findings, has Claude bump the affected dependencies, opens one pull request, watches CI, and repairs failures until green.

**Architecture:** A new `cvefix` module in `software-factory`, beside `codereview` and `feedback`, on its own `cve-fix` task queue. The build runs **only in CI** — nothing is compiled inside the container, so the image, the Docker socket and the resource guards are all untouched. Claude edits manifest files and returns structured output; every clone, commit, push, Dependency-Track call and GitHub call is a Java activity, so the agent needs no credentials, no `git` and no `docker`.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Temporal Java SDK (`io.temporal.spring.boot`), Spring Data MongoDB, JDK `HttpClient`, JUnit 5 + `TestWorkflowEnvironment` + Testcontainers Mongo.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-11-dependency-track-cve-automation-design.md`. Read it before Task 1.
- **Prerequisite:** `docs/superpowers/plans/2026-08-11-cve-fix-skill-and-evals-filter.md` Task 2 Step 7 must be done first. The agent prompt in Task 6 is derived from that skill's body, and the repair budget default of `3` should be replaced with the number a real run needed.
- Java style: Google Java Style, Checkstyle with `maxWarnings = 0`. `final` on all parameters, Javadoc on every public type and method. Run `../gradlew :software-factory:check` before every commit.
- **Do not modify `ClaudeCliRunner`.** Its env allowlist and `--disallowedTools mcp__*` are correct for this module unchanged: the agent gets no Dependency-Track credential because a Java activity holds it.
- **Do not modify `GitHubCredentials.mintInstallationToken`.** It requests `contents`/`issues`/`pull_requests` in one shared payload and GitHub 422s the whole request if the App was not granted a requested permission — adding `checks: read` there would break code-review and feedback too. CI status is read unauthenticated instead (see Task 8).
- Feature flag `factory.cvefix.enabled` defaults to `false`. Every Mongo index initializer and the schedule initializer must be `@ConditionalOnProperty(name = "factory.cvefix.enabled", havingValue = "true")`, following `LearningIndexInitializer` — an unreachable Mongo must not fail the whole application context and take the webhook receiver down with it.
- Dependency-Track projects in scope: `simonrowe-dev/backend`, `simonrowe-dev/frontend`. Container-image projects are out of scope.
- Branch is the fixed `chore/dependency-cve-fixes`, force-pushed. Never generate a dated branch name.
- Pull requests are **not** drafts (the code-review bot ignores drafts) and are never auto-merged.
- Changed-path allowlist, exactly: `backend/build.gradle.kts`, `gradle/libs.versions.toml`, `frontend/package.json`, `frontend/package-lock.json`. **Expressed once in code** as `CveFixAllowedFiles.ALL` (Task 2); the agent's tool grant (Task 6) and the activity's path validation (Task 9) both read that constant rather than re-listing it. The `file` enum in `cve-fix-schema.json` is the one intentional duplicate — JSON Schema cannot reference a Java constant — and must be kept in sync by hand.
- Conventional commits, no Jira references, no Claude attribution.

## File Structure

```text
software-factory/src/main/java/com/simonrowe/factory/
  git/                                    # Task 1 — extracted from feedback/agent
    RepositoryWorkspace.java              #   AutoCloseable clone (was GuidanceWorkspace)
    RepositoryWorkspaceFactory.java       #   clone / changedPaths / validate / commitAndPush
  cvefix/
    config/
      CveFixProperties.java               # Task 2
      CveFixTaskQueues.java               # Task 2
      CveFixAllowedFiles.java             # Task 2 — the changed-path allowlist, once
      CveFixBeans.java                    # Task 3 — exposes the nested DependencyTrack record
    domain/
      Finding.java                        # Task 3
      ComponentFindings.java              # Task 3
      FixProposal.java                    # Task 6
      Bump.java                           # Task 6
      UnfixableComponent.java             # Task 6
      CveFixStatus.java                   # Task 2
      CveFixPhase.java                    # Task 2
      CveFixProgress.java                 # Task 2
      CveFixResult.java                   # Task 2
      CveFixRequest.java                  # Task 10 — workflow input, carries the CI settings
      CiOutcome.java                      # Task 8
    dependencytrack/
      DependencyTrackClient.java          # Task 3
    persistence/
      CveFixRunRecord.java                # Task 4
      CveFixRunRepository.java            # Task 4
      UnfixableFindingRecord.java         # Task 4
      UnfixableFindingRepository.java     # Task 4
      CveFixIndexInitializer.java         # Task 4
      FindingSuppressor.java              # Task 5
    agent/
      FixEngine.java                      # Task 6
      ClaudeCliFixEngine.java             # Task 6
    github/
      CveFixPrGateway.java                # Task 7
      CveFixPrBodyRenderer.java           # Task 9 — FixSummary -> PR title and body
      CiStatusGateway.java                # Task 8
    workflow/
      CveFixWorkflow.java                 # Task 10
      CveFixWorkflowImpl.java             # Task 10
      CveFixActivities.java               # Task 9
      CveFixActivitiesImpl.java           # Task 9
    schedule/
      CveFixScheduleInitializer.java      # Task 11
software-factory/src/main/resources/
  cve-fix-schema.json                     # Task 6
```

Each task below ends with a green `:software-factory:check` and a commit.

---

### Task 1: Extract the shared git workspace into `factory/git`

**Files:**
- Create: `software-factory/src/main/java/com/simonrowe/factory/git/RepositoryWorkspace.java`
- Create: `software-factory/src/main/java/com/simonrowe/factory/git/RepositoryWorkspaceFactory.java`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/feedback/agent/GuidanceWorkspaceFactory.java` (delete the extracted parts, delegate)
- Delete: `software-factory/src/test/java/com/simonrowe/factory/feedback/agent/GuidanceWorkspaceFactoryTest.java` — all four of its tests cover methods that move in this task, so once they are moved the class is empty. Move the tests into `RepositoryWorkspaceFactoryTest`, then delete the file; an empty test class is worse than none.
- Create: `software-factory/src/test/java/com/simonrowe/factory/git/RepositoryWorkspaceFactoryTest.java`

**Interfaces:**
- Consumes: `ProcessRunner.run(List<String>, Path, String, Map<String,String>, Set<String>, Duration, Consumer<String>)`, `GitHubCredentials.accessToken(Long)`, `GitWorkspaceFactory.basicAuthorizationHeader(String)`.
- Produces, used by Tasks 9 and 10:
  - `RepositoryWorkspace` — a top-level `public final class` in its own file (Step 3), implementing `AutoCloseable`, with `Path repository()`, `String defaultBranch()`, `void close()`. Not nested: Checkstyle's `OneTopLevelClass` and every later import assume its own file.
  - `RepositoryWorkspaceFactory.create(String owner, String repository, Long installationId, Path workspaceRoot, String prefix, Consumer<String> heartbeat) -> RepositoryWorkspace`
  - `RepositoryWorkspaceFactory.changedPaths(RepositoryWorkspace, Consumer<String>) -> List<String>`
  - `RepositoryWorkspaceFactory.validateAllowedPaths(List<String> changedPaths, List<String> allowedGlobs) -> void` (static, throws `IllegalStateException`)
  - `RepositoryWorkspaceFactory.commitAndPush(RepositoryWorkspace, String branch, String message, String authorName, String authorEmail, Long installationId, Consumer<String> heartbeat) -> void`

**Context the implementer needs:**

`GuidanceWorkspaceFactory` already does clone → `changedPaths` → `validateAllowedPaths` → `commitAndPush`. `cvefix` needs the same four things, so extract rather than copy a third time. Read `GuidanceWorkspaceFactory` in full before starting.

Two deliberate signature changes during extraction:

1. `workspaceRoot`, `prefix`, `authorName` and `authorEmail` become **parameters** instead of being read from `FeedbackProperties`. The shared class must not depend on either module's properties.
2. `commitAndPush` currently runs `git checkout --quiet -b <branch>`, which fails with `fatal: a branch named '...' already exists` the second time it is called on the same workspace. The CVE repair loop calls it repeatedly in one run, so it must become `git checkout --quiet -B <branch>` (create or reset). **This is the single most important behavioural change in this task** — without it the repair loop dies on iteration two.

- [ ] **Step 1: Write the failing test for repeated commitAndPush on one workspace**

Create `software-factory/src/test/java/com/simonrowe/factory/git/RepositoryWorkspaceFactoryTest.java`:

```java
package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryWorkspaceFactoryTest {

  @Test
  void checksOutBranchWithCapitalBSoRepeatedCallsSucceed() {
    // -B creates or resets; -b fails if the branch exists, which breaks the CVE repair
    // loop's second iteration against the same workspace.
    assertThat(RepositoryWorkspaceFactory.checkoutCommand("chore/dependency-cve-fixes"))
        .containsExactly("git", "checkout", "--quiet", "-B", "chore/dependency-cve-fixes");
  }

  @Test
  void rejectsChangedPathsOutsideTheAllowlist() {
    assertThatThrownBy(
            () ->
                RepositoryWorkspaceFactory.validateAllowedPaths(
                    List.of("backend/build.gradle.kts", "backend/src/main/java/Evil.java"),
                    List.of("backend/build.gradle.kts", "gradle/libs.versions.toml")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("backend/src/main/java/Evil.java");
  }

  @Test
  void acceptsChangedPathsInsideTheAllowlist() {
    RepositoryWorkspaceFactory.validateAllowedPaths(
        List.of("gradle/libs.versions.toml", "frontend/package-lock.json"),
        List.of(
            "gradle/libs.versions.toml",
            "frontend/package.json",
            "frontend/package-lock.json"));
  }

  @Test
  void parsesPorcelainRenamesToTheirDestination() {
    assertThat(RepositoryWorkspaceFactory.parsePorcelain("R  old.json -> frontend/package.json\n"))
        .containsExactly("frontend/package.json");
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*RepositoryWorkspaceFactoryTest*'`

Expected: compilation failure — `package com.simonrowe.factory.git does not exist`.

- [ ] **Step 3: Create `RepositoryWorkspace`**

Create `software-factory/src/main/java/com/simonrowe/factory/git/RepositoryWorkspace.java`:

```java
package com.simonrowe.factory.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A disposable checkout of a repository. Closing it deletes the whole temporary tree, so callers
 * should use it in a try-with-resources block.
 */
public final class RepositoryWorkspace implements AutoCloseable {

  private final Path root;
  private final Path repository;
  private final String defaultBranch;

  RepositoryWorkspace(final Path root, final Path repository, final String defaultBranch) {
    this.root = root;
    this.repository = repository;
    this.defaultBranch = defaultBranch;
  }

  /** The checkout directory itself, the working directory for git and the agent. */
  public Path repository() {
    return repository;
  }

  /** The cloned repository's default branch name, as reported by git. */
  public String defaultBranch() {
    return defaultBranch;
  }

  @Override
  public void close() {
    deleteTree(root);
  }

  static void deleteTree(final Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(RepositoryWorkspace::deleteQuietly);
    } catch (IOException ignored) {
      // A failed cleanup must not hide the activity's useful failure.
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup under a unique temporary root.
    }
  }
}
```

- [ ] **Step 4: Create `RepositoryWorkspaceFactory`**

Move the body of `GuidanceWorkspaceFactory` here, applying the two signature changes. Create `software-factory/src/main/java/com/simonrowe/factory/git/RepositoryWorkspaceFactory.java`:

```java
package com.simonrowe.factory.git;

import com.simonrowe.factory.codereview.agent.GitWorkspaceFactory;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Prepares disposable checkouts for agents to edit, then validates and pushes the result.
 *
 * <p>The agent that edits files in the checkout never touches git and never holds a credential:
 * this factory owns cloning, allowlist validation of the changed paths, and the commit and push.
 * Shared by the feedback and cvefix modules; it deliberately takes the workspace root, branch
 * prefix and git identity as parameters rather than reading either module's properties.
 */
@Component
public class RepositoryWorkspaceFactory {

  private static final Duration GIT_TIMEOUT = Duration.ofMinutes(3);

  private final GitHubCredentials credentials;
  private final ProcessRunner processRunner;

  public RepositoryWorkspaceFactory(
      final GitHubCredentials credentials, final ProcessRunner processRunner) {
    this.credentials = credentials;
    this.processRunner = processRunner;
  }

  /** Shallow-clones the default branch of {@code owner/repository} into a temporary workspace. */
  public RepositoryWorkspace create(
      final String owner,
      final String repository,
      final Long installationId,
      final Path workspaceRoot,
      final String prefix,
      final Consumer<String> heartbeat) {
    Path workspace = null;
    try {
      Path root = workspaceRoot.toAbsolutePath().normalize();
      Files.createDirectories(root);
      workspace = Files.createTempDirectory(root, prefix);
      Path checkout = workspace.resolve("repository");
      heartbeat.accept("Cloning " + owner + "/" + repository);
      runGit(
          List.of(
              "git", "clone", "--quiet", "--depth", "1",
              "https://github.com/" + owner + "/" + repository + ".git",
              checkout.toString()),
          workspace,
          installationId,
          heartbeat);
      ProcessRunner.ProcessResult head =
          runGit(
              List.of("git", "symbolic-ref", "--short", "HEAD"), checkout, installationId,
              heartbeat);
      return new RepositoryWorkspace(workspace, checkout, head.standardOutput().trim());
    } catch (RuntimeException | IOException exception) {
      if (workspace != null) {
        RepositoryWorkspace.deleteTree(workspace);
      }
      throw new IllegalStateException("Unable to prepare repository workspace", exception);
    }
  }

  /** Paths touched in the workspace, repo-relative, from {@code git status --porcelain}. */
  public List<String> changedPaths(
      final RepositoryWorkspace workspace, final Consumer<String> heartbeat) {
    ProcessRunner.ProcessResult status =
        runGit(List.of("git", "status", "--porcelain"), workspace.repository(), null, heartbeat);
    return parsePorcelain(status.standardOutput());
  }

  static List<String> parsePorcelain(final String output) {
    return output
        .lines()
        .filter(line -> !line.isBlank())
        .map(line -> line.substring(3))
        .map(path -> path.contains(" -> ") ? path.substring(path.indexOf(" -> ") + 4) : path)
        .map(
            path ->
                path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2
                    ? path.substring(1, path.length() - 1)
                    : path)
        .toList();
  }

  /** Throws {@link IllegalStateException} when any changed path escapes the allowlist. */
  public static void validateAllowedPaths(
      final List<String> changedPaths, final List<String> allowedGlobs) {
    FileSystem fileSystem = FileSystems.getDefault();
    List<PathMatcher> matchers =
        allowedGlobs.stream().map(glob -> fileSystem.getPathMatcher("glob:" + glob)).toList();
    List<String> violations =
        changedPaths.stream()
            .filter(path -> matchers.stream().noneMatch(matcher -> matcher.matches(Path.of(path))))
            .toList();
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Agent touched files outside the allowlist: " + String.join(", ", violations));
    }
  }

  /**
   * Uses {@code -B}, not {@code -b}: the CVE repair loop calls {@link #commitAndPush} repeatedly
   * against one workspace, and {@code -b} fails once the branch exists.
   */
  static List<String> checkoutCommand(final String branch) {
    return List.of("git", "checkout", "--quiet", "-B", branch);
  }

  /** Branch, add, commit and force-push. Never invoked by an agent — Java only. */
  public void commitAndPush(
      final RepositoryWorkspace workspace,
      final String branch,
      final String message,
      final String authorName,
      final String authorEmail,
      final Long installationId,
      final Consumer<String> heartbeat) {
    Path repo = workspace.repository();
    runGit(checkoutCommand(branch), repo, installationId, heartbeat);
    runGit(List.of("git", "add", "--all"), repo, installationId, heartbeat);
    runGit(
        List.of(
            "git", "-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail,
            "commit", "--quiet", "-m", message),
        repo,
        installationId,
        heartbeat);
    heartbeat.accept("Pushing " + branch);
    // --force: this branch namespace belongs to the factory; a re-drive or a repair iteration
    // replaces its own earlier proposal.
    runGit(
        List.of("git", "push", "--force", "--quiet", "origin", "HEAD:refs/heads/" + branch),
        repo,
        installationId,
        heartbeat);
  }

  private ProcessRunner.ProcessResult runGit(
      final List<String> command,
      final Path directory,
      final Long installationId,
      final Consumer<String> heartbeat) {
    String accessToken = credentials.accessToken(installationId);
    Map<String, String> environment =
        accessToken.isBlank()
            ? Map.of("GIT_TERMINAL_PROMPT", "0")
            : Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http.extraHeader",
                "GIT_CONFIG_VALUE_0",
                GitWorkspaceFactory.basicAuthorizationHeader(accessToken));
    ProcessRunner.ProcessResult result =
        processRunner.run(command, directory, null, environment, Set.of(), GIT_TIMEOUT, heartbeat);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          command.get(1) + " failed: " + abbreviate(result.standardError(), 600));
    }
    return result;
  }

  private static String abbreviate(final String value, final int maximumLength) {
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }
}
```

- [ ] **Step 5: Run the new test to confirm it passes**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*RepositoryWorkspaceFactoryTest*'`

Expected: 4 tests PASS.

- [ ] **Step 6: Make `GuidanceWorkspaceFactory` delegate**

Reduce `GuidanceWorkspaceFactory` to a thin adapter that supplies `FeedbackProperties` values to the shared factory. Replace its class body with:

```java
@Component
public class GuidanceWorkspaceFactory {

  private final FeedbackProperties properties;
  private final RepositoryWorkspaceFactory workspaces;

  public GuidanceWorkspaceFactory(
      final FeedbackProperties properties, final RepositoryWorkspaceFactory workspaces) {
    this.properties = properties;
    this.workspaces = workspaces;
  }

  /** Shallow-clones the default branch of {@code owner/repository} into a temp workspace. */
  public RepositoryWorkspace create(
      final String owner,
      final String repository,
      final Long installationId,
      final Consumer<String> heartbeat) {
    return workspaces.create(
        owner, repository, installationId, properties.workspaceRoot(), "guidance-", heartbeat);
  }

  /** Paths touched in the workspace (git status --porcelain), repo-relative. */
  public List<String> changedPaths(
      final RepositoryWorkspace workspace, final Consumer<String> heartbeat) {
    return workspaces.changedPaths(workspace, heartbeat);
  }

  /** Branch + add + commit + force-push, using the feedback module's git identity. */
  public void commitAndPush(
      final RepositoryWorkspace workspace,
      final String branch,
      final String message,
      final Long installationId,
      final Consumer<String> heartbeat) {
    workspaces.commitAndPush(
        workspace, branch, message, properties.gitAuthorName(), properties.gitAuthorEmail(),
        installationId, heartbeat);
  }
}
```

Delete the now-duplicated `parsePorcelain`, `validateAllowedPaths`, `runGit`, `deleteTree`, `deleteQuietly`, `abbreviate` and the nested `GuidanceWorkspace` class from this file. Update every reference to `GuidanceWorkspaceFactory.GuidanceWorkspace` to `RepositoryWorkspace`, and every call to `GuidanceWorkspaceFactory.validateAllowedPaths(...)` to `RepositoryWorkspaceFactory.validateAllowedPaths(...)`.

- [ ] **Step 7: Find and fix every caller**

Run: `grep -rn "GuidanceWorkspace\b\|validateAllowedPaths\|parsePorcelain" software-factory/src/`

Update each hit. The callers are, verified against the current tree:

- `FeedbackActivitiesImpl` (two references, at the `GuidanceWorkspace` type in the workspace lifecycle and at the `validateAllowedPaths` call).
- `GuidanceWorkspaceFactoryTest` (four references — every test in the file).
- **`FeedbackActivitiesImplTest`** — the largest set, around ten references, including several `mock(GuidanceWorkspaceFactory.GuidanceWorkspace.class)` sites. Each of those becomes `mock(RepositoryWorkspace.class)`. Do not overlook this file: it does not import `GuidanceWorkspaceFactory` by that name everywhere, so a naive search-and-replace on the outer class misses the mocks.

`ClaudeCliDistillEngine` is **not** a caller — it takes `target.workspace()` as a `Path` and references none of the moved symbols. Do not go looking for changes there.

Move the tests for the moved methods out of `GuidanceWorkspaceFactoryTest` into `RepositoryWorkspaceFactoryTest` rather than deleting them, then delete the now-empty `GuidanceWorkspaceFactoryTest`.

- [ ] **Step 8: Run the whole module's checks**

Run: `cd software-factory && ../gradlew :software-factory:check`

Expected: BUILD SUCCESSFUL, Checkstyle clean, every pre-existing feedback test still green. **This task is a pure refactor: a single behaviour change to any feedback test is a bug in your extraction, not an expected consequence.** The one intended behaviour change is `-b` → `-B`.

- [ ] **Step 9: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/git software-factory/src/main/java/com/simonrowe/factory/feedback software-factory/src/test/java/com/simonrowe/factory
git commit -m "refactor: extract shared repository workspace into factory/git

The cvefix module needs the same clone / validate-changed-paths / commit-and-push
cycle as feedback, so extract it rather than copy it a third time. Workspace root,
branch prefix and git identity become parameters so the shared class depends on
neither module's properties.

One behaviour change: checkout uses -B rather than -b, because the CVE repair
loop calls commitAndPush repeatedly against one workspace and -b fails once the
branch exists."
```

---

### Task 2: Configuration, task queue and status types

**Files:**
- Create: `.../cvefix/config/CveFixProperties.java`
- Create: `.../cvefix/config/CveFixTaskQueues.java`
- Create: `.../cvefix/config/CveFixAllowedFiles.java`
- Create: `.../cvefix/domain/CveFixStatus.java`, `CveFixPhase.java`, `CveFixProgress.java`, `CveFixResult.java`
- Modify: `software-factory/src/main/resources/application.yml`
- Modify: `software-factory/src/main/java/com/simonrowe/factory/FactoryApplication.java` (only if `@ConfigurationPropertiesScan` needs the new package — check first)
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/config/CveFixPropertiesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CveFixTaskQueues.CVE_FIX` = `"cve-fix"`
  - `CveFixAllowedFiles.ALL` — the four-entry changed-path allowlist, the single source for Tasks 6 and 9
  - `CveFixProperties` record with accessors `enabled()`, `owner()`, `repository()`, `branch()`, `baseBranch()`, `gitAuthorName()`, `gitAuthorEmail()`, `workspaceRoot()`, `dependencyTrack()`, `agent()`, `ci()`
  - `CveFixProperties.DependencyTrack` with `baseUrl()`, `apiKey()`, `projects()`, `requestTimeout()`
  - `CveFixProperties.Agent` with `command()`, `model()`, `effort()`, `maxTurns()`, `timeout()`
  - `CveFixProperties.Ci` with `pollInterval()`, `repairBudget()`, `maxWait()`, `advisoryChecks()`

**There is deliberately no `installationId` property.** The GitHub installation id is resolved at
run time by the activity, from `GitHubCredentials.installationId(owner, repository)` (Task 9). A
configured id would be a second source of truth that nothing populates, and a null one silently
degrades `GitHubCredentials.accessToken(null)` to the static `GITHUB_TOKEN` — which the
`software-factory` compose service does not set, giving an anonymous clone and a `403` on push.

**The three `ci` values are also carried on `CveFixRequest`** (Task 10). Workflow code cannot read
them from this bean: `@WorkflowImpl` classes are instantiated by the Temporal SDK, not Spring, so
they have no injected dependencies at all. `CveFixScheduleInitializer` (Task 11) holds this bean
and copies `pollInterval`, `repairBudget` and `maxWait` into the request it schedules.
  - `CveFixStatus` enum: `COMPLETED`, `NO_FINDINGS`, `SKIPPED_PR_OPEN`, `NOTHING_FIXABLE`, `CI_UNRESOLVED`, `FAILED`
  - `CveFixPhase` enum: `ACCEPTED`, `CHECKING_PR`, `FETCHING`, `PREPARING`, `PROPOSING`, `PUSHING`, `AWAITING_CI`, `REPAIRING`, `COMPLETED`, `SKIPPED`, `FAILED`
  - `CveFixProgress(CveFixPhase phase, String detail, Integer count)` with a static `accepted()`
  - `CveFixResult(String workflowId, CveFixStatus status, String prUrl, int bumps, int unfixable, String detail)`

- [ ] **Step 1: Write the failing test**

Create `software-factory/src/test/java/com/simonrowe/factory/cvefix/config/CveFixPropertiesTest.java`:

```java
package com.simonrowe.factory.cvefix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CveFixPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsDefaultsWithTheFeatureDisabled() {
    runner.run(
        context -> {
          CveFixProperties properties = context.getBean(CveFixProperties.class);
          assertThat(properties.enabled()).isFalse();
          assertThat(properties.branch()).isEqualTo("chore/dependency-cve-fixes");
          assertThat(properties.baseBranch()).isEqualTo("main");
          assertThat(properties.ci().repairBudget()).isEqualTo(3);
          assertThat(properties.ci().pollInterval()).isEqualTo(Duration.ofMinutes(3));
          // 3h, not 45m: one repair iteration costs up to agent.timeout (15m) plus a full
          // CI cycle, so a 45m cap would truncate the documented budget of 3 repairs.
          assertThat(properties.ci().maxWait()).isEqualTo(Duration.ofHours(3));
          // The promptfoo evals job is continue-on-error advisory; it must never be able to
          // decide the build is RED.
          assertThat(properties.ci().advisoryChecks()).containsExactly("evaluate");
          assertThat(properties.dependencyTrack().projects())
              .containsExactly("simonrowe-dev/backend", "simonrowe-dev/frontend");
        });
  }

  @Test
  void overridesBindFromProperties() {
    runner
        .withPropertyValues(
            "factory.cvefix.enabled=true",
            "factory.cvefix.ci.repair-budget=5",
            "factory.cvefix.ci.poll-interval=90s",
            "factory.cvefix.dependency-track.base-url=http://dt:8080",
            "factory.cvefix.dependency-track.api-key=secret")
        .run(
            context -> {
              CveFixProperties properties = context.getBean(CveFixProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.ci().repairBudget()).isEqualTo(5);
              assertThat(properties.ci().pollInterval()).isEqualTo(Duration.ofSeconds(90));
              assertThat(properties.dependencyTrack().baseUrl()).isEqualTo("http://dt:8080");
            });
  }

  @Test
  void projectsListIsUnmodifiable() {
    CveFixProperties.DependencyTrack dependencyTrack =
        new CveFixProperties.DependencyTrack(
            "http://dt:8080", "k", List.of("a"), Duration.ofSeconds(30));
    assertThat(dependencyTrack.projects()).isUnmodifiable();
  }

  @EnableConfigurationProperties(CveFixProperties.class)
  static class TestConfig {
  }
}
```

Note: the defaults asserted here come from `application.yml`, which `ApplicationContextRunner` does **not** load. Bind the defaults in the record's compact constructor so they hold regardless of the property source, and mirror them in `application.yml` for documentation and for env-var overriding.

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixPropertiesTest*'`

Expected: compilation failure — `package com.simonrowe.factory.cvefix.config does not exist`.

- [ ] **Step 3: Create the task queue constant and the allowlist constant**

```java
package com.simonrowe.factory.cvefix.config;

/** Temporal task queue names for CVE-fix workflows. */
public final class CveFixTaskQueues {
  public static final String CVE_FIX = "cve-fix";

  private CveFixTaskQueues() {
  }
}
```

```java
package com.simonrowe.factory.cvefix.config;

import java.util.List;

/**
 * The only files a CVE-fix run may change, in one place.
 *
 * <p>Read by the agent's tool grant (Task 6) and by the activity's changed-path validation
 * (Task 9), so the two cannot drift. The {@code file} enum in {@code cve-fix-schema.json} is the
 * one intentional duplicate — JSON Schema cannot reference a Java constant — and must be kept in
 * sync by hand.
 */
public final class CveFixAllowedFiles {

  /** Repository-relative paths, exactly as {@code git status --porcelain} reports them. */
  public static final List<String> ALL =
      List.of(
          "gradle/libs.versions.toml",
          "backend/build.gradle.kts",
          "frontend/package.json",
          "frontend/package-lock.json");

  private CveFixAllowedFiles() {
  }
}
```

- [ ] **Step 4: Create `CveFixProperties`**

```java
package com.simonrowe.factory.cvefix.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the scheduled CVE-fix flow.
 *
 * <p>There is deliberately no installation id here: the activity resolves it from
 * {@code GitHubCredentials.installationId(owner, repository)} at run time, so there is no
 * configured value that can be left null and silently degrade every git operation to anonymous.
 */
@ConfigurationProperties("factory.cvefix")
public record CveFixProperties(
    boolean enabled,
    String owner,
    String repository,
    String branch,
    String baseBranch,
    String gitAuthorName,
    String gitAuthorEmail,
    Path workspaceRoot,
    DependencyTrack dependencyTrack,
    Agent agent,
    Ci ci) {

  public CveFixProperties {
    owner = owner == null ? "simonjamesrowe" : owner;
    repository = repository == null ? "simonrowe-dev-monorepo" : repository;
    branch = branch == null ? "chore/dependency-cve-fixes" : branch;
    baseBranch = baseBranch == null ? "main" : baseBranch;
    gitAuthorName = gitAuthorName == null ? "simonrowe-code-reviewer[bot]" : gitAuthorName;
    gitAuthorEmail =
        gitAuthorEmail == null
            ? "simonrowe-code-reviewer[bot]@users.noreply.github.com"
            : gitAuthorEmail;
    workspaceRoot =
        workspaceRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : workspaceRoot;
    dependencyTrack = dependencyTrack == null ? DependencyTrack.defaults() : dependencyTrack;
    agent = agent == null ? Agent.defaults() : agent;
    ci = ci == null ? Ci.defaults() : ci;
  }

  /** Dependency-Track endpoint, credential and the projects in scope. */
  public record DependencyTrack(
      String baseUrl, String apiKey, List<String> projects, Duration requestTimeout) {

    public DependencyTrack {
      baseUrl = baseUrl == null ? "http://dependencytrack-apiserver:8080" : baseUrl;
      apiKey = apiKey == null ? "" : apiKey;
      projects =
          projects == null || projects.isEmpty()
              ? List.of("simonrowe-dev/backend", "simonrowe-dev/frontend")
              : List.copyOf(projects);
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    static DependencyTrack defaults() {
      return new DependencyTrack(null, null, null, null);
    }
  }

  /** Claude CLI process configuration for the fix phase. */
  public record Agent(
      String command, String model, String effort, int maxTurns, Duration timeout) {

    public Agent {
      command = command == null ? "claude" : command;
      model = model == null ? "sonnet" : model;
      effort = effort == null ? "medium" : effort;
      maxTurns = maxTurns == 0 ? 40 : maxTurns;
      timeout = timeout == null ? Duration.ofMinutes(15) : timeout;
    }

    static Agent defaults() {
      return new Agent(null, null, null, 0, null);
    }
  }

  /**
   * CI polling. {@code repairBudget} bounds how many times the agent may react to a red build
   * before the run gives up and leaves the pull request open for a human. {@code advisoryChecks}
   * names check runs whose conclusion must never make the build RED.
   */
  public record Ci(
      Duration pollInterval, int repairBudget, Duration maxWait, List<String> advisoryChecks) {

    public Ci {
      // 3 minutes keeps unauthenticated GitHub API use at ~20 requests/hour, inside the
      // 60/hour per-IP limit that route is subject to. See CiStatusGateway.
      pollInterval = pollInterval == null ? Duration.ofMinutes(3) : pollInterval;
      repairBudget = repairBudget == 0 ? 3 : repairBudget;
      // 3h, not 45m: one repair iteration costs up to agent.timeout (15m) plus a whole CI
      // cycle, so repairBudget + 1 iterations do not fit in 45 minutes and the wall-clock cap
      // would silently truncate the documented budget.
      maxWait = maxWait == null ? Duration.ofHours(3) : maxWait;
      // The promptfoo evals job is continue-on-error advisory. Job-level continue-on-error
      // keeps the *run* from failing; it is not documented to rewrite the check-run
      // conclusion, so this list — not that setting — is what guarantees an advisory job
      // cannot burn the repair budget. See CiStatusGateway and Task 8 Step 4.
      advisoryChecks =
          advisoryChecks == null || advisoryChecks.isEmpty()
              ? List.of("evaluate")
              : List.copyOf(advisoryChecks);
    }

    static Ci defaults() {
      return new Ci(null, 0, null, null);
    }
  }
}
```

- [ ] **Step 5: Create the four domain types**

`CveFixStatus.java`:

```java
package com.simonrowe.factory.cvefix.domain;

/** Terminal outcome of one CVE-fix run. */
public enum CveFixStatus {
  /** CI went green; the pull request is waiting for a human to merge. */
  COMPLETED,
  /** Dependency-Track reported nothing actionable. */
  NO_FINDINGS,
  /** A CVE pull request is already open, so this run did nothing. */
  SKIPPED_PR_OPEN,
  /** Findings existed but the agent could not produce a single bump. */
  NOTHING_FIXABLE,
  /**
   * CI never went green. Either the repair budget ran out, or {@code ci.maxWait} elapsed first;
   * the two cases are distinguished by {@link CveFixResult#detail()}, not by the status. The pull
   * request is left open either way, which makes every later run hit {@link #SKIPPED_PR_OPEN}.
   */
  CI_UNRESOLVED,
  /** The run failed for an operational reason: Dependency-Track down, git error, agent error. */
  FAILED
}
```

`CveFixPhase.java`:

```java
package com.simonrowe.factory.cvefix.domain;

/** Coarse progress phase, surfaced by the workflow's query method. */
public enum CveFixPhase {
  ACCEPTED, CHECKING_PR, FETCHING, PREPARING, PROPOSING, PUSHING, AWAITING_CI, REPAIRING,
  COMPLETED, SKIPPED, FAILED
}
```

`CveFixProgress.java`:

```java
package com.simonrowe.factory.cvefix.domain;

/** Queryable progress snapshot. {@code count} is phase-dependent and may be null. */
public record CveFixProgress(CveFixPhase phase, String detail, Integer count) {

  /** The state a run reports before its first activity completes. */
  public static CveFixProgress accepted() {
    return new CveFixProgress(CveFixPhase.ACCEPTED, "Accepted", null);
  }
}
```

`CveFixResult.java`:

```java
package com.simonrowe.factory.cvefix.domain;

/** Outcome of one CVE-fix run, returned by the workflow method. */
public record CveFixResult(
    String workflowId,
    CveFixStatus status,
    String prUrl,
    int bumps,
    int unfixable,
    String detail) {
}
```

- [ ] **Step 6: Add the `application.yml` block**

Append under the existing `factory:` key, after the `feedback:` block:

```yaml
  cvefix:
    enabled: ${FACTORY_CVEFIX_ENABLED:false}
    owner: simonjamesrowe
    repository: simonrowe-dev-monorepo
    branch: chore/dependency-cve-fixes
    base-branch: main
    # No installation-id key: the activity resolves it from
    # GitHubCredentials.installationId(owner, repository) at run time. A configured-but-empty
    # value would make accessToken(null) fall back to the static GITHUB_TOKEN, which this
    # service does not set — anonymous clone, 403 on push.
    git-author-name: simonrowe-code-reviewer[bot]
    git-author-email: simonrowe-code-reviewer[bot]@users.noreply.github.com
    workspace-root: ${FACTORY_WORKSPACE_ROOT:${java.io.tmpdir}/software-factory}
    dependency-track:
      # The container on the shared compose network, not the public hostname: this keeps the
      # call off the Cloudflare/pinggy path entirely.
      base-url: ${DEPENDENCYTRACK_BASE_URL:http://dependencytrack-apiserver:8080}
      api-key: ${DEPENDENCYTRACK_API_KEY:}
      projects:
        - simonrowe-dev/backend
        - simonrowe-dev/frontend
      request-timeout: 30s
    agent:
      command: ${CLAUDE_COMMAND:claude}
      model: ${FACTORY_CVEFIX_MODEL:sonnet}
      effort: ${FACTORY_CVEFIX_EFFORT:medium}
      max-turns: ${FACTORY_CVEFIX_MAX_TURNS:40}
      timeout: ${FACTORY_CVEFIX_TIMEOUT:15m}
    ci:
      # 3m keeps unauthenticated GitHub API use near 20 req/hour, inside the 60/hour per-IP
      # limit. Do not lower without moving CiStatusGateway to the installation token.
      poll-interval: ${FACTORY_CVEFIX_POLL_INTERVAL:3m}
      repair-budget: ${FACTORY_CVEFIX_REPAIR_BUDGET:3}
      # 3h, not 45m: repair-budget + 1 iterations, each up to agent.timeout plus a full CI
      # cycle, do not fit in 45 minutes. A shorter cap silently truncates the budget.
      max-wait: ${FACTORY_CVEFIX_MAX_WAIT:3h}
      # Check runs that may never make the build RED. The promptfoo evals job is
      # continue-on-error advisory: if it ever reports conclusion: failure, every run would
      # burn the whole repair budget on it and end CI_UNRESOLVED, blocking all later runs.
      advisory-checks:
        - evaluate
```

Do **not** add `com.simonrowe.factory.cvefix.workflow` to `spring.temporal.workers-auto-discovery.workflow-packages` yet — that package does not exist until Task 9, and no `@WorkflowImpl` lives in it until Task 10, which is where the list is edited.

- [ ] **Step 7: Confirm properties are picked up by the application**

Run: `grep -rn "ConfigurationPropertiesScan\|EnableConfigurationProperties" software-factory/src/main/java/`

If `FactoryApplication` uses `@ConfigurationPropertiesScan`, the new package under `com.simonrowe.factory` is already covered — change nothing. If it lists classes explicitly, add `CveFixProperties.class`.

- [ ] **Step 8: Run the tests and checks**

Run: `cd software-factory && ../gradlew :software-factory:check`

Expected: BUILD SUCCESSFUL, the three `CveFixPropertiesTest` tests passing, `FactoryApplicationTest` still green (it boots the whole context, so a binding mistake surfaces here).

- [ ] **Step 9: Commit**

```bash
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/main/resources/application.yml software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: add cvefix configuration, task queue and status types

Feature-flagged off by default. Dependency-Track is reached at
dependencytrack-apiserver:8080 on the compose network rather than the public
hostname, and the 3-minute CI poll interval keeps unauthenticated GitHub API use
inside the 60/hour per-IP limit."
```

---

### Task 3: Dependency-Track client

**Files:**
- Create: `.../cvefix/domain/Finding.java`, `ComponentFindings.java`
- Create: `.../cvefix/dependencytrack/DependencyTrackClient.java`
- Create: `.../cvefix/config/CveFixBeans.java` (Step 4 — without it `DependencyTrackClient` cannot autowire)
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/dependencytrack/DependencyTrackClientTest.java`

**Interfaces:**
- Consumes: `CveFixProperties.DependencyTrack`.
- Produces:
  - `Finding(String purl, String componentName, String componentVersion, String vulnerabilityId, String severity, String recommendation)`
  - `ComponentFindings(String purl, String componentName, String componentVersion, List<String> vulnerabilityIds, List<Finding> findings)` with `static List<ComponentFindings> group(List<Finding>)` and `String fingerprint()`
  - `DependencyTrackClient.findings() -> List<Finding>` — every in-scope project's unsuppressed findings, or throws `IllegalStateException`

**Context the implementer needs:**

Two Dependency-Track endpoints, both taking an `X-Api-Key` header:

- `GET /api/v1/project?pageSize=100` → array of projects with `name` and `uuid`.
- `GET /api/v1/finding/project/{uuid}` → array of findings shaped
  `{component: {purl, name, version}, vulnerability: {vulnId, severity, recommendation}, analysis: {isSuppressed}}`.

Three rules that matter:

1. **Skip `analysis.isSuppressed == true`.** Those have been audited and dismissed; "fixing" one undoes a human decision.
2. **There is no fixed-version field.** Do not invent one. `recommendation` is free prose and often `null` — pass it through verbatim for the agent to read.
3. **Fail loudly, never partially.** If any in-scope project errors or is missing, throw. Dependency-Track shares `langfuse-db` with Langfuse, so it can be down independently; opening a pull request from half a finding set would silently under-report.

`fingerprint()` is the suppression key used in Task 5: the component PURL plus its sorted vulnerability ids. Sorting matters — Dependency-Track's array order is not stable, and an unsorted fingerprint would make every run look like new information.

**This method is the only producer of a fingerprint.** The agent is never asked to emit one, and `cve-fix-schema.json` has no `fingerprint` field: a model-emitted string compared against a Java-computed one would differ on any deviation, and every component would look like new information forever — exactly the failure the sorting note above warns about. Task 5's `record(...)` looks the value up from the matching `ComponentFindings`.

- [ ] **Step 1: Write the failing test**

Create `software-factory/src/test/java/com/simonrowe/factory/cvefix/dependencytrack/DependencyTrackClientTest.java`:

```java
package com.simonrowe.factory.cvefix.dependencytrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DependencyTrackClientTest {

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> seenApiKeys = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/api/v1/",
        exchange -> {
          String key = exchange.getRequestURI().getPath();
          seenApiKeys.put(key, String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
          byte[] body = responses.getOrDefault(key, "[]").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private DependencyTrackClient client() {
    CveFixProperties.DependencyTrack config =
        new CveFixProperties.DependencyTrack(
            "http://localhost:" + server.getAddress().getPort(),
            "test-key",
            List.of("simonrowe-dev/backend"),
            Duration.ofSeconds(5));
    return new DependencyTrackClient(config, new ObjectMapper());
  }

  @Test
  void fetchesUnsuppressedFindingsAndSendsTheApiKey() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"},
         {"name":"simonrowe-dev/container","uuid":"u2"}]
        """);
    responses.put(
        "/api/v1/finding/project/u1",
        """
        [{"component":{"purl":"pkg:maven/com.foo/bar@1.0","name":"bar","version":"1.0"},
          "vulnerability":{"vulnId":"CVE-1","severity":"HIGH","recommendation":"upgrade"},
          "analysis":{"isSuppressed":false}},
         {"component":{"purl":"pkg:maven/com.foo/bar@1.0","name":"bar","version":"1.0"},
          "vulnerability":{"vulnId":"CVE-2","severity":"LOW"},
          "analysis":{"isSuppressed":true}}]
        """);

    List<Finding> findings = client().findings();

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).vulnerabilityId()).isEqualTo("CVE-1");
    assertThat(findings.get(0).purl()).isEqualTo("pkg:maven/com.foo/bar@1.0");
    assertThat(findings.get(0).recommendation()).isEqualTo("upgrade");
    assertThat(seenApiKeys.get("/api/v1/project")).isEqualTo("test-key");
  }

  @Test
  void toleratesAMissingRecommendation() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"}]
        """);
    responses.put(
        "/api/v1/finding/project/u1",
        """
        [{"component":{"purl":"pkg:npm/left-pad@1.0.0","name":"left-pad","version":"1.0.0"},
          "vulnerability":{"vulnId":"CVE-3","severity":"MEDIUM"},
          "analysis":{}}]
        """);

    assertThat(client().findings().get(0).recommendation()).isEmpty();
  }

  @Test
  void throwsWhenAnInScopeProjectIsMissing() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/frontend","uuid":"u9"}]
        """);

    assertThatThrownBy(() -> client().findings())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simonrowe-dev/backend");
  }

  @Test
  void throwsWhenDependencyTrackReturnsAnError() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"}]
        """);
    statuses.put("/api/v1/finding/project/u1", 503);

    assertThatThrownBy(() -> client().findings())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("503");
  }

  @Test
  void groupsFindingsByComponentWithASortedFingerprint() {
    List<Finding> findings =
        List.of(
            new Finding("pkg:maven/a/b@1", "b", "1", "CVE-9", "HIGH", ""),
            new Finding("pkg:maven/a/b@1", "b", "1", "CVE-1", "LOW", ""),
            new Finding("pkg:npm/c@2", "c", "2", "CVE-5", "HIGH", ""));

    List<ComponentFindings> grouped = ComponentFindings.group(findings);

    assertThat(grouped).hasSize(2);
    ComponentFindings first =
        grouped.stream().filter(g -> g.purl().equals("pkg:maven/a/b@1")).findFirst().orElseThrow();
    assertThat(first.vulnerabilityIds()).containsExactly("CVE-1", "CVE-9");
    assertThat(first.fingerprint()).isEqualTo("pkg:maven/a/b@1|CVE-1,CVE-9");
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*DependencyTrackClientTest*'`

Expected: compilation failure — the `dependencytrack` and `domain` classes do not exist.

- [ ] **Step 3: Create the domain records**

`Finding.java`:

```java
package com.simonrowe.factory.cvefix.domain;

/**
 * One Dependency-Track finding: a vulnerability against a specific component version.
 *
 * <p>{@code recommendation} is the advisory's free-text prose and is frequently empty.
 * Dependency-Track exposes no fixed-version field, so the target version is the agent's to
 * determine.
 */
public record Finding(
    String purl,
    String componentName,
    String componentVersion,
    String vulnerabilityId,
    String severity,
    String recommendation) {
}
```

`ComponentFindings.java`:

```java
package com.simonrowe.factory.cvefix.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Every finding against one component, so a single bump can clear several advisories. */
public record ComponentFindings(
    String purl,
    String componentName,
    String componentVersion,
    List<String> vulnerabilityIds,
    List<Finding> findings) {

  public ComponentFindings {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /**
   * Groups findings by component PURL, returned sorted by PURL.
   *
   * <p>The order is the sort, not the input order: Dependency-Track's array order is not stable,
   * so a run-to-run-stable prompt and a stable set of grouped components need an explicit sort.
   * {@code LinkedHashMap} only keeps the intermediate grouping deterministic while it is built.
   */
  public static List<ComponentFindings> group(final List<Finding> findings) {
    Map<String, List<Finding>> byPurl =
        findings.stream()
            .collect(
                Collectors.groupingBy(Finding::purl, LinkedHashMap::new, Collectors.toList()));
    return byPurl.entrySet().stream()
        .map(
            entry -> {
              Finding first = entry.getValue().get(0);
              List<String> ids =
                  entry.getValue().stream()
                      .map(Finding::vulnerabilityId)
                      .distinct()
                      .sorted()
                      .toList();
              return new ComponentFindings(
                  entry.getKey(), first.componentName(), first.componentVersion(), ids,
                  entry.getValue());
            })
        .sorted(Comparator.comparing(ComponentFindings::purl))
        .toList();
  }

  /**
   * Suppression key: the PURL plus its sorted vulnerability ids. Sorting is essential —
   * Dependency-Track's array order is not stable, and an unsorted key would make every run look
   * like new information and defeat the suppression entirely.
   */
  public String fingerprint() {
    return purl + "|" + String.join(",", vulnerabilityIds);
  }
}
```

- [ ] **Step 4: Create `DependencyTrackClient`**

```java
package com.simonrowe.factory.cvefix.dependencytrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.Finding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads unsuppressed findings for the in-scope Dependency-Track projects.
 *
 * <p>Fails rather than degrading: Dependency-Track shares its Postgres with Langfuse and can be
 * down on its own, and a pull request raised from half a finding set would silently under-report.
 */
@Component
public class DependencyTrackClient {

  private final CveFixProperties.DependencyTrack config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public DependencyTrackClient(
      final CveFixProperties.DependencyTrack config, final ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
  }

  /** Every unsuppressed finding across every in-scope project. */
  public List<Finding> findings() {
    JsonNode projects = get("/api/v1/project?pageSize=100");
    List<Finding> all = new ArrayList<>();
    for (String name : config.projects()) {
      String uuid = uuidFor(projects, name);
      for (JsonNode finding : get("/api/v1/finding/project/" + uuid)) {
        if (finding.path("analysis").path("isSuppressed").asBoolean(false)) {
          continue;
        }
        JsonNode component = finding.path("component");
        JsonNode vulnerability = finding.path("vulnerability");
        all.add(
            new Finding(
                component.path("purl").asText(""),
                component.path("name").asText(""),
                component.path("version").asText(""),
                vulnerability.path("vulnId").asText(""),
                vulnerability.path("severity").asText("UNASSIGNED"),
                vulnerability.path("recommendation").asText("")));
      }
    }
    return List.copyOf(all);
  }

  private static String uuidFor(final JsonNode projects, final String name) {
    for (JsonNode project : projects) {
      if (name.equals(project.path("name").asText())) {
        return project.path("uuid").asText();
      }
    }
    throw new IllegalStateException(
        "Dependency-Track has no project named " + name
            + " — check the project name or whether CI has uploaded its SBOM");
  }

  private JsonNode get(final String path) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .timeout(config.requestTimeout())
            .header("X-Api-Key", config.apiKey())
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Dependency-Track GET " + path + " returned " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new IllegalStateException("Dependency-Track GET " + path + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling Dependency-Track", exception);
    }
  }
}
```

`DependencyTrackClient` takes `CveFixProperties.DependencyTrack`, a nested record that is not itself a bean, so Spring cannot inject it directly. Expose it with a `@Bean` method — keep the constructor signature the test above uses. Create `.../cvefix/config/CveFixBeans.java`:

```java
package com.simonrowe.factory.cvefix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes nested configuration records as beans so components can depend on them directly. */
@Configuration
public class CveFixBeans {

  /** The Dependency-Track slice of {@link CveFixProperties}, for {@code DependencyTrackClient}. */
  @Bean
  public CveFixProperties.DependencyTrack dependencyTrackProperties(
      final CveFixProperties properties) {
    return properties.dependencyTrack();
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*DependencyTrackClientTest*'`

Expected: 5 tests PASS.

- [ ] **Step 6: Run checks and commit**

```bash
cd software-factory && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: read unsuppressed findings from Dependency-Track

Skips findings audited as suppressed, passes the advisory recommendation through
verbatim (Dependency-Track exposes no fixed-version field), and fails rather than
returning a partial finding set. Groups by component with a sorted fingerprint so
the suppression key is stable across runs."
```

---

### Task 4: Persistence

**Files:**
- Create: `.../cvefix/persistence/CveFixRunRecord.java`, `CveFixRunRepository.java`, `UnfixableFindingRecord.java`, `UnfixableFindingRepository.java`, `CveFixIndexInitializer.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/persistence/CveFixRepositoriesTest.java`

**Interfaces:**
- Consumes: `CveFixStatus`, `ComponentFindings.fingerprint()`.
- Produces:
  - `CveFixRunRecord(String id, String workflowId, Instant startedAt, CveFixStatus status, int findingsSeen, List<String> bumps, String prUrl, int ciAttempts, String detail)` with `static String idFor(String workflowId)`
  - `CveFixRunRepository extends MongoRepository<CveFixRunRecord, String>`
  - `UnfixableFindingRecord(String id, String purl, String fingerprint, List<String> vulnerabilityIds, String reason, Instant recordedAt)` with `static String idFor(String purl)`
  - `UnfixableFindingRepository extends MongoRepository<UnfixableFindingRecord, String>` with `Optional<UnfixableFindingRecord> findByPurl(String purl)`

**Context the implementer needs:**

`software_factory` is the factory's own database and does **not** use Mongock — index creation is an `ApplicationRunner`, following `LearningIndexInitializer`. Read that class before writing yours; in particular copy its `@ConditionalOnProperty` gate. Without the gate, an unreachable Mongo fails the whole application context and takes the GitHub webhook receiver and the `code-review` worker down with it, neither of which touches Mongo.

`UnfixableFindingRecord` is keyed by PURL (one row per component), and stores the `fingerprint` of the finding set that was given up on. Task 5 compares the *current* fingerprint against the stored one, and computes the stored value itself from `ComponentFindings.fingerprint()` — the agent never supplies it.

**Who populates `CveFixRunRecord.id` and `startedAt`:** the *workflow* does, and it must use Temporal's deterministic clock and id. `id` is `CveFixRunRecord.idFor(Workflow.getInfo().getWorkflowId())` — one record per run, so the workflow id is the natural key and a re-drive upserts rather than duplicating. `startedAt` is `Instant.ofEpochMilli(Workflow.currentTimeMillis())`. **Never `Instant.now()`**: workflow code is replayed, so a wall-clock read there is non-deterministic. `recordRun` (Task 9) is a plain save and invents nothing.

- [ ] **Step 1: Write the failing test**

Create `software-factory/src/test/java/com/simonrowe/factory/cvefix/persistence/CveFixRepositoriesTest.java`:

Use `@DataMongoTest`, **not** `@SpringBootTest`, and copy `LearningRepositoryTest`'s exact setup. This matters: `@SpringBootTest` boots the Temporal worker, which connects eagerly at startup and fails the context against a real address — `FactoryApplicationTest` only works because it sets `spring.temporal.test-server.enabled=true`. A repository test needs no Temporal at all, so take the slice. Note also the URI form (`getConnectionString() + "/db"`, not `getReplicaSetUrl(...)`) and the `mongo:8` tag, both matching the existing test.

```java
package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class CveFixRepositoriesTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private CveFixRunRepository runs;
  @Autowired private UnfixableFindingRepository unfixable;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void roundTripsARunRecord() {
    CveFixRunRecord saved =
        runs.save(
            new CveFixRunRecord(
                CveFixRunRecord.idFor("cve-fix-wf-1"), "cve-fix-wf-1",
                Instant.parse("2026-08-11T00:00:00Z"),
                CveFixStatus.COMPLETED, 7, List.of("bar 1.0 -> 1.1"),
                "https://github.com/o/r/pull/1", 2, null));

    assertThat(runs.findById("cve-fix-wf-1")).contains(saved);
  }

  @Test
  void findsAnUnfixableComponentByPurl() {
    unfixable.save(
        new UnfixableFindingRecord(
            UnfixableFindingRecord.idFor("pkg:maven/a/b@1"),
            "pkg:maven/a/b@1",
            "pkg:maven/a/b@1|CVE-1,CVE-9",
            List.of("CVE-1", "CVE-9"),
            "no released version clears CVE-9",
            Instant.parse("2026-08-11T00:00:00Z")));

    assertThat(unfixable.findByPurl("pkg:maven/a/b@1"))
        .get()
        .extracting(UnfixableFindingRecord::fingerprint)
        .isEqualTo("pkg:maven/a/b@1|CVE-1,CVE-9");
    assertThat(unfixable.findByPurl("pkg:npm/absent@1")).isEmpty();
  }

  @Test
  void indexInitializerCreatesAUniquePurlIndex() {
    // CveFixIndexInitializer is an ApplicationRunner and is gated on the feature flag, so it
    // does not run inside this slice. Drive it directly — that also proves it is idempotent,
    // which matters because it runs on every restart.
    CveFixIndexInitializer initializer = new CveFixIndexInitializer(mongoTemplate);
    initializer.run(null);
    initializer.run(null);

    assertThat(mongoTemplate.indexOps(UnfixableFindingRecord.class).getIndexInfo())
        .anySatisfy(
            index -> {
              assertThat(index.getName()).isEqualTo("purl");
              assertThat(index.isUnique()).isTrue();
            });
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixRepositoriesTest*'`

Expected: compilation failure — the persistence classes do not exist.

- [ ] **Step 3: Create the records and repositories**

`CveFixRunRecord.java`:

```java
package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persisted record of one CVE-fix run: what it saw, what it changed, and how it ended. */
@Document(collection = "cve_fix_runs")
public record CveFixRunRecord(
    @Id String id,
    String workflowId,
    Instant startedAt,
    CveFixStatus status,
    int findingsSeen,
    List<String> bumps,
    String prUrl,
    int ciAttempts,
    String detail) {

  public CveFixRunRecord {
    bumps = bumps == null ? List.of() : List.copyOf(bumps);
  }

  /**
   * Deterministic id for upserts: one record per run, keyed by the Temporal workflow id, so a
   * re-drive of the same run overwrites its own row instead of adding a second one.
   */
  public static String idFor(final String workflowId) {
    return workflowId;
  }
}
```

`UnfixableFindingRecord.java`:

```java
package com.simonrowe.factory.cvefix.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A component the agent could not fix, and the exact finding set it gave up on.
 *
 * <p>One row per component. A later run re-attempts as soon as the current fingerprint differs
 * from the stored one, so a new advisory against the same component is treated as new
 * information while a re-run of the same advisories is not.
 */
@Document(collection = "unfixable_findings")
public record UnfixableFindingRecord(
    @Id String id,
    String purl,
    String fingerprint,
    List<String> vulnerabilityIds,
    String reason,
    Instant recordedAt) {

  public UnfixableFindingRecord {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
  }

  /** Deterministic id for upserts: one record per component. */
  public static String idFor(final String purl) {
    return purl;
  }
}
```

`CveFixRunRepository.java`:

```java
package com.simonrowe.factory.cvefix.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link CveFixRunRecord}, keyed by run id. */
public interface CveFixRunRepository extends MongoRepository<CveFixRunRecord, String> {
}
```

`UnfixableFindingRepository.java`:

```java
package com.simonrowe.factory.cvefix.persistence;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link UnfixableFindingRecord}, keyed by component PURL. */
public interface UnfixableFindingRepository
    extends MongoRepository<UnfixableFindingRecord, String> {

  /** The recorded give-up for a component, if there is one. */
  Optional<UnfixableFindingRecord> findByPurl(String purl);
}
```

`CveFixIndexInitializer.java`:

```java
package com.simonrowe.factory.cvefix.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the CVE-fix indexes at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code.
 *
 * <p>Gated on {@code factory.cvefix.enabled} so an unreachable Mongo cannot fail the whole
 * application context — and with it the GitHub webhook receiver and the {@code code-review}
 * Temporal worker, neither of which has any Mongo dependency — when the feature isn't in use.
 */
@Component
@ConditionalOnProperty(name = "factory.cvefix.enabled", havingValue = "true")
public class CveFixIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  public CveFixIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(UnfixableFindingRecord.class)
        .createIndex(new Index().named("purl").on("purl", Sort.Direction.ASC).unique());
    mongoTemplate
        .indexOps(CveFixRunRecord.class)
        .createIndex(new Index().named("startedAt").on("startedAt", Sort.Direction.DESC));
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixRepositoriesTest*'`

Expected: 3 tests PASS. This is a `@DataMongoTest` slice against Testcontainers Mongo — deliberately *not* the full context, for the reasons in Step 1 — so it proves the mapping and the repositories, nothing else. Property-binding and bean-wiring coverage comes from `FactoryApplicationTest`.

- [ ] **Step 5: Run checks and commit**

```bash
cd software-factory && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix/persistence software-factory/src/test/java/com/simonrowe/factory/cvefix/persistence
git commit -m "feat: persist cvefix runs and unfixable components

One unfixable row per component storing the finding fingerprint it gave up on, so
a re-run of the same advisories stays quiet while a new advisory against the same
component re-opens the attempt. Index creation is gated on the feature flag,
following LearningIndexInitializer, so an unreachable Mongo cannot take the
webhook receiver down."
```

---

### Task 5: Suppression of known-unfixable components

**Files:**
- Create: `.../cvefix/persistence/FindingSuppressor.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/persistence/FindingSuppressorTest.java`

**Interfaces:**
- Consumes: `UnfixableFindingRepository`, `ComponentFindings`.
- Produces: `FindingSuppressor.retainActionable(List<ComponentFindings>) -> List<ComponentFindings>` and `FindingSuppressor.record(List<UnfixableComponent> unfixable, List<ComponentFindings> components) -> void` (the latter is added in Task 6 once `UnfixableComponent` exists; for now implement `retainActionable` only).

**Context the implementer needs:**

This is the mechanism that stops a CVE with no available fix burning tokens every night. The rule, exactly:

- A component is **skipped** when a stored record exists for its PURL *and* the stored fingerprint equals the current fingerprint.
- A component is **retained** when there is no record, or when the fingerprint differs — a new advisory against the same component is new information.

Both sides of that comparison must come from `ComponentFindings.fingerprint()`. `record(...)` therefore takes the current `List<ComponentFindings>` alongside the agent's `List<UnfixableComponent>` and looks each PURL up, storing the Java-computed fingerprint and the Dependency-Track vulnerability ids — never anything the model typed. Storing a model-emitted fingerprint here would make the comparison in `retainActionable` fail on any deviation, so every component would look like new information forever and the whole mechanism would be silently dead. An `UnfixableComponent` whose PURL is not in the current set is dropped with a warning: the agent invented it, and there is no fingerprint to store.

This is plain logic over a repository, deliberately not an agent decision, so it is cheap to test exhaustively.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FindingSuppressorTest {

  @Mock private UnfixableFindingRepository repository;
  @InjectMocks private FindingSuppressor suppressor;

  private static ComponentFindings component(final String purl, final String... ids) {
    return new ComponentFindings(purl, "name", "1.0", List.of(ids), List.of());
  }

  private static UnfixableFindingRecord record(final String purl, final String fingerprint) {
    return new UnfixableFindingRecord(
        purl, purl, fingerprint, List.of(), "no fix", Instant.EPOCH);
  }

  @Test
  void retainsComponentsWithNoRecord() {
    when(repository.findByPurl("pkg:maven/a/b@1")).thenReturn(Optional.empty());

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .hasSize(1);
  }

  @Test
  void skipsComponentsWhoseFingerprintIsUnchanged() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1")));

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .isEmpty();
  }

  @Test
  void retainsComponentsWhenANewAdvisoryAppears() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1")));

    assertThat(
            suppressor.retainActionable(
                List.of(component("pkg:maven/a/b@1", "CVE-1", "CVE-2"))))
        .hasSize(1);
  }

  @Test
  void retainsComponentsWhenAnAdvisoryIsWithdrawn() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1,CVE-2")));

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .hasSize(1);
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*FindingSuppressorTest*'`

Expected: compilation failure — `FindingSuppressor` does not exist.

- [ ] **Step 3: Implement it**

```java
package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Drops components already recorded as unfixable, unless their finding set has changed.
 *
 * <p>This is what stops an advisory with no available fix costing an agent run every night. It is
 * deliberately plain logic rather than an agent judgement.
 */
@Component
public class FindingSuppressor {

  private final UnfixableFindingRepository repository;

  public FindingSuppressor(final UnfixableFindingRepository repository) {
    this.repository = repository;
  }

  /** The components worth attempting this run. */
  public List<ComponentFindings> retainActionable(final List<ComponentFindings> components) {
    return components.stream().filter(this::isActionable).toList();
  }

  private boolean isActionable(final ComponentFindings component) {
    return repository
        .findByPurl(component.purl())
        .map(record -> !record.fingerprint().equals(component.fingerprint()))
        .orElse(true);
  }
}
```

- [ ] **Step 4: Run the tests, check, and commit**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*FindingSuppressorTest*' && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix/persistence software-factory/src/test/java/com/simonrowe/factory/cvefix/persistence
git commit -m "feat: suppress components already recorded as unfixable

Skips a component only when its stored fingerprint matches the current finding
set, so a new or withdrawn advisory re-opens the attempt while a nightly re-run
of the same advisories stays quiet."
```

Expected: 4 tests PASS, then BUILD SUCCESSFUL.

---

### Task 6: The fix agent

**Files:**
- Create: `.../cvefix/domain/Bump.java`, `UnfixableComponent.java`, `FixProposal.java`
- Create: `.../cvefix/agent/FixEngine.java`, `ClaudeCliFixEngine.java`
- Create: `software-factory/src/main/resources/cve-fix-schema.json`
- Modify: `.../cvefix/persistence/FindingSuppressor.java` (add `record(...)`)
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/agent/ClaudeCliFixEngineTest.java`

**Interfaces:**
- Consumes: `ClaudeCliRunner.runStructured(Invocation, Consumer<String>)`, `CveFixProperties.Agent`, `ComponentFindings`, `RepositoryWorkspace`.
- Produces:
  - `Bump(String purl, String file, String fromVersion, String toVersion, List<String> clears)`
  - `UnfixableComponent(String purl, List<String> vulnerabilityIds, String reason)` — **no `fingerprint` field.** The agent does not compute suppression keys; `FindingSuppressor.record` derives the fingerprint from the matching `ComponentFindings` (see Task 5).
  - `FixProposal(List<Bump> bumps, List<UnfixableComponent> unfixable, String summary)`
  - `FixEngine.propose(RepositoryWorkspace workspace, List<ComponentFindings> components, String failureContext, Consumer<String> heartbeat) -> FixProposal`
  - `FindingSuppressor.record(List<UnfixableComponent> unfixable, List<ComponentFindings> components) -> void`

**Context the implementer needs:**

Read `ClaudeCliHarvestEngine` first — it is the pattern for this class: load a JSON schema from resources in the constructor, build a `ClaudeCliRunner.Invocation`, call `runStructured`, and map the returned node with `objectMapper.treeToValue`.

Differences for this engine:

- The working directory is the **repository checkout** (`workspace.repository()`), not a scratch temp dir, because the agent must edit real manifests.
- Tools must include `Edit` and `Write`, scoped to the four allowlisted files — read from `CveFixAllowedFiles.ALL` (Task 2), never re-listed here. `ClaudeCliRunner` already appends `Edit(./.git/**)` and `Write(./.git/**)` to `--disallowedTools`, so `.git` is covered for you.
- The agent is **not** asked for a fingerprint. It reports `purl`, `vulnerabilityIds` and `reason`; the suppression key is Java's to compute (Task 5).
- `failureContext` is null on the first attempt and carries CI failure logs on a repair attempt. The prompt branches on it.
- The agent must **not** run builds — there is no toolchain in the container. Do not grant `Bash`.

The prompt's procedure is derived from `dependency-cve-fix/SKILL.md` in `agent-setup`. Keep them consistent; if you improve one, improve the other.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.cvefix.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCliFixEngineTest {

  @Test
  void grantsEditAndWriteOnlyOnTheAllowlistedManifests() {
    List<String> allowed = ClaudeCliFixEngine.allowedTools();

    assertThat(allowed)
        .contains(
            "Edit(./gradle/libs.versions.toml)",
            "Edit(./backend/build.gradle.kts)",
            "Edit(./frontend/package.json)",
            "Edit(./frontend/package-lock.json)");
    assertThat(allowed).noneMatch(tool -> tool.startsWith("Bash"));
  }

  @Test
  void doesNotGrantBashBecauseThereIsNoBuildToolchainInTheContainer() {
    assertThat(ClaudeCliFixEngine.tools()).doesNotContain("Bash");
  }

  @Test
  void promptIncludesTheFailureContextOnARepairAttempt() {
    String prompt = ClaudeCliFixEngine.prompt(List.of(), "gradle: cannot find symbol Foo");

    assertThat(prompt).contains("gradle: cannot find symbol Foo");
    assertThat(prompt).contains("previous attempt");
  }

  @Test
  void promptOmitsTheRepairSectionOnAFirstAttempt() {
    assertThat(ClaudeCliFixEngine.prompt(List.of(), null)).doesNotContain("previous attempt");
  }

  @Test
  void rejectsStructuredOutputThatDoesNotMatchTheSchema() {
    ObjectMapper mapper = new ObjectMapper();
    assertThatThrownBy(() -> ClaudeCliFixEngine.parse(mapper, mapper.readTree("{\"nope\":1}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema");
  }

  @Test
  void parsesAValidProposal() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var node =
        mapper.readTree(
            """
            {"summary":"one bump",
             "bumps":[{"purl":"pkg:maven/a/b@1","file":"gradle/libs.versions.toml",
                       "fromVersion":"1.0","toVersion":"1.1","clears":["CVE-1"]}],
             "unfixable":[]}
            """);

    var proposal = ClaudeCliFixEngine.parse(mapper, node);

    assertThat(proposal.bumps()).hasSize(1);
    assertThat(proposal.bumps().get(0).toVersion()).isEqualTo("1.1");
    assertThat(proposal.unfixable()).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*ClaudeCliFixEngineTest*'`

Expected: compilation failure — the agent package does not exist.

- [ ] **Step 3: Create the domain records**

```java
package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/** One dependency version change the agent made, and the advisories it clears. */
public record Bump(
    String purl, String file, String fromVersion, String toVersion, List<String> clears) {

  public Bump {
    clears = clears == null ? List.of() : List.copyOf(clears);
  }

  /** Human-readable one-liner for the pull request body and the run record. */
  public String describe() {
    return componentOf(purl) + " " + fromVersion + " -> " + toVersion
        + " (" + String.join(", ", clears) + ")";
  }

  private static String componentOf(final String value) {
    int at = value.lastIndexOf('@');
    return at > 0 ? value.substring(0, at) : value;
  }
}
```

```java
package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/**
 * A component the agent declined to bump, with the reason. Recorded so the same advisories do
 * not cost an agent run every night.
 *
 * <p>Carries no fingerprint on purpose: the suppression key is computed in Java from the matching
 * {@code ComponentFindings}. A model-emitted key would be compared against a Java-computed one and
 * would silently disable suppression on any deviation.
 */
public record UnfixableComponent(String purl, List<String> vulnerabilityIds, String reason) {

  public UnfixableComponent {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
  }
}
```

```java
package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/** What one agent attempt produced. */
public record FixProposal(List<Bump> bumps, List<UnfixableComponent> unfixable, String summary) {

  public FixProposal {
    bumps = bumps == null ? List.of() : List.copyOf(bumps);
    unfixable = unfixable == null ? List.of() : List.copyOf(unfixable);
  }

  /** True when the agent changed nothing, so there is no pull request to open. */
  public boolean isEmpty() {
    return bumps.isEmpty();
  }
}
```

- [ ] **Step 4: Create `cve-fix-schema.json`**

Create `software-factory/src/main/resources/cve-fix-schema.json`:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["summary", "bumps", "unfixable"],
  "properties": {
    "summary": {
      "type": "string",
      "description": "One paragraph describing what was changed and what was left alone."
    },
    "bumps": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["purl", "file", "fromVersion", "toVersion", "clears"],
        "properties": {
          "purl": { "type": "string" },
          "file": {
            "type": "string",
            "description": "Must stay in sync with CveFixAllowedFiles.ALL — JSON Schema cannot reference a Java constant, so this enum is the one intentional duplicate of the allowlist.",
            "enum": [
              "gradle/libs.versions.toml",
              "backend/build.gradle.kts",
              "frontend/package.json",
              "frontend/package-lock.json"
            ]
          },
          "fromVersion": { "type": "string" },
          "toVersion": { "type": "string" },
          "clears": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "unfixable": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["purl", "vulnerabilityIds", "reason"],
        "properties": {
          "purl": { "type": "string" },
          "vulnerabilityIds": { "type": "array", "items": { "type": "string" } },
          "reason": {
            "type": "string",
            "description": "Which case applies: no released version clears the advisory; the only fix needs a major upgrade of something else; or transitive-only with no newer direct release."
          }
        }
      }
    }
  }
}
```

- [ ] **Step 5: Create `FixEngine` and `ClaudeCliFixEngine`**

```java
package com.simonrowe.factory.cvefix.agent;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.git.RepositoryWorkspace;
import java.util.List;
import java.util.function.Consumer;

/** Proposes dependency bumps for a set of findings by editing the checkout in place. */
public interface FixEngine {

  /**
   * Edits the manifests in {@code workspace} and describes what it did.
   *
   * @param failureContext CI failure output from the previous attempt, or null on the first
   *     attempt
   */
  FixProposal propose(
      RepositoryWorkspace workspace,
      List<ComponentFindings> components,
      String failureContext,
      Consumer<String> heartbeat);
}
```

```java
package com.simonrowe.factory.cvefix.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.cvefix.config.CveFixAllowedFiles;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.git.RepositoryWorkspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Bumps vulnerable dependencies with a headless Claude run.
 *
 * <p>The agent is given no {@code Bash} tool: the container carries no Gradle, Node or Docker, so
 * verification happens in CI. It also holds no credential — the Dependency-Track key lives in a
 * Java activity, and {@link ClaudeCliRunner} strips everything outside its allowlist.
 */
@Component
public class ClaudeCliFixEngine implements FixEngine {

  private final CveFixProperties properties;
  private final ClaudeCliRunner runner;
  private final ObjectMapper objectMapper;
  private final String schema;

  public ClaudeCliFixEngine(
      final CveFixProperties properties,
      final ClaudeCliRunner runner,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.runner = runner;
    this.objectMapper = objectMapper;
    this.schema = loadSchema();
  }

  @Override
  public FixProposal propose(
      final RepositoryWorkspace workspace,
      final List<ComponentFindings> components,
      final String failureContext,
      final Consumer<String> heartbeat) {
    heartbeat.accept(
        failureContext == null
            ? "Proposing bumps for " + components.size() + " components"
            : "Repairing after a failed build");
    JsonNode structured =
        runner.runStructured(
            new ClaudeCliRunner.Invocation(
                properties.agent().command(),
                properties.agent().model(),
                properties.agent().effort(),
                properties.agent().maxTurns(),
                properties.agent().timeout(),
                tools(),
                allowedTools(),
                schema,
                prompt(components, failureContext),
                workspace.repository()),
            heartbeat);
    return parse(objectMapper, structured);
  }

  static List<String> tools() {
    return List.of("Read", "Glob", "Grep", "Edit", "Write");
  }

  static List<String> allowedTools() {
    List<String> allowed = new ArrayList<>(List.of("Read(./**)", "Glob", "Grep"));
    for (String file : CveFixAllowedFiles.ALL) {
      allowed.add("Edit(./" + file + ")");
      allowed.add("Write(./" + file + ")");
    }
    return List.copyOf(allowed);
  }

  static FixProposal parse(final ObjectMapper objectMapper, final JsonNode structured) {
    try {
      FixProposal proposal = objectMapper.treeToValue(structured, FixProposal.class);
      if (proposal.summary() == null) {
        throw new IllegalStateException("Fix output did not match the cve-fix schema: no summary");
      }
      return proposal;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Fix output did not match the cve-fix schema", exception);
    }
  }

  static String prompt(final List<ComponentFindings> components, final String failureContext) {
    StringBuilder findings = new StringBuilder();
    for (ComponentFindings component : components) {
      findings
          .append("- ")
          .append(component.purl())
          .append(" (")
          .append(component.componentName())
          .append("@")
          .append(component.componentVersion())
          .append(") advisories: ")
          .append(String.join(", ", component.vulnerabilityIds()))
          .append("\n");
      component.findings().forEach(
          finding ->
              findings
                  .append("    ")
                  .append(finding.vulnerabilityId())
                  .append(" [")
                  .append(finding.severity())
                  .append("] ")
                  .append(finding.recommendation().isBlank()
                      ? "no advisory recommendation"
                      : finding.recommendation())
                  .append("\n"));
    }

    String repair =
        failureContext == null
            ? ""
            : """

            The previous attempt was pushed and CI failed. Its output follows. Fix the cause:
            either correct the call sites, or choose a different target version and say so in
            the summary. If a bump cannot be made to build, drop it and move it to unfixable.

            ```
            %s
            ```
            """
                .formatted(failureContext);

    return """
        You are patching vulnerable dependencies in the simonrowe.dev monorepo. You are in a
        clean checkout of the default branch.

        Hard constraints:
        - You may edit ONLY these files: %s. A change anywhere else fails the run.
        - You have no Bash tool and no build toolchain. Do NOT attempt to build or test. CI
          verifies your change after it is pushed.
        - Never edit source code to work around a breaking change on a first attempt. Prefer the
          smallest version bump that clears the advisory.

        Dependency-Track findings to address:
        %s
        Process:
        1. Read the manifests to find where each component's version is declared. Most backend
           versions live in gradle/libs.versions.toml, not backend/build.gradle.kts.
        2. For each component, pick the lowest version that clears every listed advisory.
           Dependency-Track does not tell you the fixed version — infer it from the advisory
           recommendation above and from the version strings already in the manifests.
        3. Apply the edit. For frontend/package-lock.json, update the matching "version" and
           "resolved"/"integrity" entries consistently, or use an overrides entry in
           package.json if the component is transitive only.
        4. Anything you cannot fix goes in unfixable, with its purl, its advisory ids exactly as
           listed above, and a reason stating which case applies: no released version clears the
           advisory; the only fix needs a major upgrade of something else; or it is
           transitive-only with no newer direct release.
        5. A partial result is good. Bumping four of six components beats attempting all six
           and breaking the build.
        %s
        Produce the requested structured result.
        """
        .formatted(String.join(", ", CveFixAllowedFiles.ALL), findings, repair);
  }

  private static String loadSchema() {
    try (InputStream input =
        ClaudeCliFixEngine.class.getResourceAsStream("/cve-fix-schema.json")) {
      if (input == null) {
        throw new IllegalStateException("cve-fix-schema.json is missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load cve-fix schema", exception);
    }
  }
}
```

- [ ] **Step 6: Add `record(...)` to `FindingSuppressor`**

Append to `FindingSuppressor`, adding `import com.simonrowe.factory.cvefix.domain.UnfixableComponent;`, `import java.time.Instant;`, `import java.util.Map;`, `import java.util.function.Function;`, `import java.util.stream.Collectors;` and a logger:

```java
  /**
   * Upserts the give-up records so later runs skip these components until something changes.
   *
   * <p>The stored fingerprint and ids come from {@code components} — the Dependency-Track data
   * that {@link #retainActionable} will compare against — never from the agent's output. A
   * model-emitted key would be compared against a Java-computed one and would disable suppression
   * entirely on any deviation.
   */
  public void record(
      final List<UnfixableComponent> unfixable, final List<ComponentFindings> components) {
    Map<String, ComponentFindings> byPurl =
        components.stream()
            .collect(Collectors.toMap(ComponentFindings::purl, Function.identity(), (a, b) -> a));
    for (UnfixableComponent component : unfixable) {
      ComponentFindings current = byPurl.get(component.purl());
      if (current == null) {
        // The agent named a component Dependency-Track did not report. There is no fingerprint
        // to store, and storing a guess would suppress something that was never seen.
        LOGGER.warn("Ignoring unfixable purl not in this run's findings: {}", component.purl());
        continue;
      }
      repository.save(
          new UnfixableFindingRecord(
              UnfixableFindingRecord.idFor(current.purl()),
              current.purl(),
              current.fingerprint(),
              current.vulnerabilityIds(),
              component.reason(),
              Instant.now()));
    }
  }
```

`Instant.now()` is correct here: this runs inside an activity, not in workflow code.

Add three tests to `FindingSuppressorTest`:

- `recordStoresTheJavaComputedFingerprintNotTheAgentsView` — one `UnfixableComponent`, one matching `ComponentFindings`; `verify(repository).save(argThat(r -> r.fingerprint().equals("pkg:maven/a/b@1|CVE-1,CVE-9")))`.
- `recordStoresOneRowPerComponent` — two components, `verify(repository, times(2)).save(any())`.
- `recordIgnoresAPurlThatIsNotInTheCurrentFindings` — `verifyNoInteractions(repository)`.

The first of those is the regression test for the whole mechanism: it is what fails if anyone reintroduces a `fingerprint` field on `UnfixableComponent`.

- [ ] **Step 7: Run the tests, check, and commit**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*ClaudeCliFixEngineTest*' --tests '*FindingSuppressorTest*' && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/main/resources/cve-fix-schema.json software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: add the cvefix agent

Edits only the four allowlisted manifests (from CveFixAllowedFiles.ALL, the single
source), gets no Bash tool because the container carries no build toolchain, and
reports what it could not fix with a reason. Suppression fingerprints are computed
in Java from the Dependency-Track data, never emitted by the model, so the
comparison in retainActionable cannot drift. The repair prompt branches on CI
failure output."
```

Expected: all tests PASS, BUILD SUCCESSFUL.

---

### Task 7: Pull request gateway

**Files:**
- Create: `.../cvefix/github/CveFixPrGateway.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/github/CveFixPrGatewayTest.java`

**Interfaces:**
- Consumes: `CveFixProperties`, `GitHubCredentials.accessToken(Long)`, `CodeReviewProperties.github().apiBaseUrl()`.
- Produces:
  - `CveFixPrGateway.findOpen() -> Optional<OpenPullRequest>` where `OpenPullRequest(int number, String htmlUrl, String headSha)` — **the only record for this concept.** Task 9's activity interface reuses `CveFixPrGateway.OpenPullRequest`; there is no separate `PullRequestRef`.
  - `CveFixPrGateway.open(String title, String body) -> OpenPullRequest`
  - `CveFixPrGateway.comment(int number, String body) -> void`

**Context the implementer needs:**

Model this on `FeedbackPrGateway` — same JDK `HttpClient`, same `X-GitHub-Api-Version` header, same installation-token auth. Read it first.

The title and body passed to `open(...)` are rendered from `CveFixActivities.FixSummary` by `CveFixPrBodyRenderer`, created in Task 9 where `FixSummary` exists. This gateway does no formatting.

- `findOpen()` queries `GET /repos/{owner}/{repo}/pulls?head={owner}:{branch}&state=open`. An empty array means no open CVE pull request. This is the step-1 skip check, so it must be exact: a 404 or a network error must throw, not be read as "none open", or the workflow would open a second pull request.
- `open(...)` posts to `/pulls` with `draft: false` — explicitly, not by omission, so the intent is visible in the code. On `422` (already exists), fall back to `findOpen()` and return that, as `FeedbackPrGateway` does.
- There is deliberately **no** `headSha(int)` method. Nothing needs it: `PushResult.headSha` carries the SHA the workflow just pushed, and `OpenPullRequest.headSha` carries it for a pull request that was discovered rather than created.

- [ ] **Step 1: Write the failing test**

Follow the `DependencyTrackClientTest` pattern: a `com.sun.net.httpserver.HttpServer` on port 0, with a map of path → response. Assert:

```java
  @Test
  void findOpenReturnsEmptyWhenNoPullRequestIsOpen() { /* "[]" -> Optional.empty() */ }

  @Test
  void findOpenReturnsTheNumberUrlAndHeadSha() {
    // [{"number":7,"html_url":"https://github.com/o/r/pull/7","head":{"sha":"abc"}}]
    // -> OpenPullRequest(7, ".../pull/7", "abc")
  }

  @Test
  void findOpenThrowsOnAnErrorResponseRatherThanReportingNoneOpen() {
    // 503 -> IllegalStateException. Reading an error as "none open" would open a second PR.
  }

  @Test
  void findOpenQueriesTheConfiguredBranchAsTheHeadFilterUrlEncoded() {
    // FeedbackPrGateway builds this as "?head=" + owner + ":" + URLEncoder.encode(branch, UTF_8)
    // + "&state=open" — the owner and the colon are raw, only the branch is encoded, so the
    // slash arrives as %2F. Assert that exact form; asserting the raw branch name would fail
    // against the implementation this task specifies.
    // assert the request URI contains
    //   head=simonjamesrowe:chore%2Fdependency-cve-fixes&state=open
  }

  @Test
  void openSendsDraftFalseExplicitly() {
    // assert the POST body has "draft":false — the code-review bot ignores drafts
  }

  @Test
  void openFallsBackToFindingTheExistingPullRequestOn422() { }

  @Test
  void commentPostsToTheIssueCommentsEndpoint() {
    // /repos/o/r/issues/7/comments
  }
```

Write each of these with a real body and real assertions; do not leave the bodies as comments.

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixPrGatewayTest*'`

Expected: compilation failure — `CveFixPrGateway` does not exist.

- [ ] **Step 3: Implement `CveFixPrGateway`**

Copy `FeedbackPrGateway`'s structure: constructor taking `CodeReviewProperties`, `CveFixProperties`, `GitHubCredentials` and `ObjectMapper`; a private `send(String method, String path, JsonNode body, String token)`; a `requireSuccess` helper. Add the nested record:

```java
  /** An open CVE pull request: the number, its URL, and the head commit CI runs against. */
  public record OpenPullRequest(int number, String htmlUrl, String headSha) {
  }
```

Comment the `draft` field where you set it:

```java
      // Explicitly false, not omitted: software-factory's own code-review webhook ignores draft
      // pull requests, so a draft CVE PR would silently never be reviewed. Drafts also save no
      // CI, since pull_request fires for them anyway.
      payload.put("draft", false);
```

- [ ] **Step 4: Run the tests, check, and commit**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*CveFixPrGatewayTest*' && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix/github software-factory/src/test/java/com/simonrowe/factory/cvefix/github
git commit -m "feat: add the cvefix pull request gateway

findOpen throws on an error response rather than reporting none-open, because
misreading that would open a second CVE pull request. Pull requests are created
with draft:false explicitly, since the code-review bot ignores drafts."
```

---

### Task 8: CI status gateway

**Files:**
- Create: `.../cvefix/domain/CiOutcome.java`
- Create: `.../cvefix/github/CiStatusGateway.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/github/CiStatusGatewayTest.java`

**Interfaces:**
- Consumes: `CveFixProperties` (including `ci().advisoryChecks()`), `CodeReviewProperties.github().apiBaseUrl()`.
- Produces:
  - `CiOutcome(CiState state, List<String> failedCheckNames, String detail)` with `enum CiState { PENDING, GREEN, RED }`
  - `CiStatusGateway.outcomeFor(String headSha) -> CiOutcome`
  - `CiStatusGateway.failureLogs(String headSha) -> String`

**Context the implementer needs:**

**This gateway sends no credential, and that is deliberate.** `GitHubCredentials.mintInstallationToken` requests `contents`/`issues`/`pull_requests` in one shared payload used by code-review and feedback too, and GitHub 422s the *whole* request if the App was not granted a requested permission. Adding `checks: read` there would break both existing flows. Because this repository is public, `GET /repos/{owner}/{repo}/commits/{sha}/check-runs` returns 200 with full check data unauthenticated — verified against `main` at `576eeb2`. Put that reasoning in a class comment so nobody "fixes" it by adding auth.

The cost is the unauthenticated rate limit: 60 requests/hour per IP. The 3-minute default poll interval keeps this near 20/hour. Do not lower it without moving to the installation token.

Interpreting the payload — **first drop every check run whose `name` is in `ci.advisoryChecks()`**, then apply:

- Any remaining check run with `status != "completed"` → `PENDING`.
- All remaining completed and every `conclusion` in `success`, `neutral`, `skipped` → `GREEN`.
- Any remaining completed run with `conclusion` in `failure`, `timed_out`, `cancelled`, `action_required` → `RED`.
- No remaining runs (either `total_count == 0` or every run was advisory) → `PENDING`. CI has not registered yet; treating an empty list as green would merge-signal a pull request no job has touched.

**Why the advisory ignore-list exists, and why it is not optional.** The promptfoo evals job sets job-level `continue-on-error: true`. That is documented to stop the *workflow run* from failing; it is **not** documented to rewrite the check-run `conclusion` GitHub reports for the job. If the `evaluate` check ever surfaces as `conclusion: failure`, this gateway would read RED on every poll of a pull request whose real CI is green: the run would burn the entire repair budget asking the agent to fix an OpenAI-spend problem it cannot see, end `CI_UNRESOLVED`, leave the pull request open, and thereby block every later run through `SKIPPED_PR_OPEN`. That is the worst failure mode in this design, and it must not rest on an undocumented property of `continue-on-error`. The ignore-list makes the exclusion explicit and testable, and it is configuration, so a new advisory job needs no code change.

The gateway must **not** special-case `continue-on-error` in any other way, and must not infer advisory status from the check name's shape — only from the configured list.

- [ ] **Step 1: Write the failing test**

```java
package com.simonrowe.factory.cvefix.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.CiOutcome;
import org.junit.jupiter.api.Test;
// plus the HttpServer scaffolding from DependencyTrackClientTest

class CiStatusGatewayTest {

  @Test
  void reportsPendingWhenNoChecksHaveRegisteredYet() {
    // {"total_count":0,"check_runs":[]} -> PENDING
    // An empty list must never read as GREEN: no job has touched the commit yet.
  }

  @Test
  void reportsPendingWhileAnyCheckIsStillRunning() {
    // one completed/success + one in_progress -> PENDING
  }

  @Test
  void reportsGreenWhenEveryCheckSucceededOrWasSkipped() {
    // success + skipped + neutral -> GREEN
  }

  @Test
  void reportsRedAndNamesTheFailedChecks() {
    // "Backend Build & Test" failure + "Frontend Build & Test" success
    // -> RED, failedCheckNames == ["Backend Build & Test"]
  }

  @Test
  void treatsTimedOutAndCancelledAsRed() { }

  @Test
  void ignoresAnAdvisoryCheckThatReportsFailure() {
    // "Backend Build & Test" success + "evaluate" failure -> GREEN, failedCheckNames empty.
    // This is the regression test for the whole advisory ignore-list: without it a red
    // promptfoo run burns the repair budget and blocks every later run via SKIPPED_PR_OPEN.
  }

  @Test
  void reportsPendingWhenTheOnlyRegisteredCheckIsAdvisory() {
    // "evaluate" success on its own -> PENDING, not GREEN: real CI has not registered yet.
  }

  @Test
  void sendsNoAuthorizationHeaderBecauseTheRepositoryIsPublic() {
    // assert the served request had no Authorization header. Adding one would require
    // checks:read on the shared installation token, which 422s the whole mint.
  }
}
```

Write real bodies for each.

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CiStatusGatewayTest*'`

Expected: compilation failure.

- [ ] **Step 3: Implement `CiOutcome` and `CiStatusGateway`**

```java
package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/** Aggregated CI state for one commit. */
public record CiOutcome(CiState state, List<String> failedCheckNames, String detail) {

  public CiOutcome {
    failedCheckNames = failedCheckNames == null ? List.of() : List.copyOf(failedCheckNames);
  }

  /** Whether CI has finished, and if so whether it passed. */
  public enum CiState {
    /** Checks are still running, or none have registered yet. */
    PENDING,
    /** Every check completed successfully, was neutral, or was skipped. */
    GREEN,
    /** At least one check failed, timed out, was cancelled, or needs action. */
    RED
  }
}
```

Implement `CiStatusGateway` with a plain `HttpClient`, no `Authorization` header, and the class comment explaining why. `failureLogs(headSha)` fetches each failed check run's `output.summary` and `output.text` and concatenates them, truncated to about 8000 characters — enough for the agent to act on, small enough not to blow the prompt.

- [ ] **Step 4: Verify the unauthenticated route still works against the live API**

Run:

```bash
SHA=$(curl -s "https://api.github.com/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/main" | jq -r .sha)
curl -s -o /dev/null -w "check-runs=%{http_code}\n" \
  "https://api.github.com/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/$SHA/check-runs"
curl -s https://api.github.com/rate_limit | jq '.resources.core | {limit, remaining}'
```

Expected: `check-runs=200`, and a `limit` of 60. If this returns `401` or `404`, the repository's visibility has changed and this design decision must be revisited — in that case stop and escalate rather than adding `checks: read` to the shared token mint.

- [ ] **Step 4b: Observe what a failed advisory job actually reports, and record it**

The advisory ignore-list is correct either way, but the plan should not carry an unverified claim about `continue-on-error`. Find a head SHA whose `evaluate` job failed and look at the conclusion GitHub reports for it:

```bash
gh run list --workflow "Promptfoo Evals" --json headSha,conclusion,status --limit 20
SHA=<a headSha whose evals run did not succeed>
curl -s "https://api.github.com/repos/simonjamesrowe/simonrowe-dev-monorepo/commits/$SHA/check-runs" \
  | jq -r '.check_runs[] | "\(.name)\t\(.status)\t\(.conclusion)"'
```

Record the observed `name`/`conclusion` pair for the evals job in a comment on `CiStatusGateway`, stating the SHA it was observed at. If the conclusion is `failure`, the ignore-list is load-bearing and the comment must say so. If it is `success`, the ignore-list is defence in depth — keep it either way; it costs one filter and removes the dependency on an undocumented behaviour. If no failed evals run exists yet, say so in the comment rather than asserting either outcome.

- [ ] **Step 5: Run the tests, check, and commit**

```bash
cd software-factory && ../gradlew :software-factory:test --tests '*CiStatusGatewayTest*' && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: read CI status unauthenticated for the public repo

Avoids adding checks:read to GitHubCredentials.mintInstallationToken, whose
single shared payload is also used by code-review and feedback — GitHub 422s the
whole request if the App lacks a requested permission, so that change would break
both. An empty check-run list is PENDING, never GREEN.

Advisory checks named in factory.cvefix.ci.advisory-checks (default: the promptfoo
evaluate job) are excluded from the RED decision. Job-level continue-on-error
stops the run from failing but is not documented to rewrite the check-run
conclusion, and a red advisory job would otherwise burn the whole repair budget
and block every later run."
```

---

### Task 9: Activities

**Files:**
- Create: `.../cvefix/workflow/CveFixActivities.java`, `CveFixActivitiesImpl.java`
- Create: `.../cvefix/github/CveFixPrBodyRenderer.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/CveFixActivitiesImplTest.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/github/CveFixPrBodyRendererTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces the activity interface the workflow calls. **The Temporal annotations are part of the contract** — `Workflow.newActivityStub` fails without `@ActivityInterface`, and `FeedbackActivities` carries both annotations for the same reason:

```java
package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** Everything the CVE-fix workflow is not allowed to do itself. */
@ActivityInterface
public interface CveFixActivities {

  /** The URL of an already-open CVE pull request, or null when there is none. */
  @ActivityMethod
  String findOpenPrUrl();

  @ActivityMethod
  List<ComponentFindings> fetchActionableFindings();

  /**
   * Clone, run the agent, validate the changed paths, commit and push in one activity.
   * {@code failureContext} is null on the first attempt. A returned {@code headSha} of null means
   * the agent changed nothing.
   */
  @ActivityMethod
  PushResult proposeAndPush(List<ComponentFindings> components, String failureContext);

  /** Renders the title and body from {@code summary}, then opens the pull request. */
  @ActivityMethod
  CveFixPrGateway.OpenPullRequest openPullRequest(FixSummary summary);

  @ActivityMethod
  CiOutcome checkCi(String headSha);

  @ActivityMethod
  String ciFailureLogs(String headSha);

  @ActivityMethod
  void commentOnPullRequest(int number, String body);

  /**
   * Persists the components the agent gave up on, so later runs skip them until their finding
   * set changes. {@code components} is this run's Dependency-Track data, from which the stored
   * fingerprint is computed — the agent never supplies one.
   */
  @ActivityMethod
  void recordUnfixable(List<UnfixableComponent> unfixable, List<ComponentFindings> components);

  @ActivityMethod
  void recordRun(CveFixRunRecord record);

  /** What one push produced: the commit CI will run against, and what to put in the PR body. */
  record PushResult(String headSha, FixSummary summary) {
  }

  /** The pull request body's inputs. */
  record FixSummary(
      List<String> bumpDescriptions, List<UnfixableComponent> unfixable, String agentSummary) {
  }
}
```

Both records are nested in the interface, so they are referred to elsewhere as `CveFixActivities.PushResult` and `CveFixActivities.FixSummary`.

There is **no** `PullRequestRef`: it was the same shape as `CveFixPrGateway.OpenPullRequest` with no conversion specified, so the gateway's record is reused here. One concept, one record.

`recordUnfixable` is what implements the spec's workflow step 8. Without it `FindingSuppressor.record` is never called, no component is ever recorded as unfixable, and step 3's suppression can never fire — the mechanism that stops an unfixable advisory costing an agent run every night would be dead code.

`findOpenPrUrl` returns a nullable `String` rather than `Optional<String>` on purpose. Temporal's default data converter (`JacksonJsonPayloadConverter`, verified in `temporal-sdk-1.36.0`) does register `Jdk8Module` and `JavaTimeModule`, so `Optional` and `Duration` would in fact round-trip — but a nullable String needs no assumption about a converter this module never configures, and it reads the same at the call site. `Duration` **is** used on `CveFixRequest` (Task 10) and relies on `JavaTimeModule` being registered, which the same verification covers.

**Context the implementer needs:**

Temporal activity arguments and return values must be JSON-serialisable — never a `RepositoryWorkspace`, never a `Path`, and no `Optional` (see the note on `findOpenPrUrl` above).

The workspace cannot live across activity boundaries: it is a local temp directory, and an activity may be retried on a different worker. So `proposeAndPush` is a single activity doing clone → agent → validate → commit → push, keeping the entire filesystem lifecycle inside one call with try-with-resources on `RepositoryWorkspace`. It returns `PushResult` because the workflow needs both the head SHA to poll and the summary for the pull request body.

**The activity resolves the GitHub installation id itself.** `RepositoryWorkspaceFactory.create` and `commitAndPush` both take an `installationId`, and nothing configures one (Task 2 deliberately has no such property). Call `credentials.installationId(properties.owner(), properties.repository())` once at the top of `proposeAndPush` and pass the result to both. **Do not pass null.** `GitHubCredentials.accessToken(null)` falls back to the static `GITHUB_TOKEN`, which the `software-factory` compose service does not define — the result is a blank token, an anonymous clone of the public repository that appears to succeed, and then a `403` on push that looks like a permissions bug rather than a missing id.

**Changed-path validation reads `CveFixAllowedFiles.ALL`** — `RepositoryWorkspaceFactory.validateAllowedPaths(changedPaths, CveFixAllowedFiles.ALL)`. Do not re-list the four paths here; that is the copy this constant exists to prevent.

**`openPullRequest` renders, the gateway sends.** `CveFixPrGateway.open` takes a title and a body, and nothing in Task 7 said where they come from: they come from here. Create `CveFixPrBodyRenderer` as a final class of static, pure methods — `String title(FixSummary)`, `String body(FixSummary)` and `String giveUpComment(FixSummary, int ciAttempts)`. The title is fixed prose (`chore: bump dependencies with Dependency-Track findings`); the body lists the `bumpDescriptions`, then the unfixable components with their reasons, then the agent's own summary. `openPullRequest` calls the first two and passes the strings to `CveFixPrGateway.open(title, body)`.

`giveUpComment` lives here so the budget-exhaustion comment and the pull request body cannot describe the same run differently. It is static and side-effect-free, so the workflow may call it directly to build the `commentOnPullRequest` body without breaking determinism. Test all three directly on a `FixSummary` fixture — pure string building, no mocks.

- [ ] **Step 1: Write the failing test**

Test `CveFixActivitiesImpl` with Mockito mocks for `DependencyTrackClient`, `FindingSuppressor`, `RepositoryWorkspaceFactory`, `GitHubCredentials`, `FixEngine`, `CveFixPrGateway`, `CiStatusGateway` and `CveFixRunRepository`. Cover:

```java
  @Test
  void fetchActionableFindingsGroupsThenSuppresses() { }

  @Test
  void proposeAndPushValidatesChangedPathsBeforeCommitting() {
    // agent writes backend/src/main/java/Evil.java -> IllegalStateException, no commit, no push
  }

  @Test
  void proposeAndPushReturnsNullHeadShaWhenTheAgentChangedNothing() {
    // empty proposal -> no commit, no push; the workflow reads this as NOTHING_FIXABLE
  }

  @Test
  void proposeAndPushClosesTheWorkspaceEvenWhenTheAgentThrows() {
    // verify the temp directory is gone afterwards
  }

  @Test
  void proposeAndPushResolvesTheInstallationIdAndPassesItToCloneAndPush() {
    // credentials.installationId("simonjamesrowe", "simonrowe-dev-monorepo") -> 42L;
    // verify create(..., 42L, ...) and commitAndPush(..., 42L, ...). Never null: accessToken(null)
    // falls back to a GITHUB_TOKEN this service does not set, giving an anonymous clone and a 403.
  }

  @Test
  void openPullRequestRendersTheTitleAndBodyFromTheSummary() {
    // verify gateway.open(title, body) with the renderer's output, not an empty or null body
  }

  @Test
  void recordUnfixableDelegatesToTheSuppressorWithThisRunsFindings() {
    // verify(suppressor).record(unfixable, components) — the fingerprint source must be the
    // Dependency-Track data, not the agent's output
  }

  @Test
  void findOpenPrUrlPropagatesGatewayFailures() { }
```

- [ ] **Step 2: Run it to confirm it fails; Step 3: implement; Step 4: run to green**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixActivitiesImplTest*'`

Annotate the implementation `@Component` and `@ActivityImpl(taskQueues = CveFixTaskQueues.CVE_FIX)`, mirroring `FeedbackActivitiesImpl` — read it for the heartbeat pattern (`Activity.getExecutionContext().heartbeat(...)` wrapped as a `Consumer<String>`).

- [ ] **Step 5: Run checks and commit**

```bash
cd software-factory && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: add cvefix activities

The whole workspace lifecycle stays inside proposeAndPush, because a temp
checkout cannot survive an activity boundary and an activity may be retried on
another worker. Changed paths are validated before anything is committed, against
the single CveFixAllowedFiles.ALL constant.

The GitHub installation id is resolved from GitHubCredentials at run time rather
than configured: a null id makes accessToken fall back to a GITHUB_TOKEN this
service does not set, which clones anonymously and then 403s on push.

recordUnfixable implements the spec's step 8 — without it nothing is ever recorded
as unfixable and the suppression in step 3 can never fire."
```

---

### Task 10: Workflow

**Files:**
- Create: `.../cvefix/domain/CveFixRequest.java`
- Create: `.../cvefix/workflow/CveFixWorkflow.java`, `CveFixWorkflowImpl.java`
- Modify: `software-factory/src/main/resources/application.yml` (add this package to `workflow-packages` — Step 5)
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/workflow/CveFixWorkflowTest.java`

**Interfaces:**
- Consumes: `CveFixActivities`.
- Produces: `CveFixWorkflow.run(CveFixRequest request) -> CveFixResult` (`@WorkflowMethod`) and `CveFixWorkflow.progress() -> CveFixProgress` (`@QueryMethod`), plus the request record:

```java
package com.simonrowe.factory.cvefix.domain;

import java.time.Duration;

/**
 * Input to one CVE-fix run.
 *
 * <p>{@code dryRun} stops before the pull request is opened, so the first manual trigger cannot
 * create one.
 *
 * <p>The three CI settings are carried here rather than read from {@code CveFixProperties},
 * because workflow code cannot reach the properties bean: {@code @WorkflowImpl} classes are
 * instantiated by the Temporal SDK, not by Spring, so they have no injected dependencies at all —
 * which is why both existing implementations hold only constants. {@code CveFixScheduleInitializer}
 * holds the bean and copies the values into the request it schedules; the compact constructor
 * below supplies the same defaults so a hand-started workflow with a sparse JSON input still
 * behaves sensibly.
 */
public record CveFixRequest(
    boolean dryRun, Duration pollInterval, int repairBudget, Duration maxWait) {

  public CveFixRequest {
    pollInterval = pollInterval == null ? Duration.ofMinutes(3) : pollInterval;
    repairBudget = repairBudget == 0 ? 3 : repairBudget;
    maxWait = maxWait == null ? Duration.ofHours(3) : maxWait;
  }
}
```

`Duration` round-trips through Temporal's default data converter: `JacksonJsonPayloadConverter.newDefaultObjectMapper` registers `JavaTimeModule` (verified in `temporal-sdk-1.36.0`), and this module configures no custom `DataConverter`.

**Context the implementer needs:**

Read `ReviewFeedbackWorkflowImpl` first. Three of its hard-won lessons apply directly:

1. **Two activity stubs with different options** — short timeouts and retries for network calls, a long `startToClose` with a heartbeat and `setMaximumAttempts(1)` for agent calls.
2. **Throw `ApplicationFailure.newNonRetryableFailure(...)`, never a plain `IllegalStateException`, from workflow code.** A raw JDK exception thrown directly in workflow code causes an infinite workflow-task retry loop in this SDK version rather than a clean failure.
3. Set the `current` progress field before each phase so the query method is useful mid-run.

The CI loop must use `Workflow.sleep(request.pollInterval())` — never `Thread.sleep`, which is non-deterministic and forbidden in workflow code, and never `properties.ci().pollInterval()`, because this class has no properties to read. Every configured value the workflow needs arrives on `CveFixRequest`.

Bound the loop by both `request.repairBudget()` and a total elapsed check against `request.maxWait()`, measured with `Workflow.currentTimeMillis()` — not `System.currentTimeMillis()`.

**`maxWait` exhaustion ends in `CI_UNRESOLVED`, the same status as budget exhaustion**, and takes the same actions: comment on the pull request, leave it open, record the run. The two are distinguished only by `CveFixResult.detail()` — `"CI never went green within the 3h wall-clock cap after N repair attempts"` versus `"the repair budget of N was exhausted with CI still red"`. Anything else would need a fifth terminal status for a case an operator treats identically.

The `SKIPPED_PR_OPEN` path must return that status distinctly rather than `COMPLETED`. A stuck pull request halts all CVE automation by design, and a month of silence must read as a stall, not an all-clear.

`recordUnfixable(pushResult.summary().unfixable(), components)` must be called on **every** path that got as far as an agent proposal — `COMPLETED`, `CI_UNRESOLVED` and `NOTHING_FIXABLE` — before `recordRun`. That is the spec's step 8, and it is what makes step 3's suppression work at all. It is deliberately *not* called on `SKIPPED_PR_OPEN` or `NO_FINDINGS`, where no agent ran and there is nothing to record.

`CveFixRunRecord` is built here: `CveFixRunRecord.idFor(Workflow.getInfo().getWorkflowId())` for the id and `Instant.ofEpochMilli(Workflow.currentTimeMillis())` for `startedAt`. Never `Instant.now()` in workflow code.

- [ ] **Step 1: Write the failing test**

Use `TestWorkflowEnvironment` with a mocked `CveFixActivities`, following `CodeReviewWorkflowTest` and `ReviewFeedbackWorkflowTest`:

```java
  @Test
  void skipsWhenACveFixPullRequestIsAlreadyOpen() {
    // findOpenPrUrl -> "https://github.com/o/r/pull/7" (non-null);
    // assert SKIPPED_PR_OPEN and that fetchActionableFindings was never called
  }

  @Test
  void returnsNoFindingsWhenDependencyTrackReportsNothingActionable() { }

  @Test
  void returnsNothingFixableWhenTheAgentChangesNothing() {
    // proposeAndPush returns a null headSha -> NOTHING_FIXABLE, no PR opened
  }

  @Test
  void completesWhenCiIsGreenOnTheFirstPoll() { }

  @Test
  void repairsOnceThenCompletesWhenCiTurnsGreen() {
    // checkCi: RED then GREEN; assert proposeAndPush called twice, second with failure logs
  }

  @Test
  void leavesThePullRequestOpenAndCommentsWhenTheRepairBudgetRunsOut() {
    // checkCi always RED; request.repairBudget() == 2 so the test is quick;
    // assert CI_UNRESOLVED, commentOnPullRequest called once, proposeAndPush called exactly
    // repairBudget + 1 times, and detail() mentioning the budget rather than the wall clock
  }

  @Test
  void endsCiUnresolvedWhenTheWallClockCapElapsesBeforeTheBudget() {
    // checkCi always PENDING, request = new CveFixRequest(false, ofMinutes(3), 3, ofMinutes(10));
    // assert CI_UNRESOLVED, detail() naming the wall-clock cap, and proposeAndPush called once —
    // maxWait must not silently look like budget exhaustion, and must not be reported COMPLETED
  }

  @Test
  void keepsPollingWhileCiIsPending() {
    // PENDING, PENDING, GREEN -> COMPLETED, proposeAndPush called once
  }

  @Test
  void dryRunStopsBeforeOpeningThePullRequest() {
    // new CveFixRequest(true, ofMinutes(3), 3, ofHours(3)); verifyNoInteractions on
    // openPullRequest, and no checkCi — there is no head SHA to poll
  }

  @Test
  void readsThePollIntervalAndBudgetFromTheRequestNotFromProperties() {
    // The workflow is instantiated by the Temporal SDK, so it has no CveFixProperties. Pass a
    // 30s pollInterval and a budget of 1 and assert both are honoured.
  }

  @Test
  void recordsTheRunOnEveryTerminalPath() {
    // parameterised across SKIPPED_PR_OPEN, NO_FINDINGS, COMPLETED, CI_UNRESOLVED
  }

  @Test
  void recordsUnfixableComponentsOnlyWhenAnAgentActuallyRan() {
    // COMPLETED / CI_UNRESOLVED / NOTHING_FIXABLE -> recordUnfixable called with this run's
    // components; SKIPPED_PR_OPEN and NO_FINDINGS -> never called
  }
```

Use `TestWorkflowEnvironment`'s time-skipping so the `Workflow.sleep` calls do not make the test slow. Every test constructs its own `CveFixRequest`, which is also how the CI settings get into the workflow at all.

- [ ] **Step 2: Run it to confirm it fails; Step 3: implement the workflow; Step 4: run to green**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixWorkflowTest*'`

Annotate `@WorkflowImpl(taskQueues = CveFixTaskQueues.CVE_FIX)`. Note that the implementation takes **no constructor arguments**: the SDK instantiates it, and the test registers the *type*, not an instance, exactly as `CodeReviewWorkflowTest` and `ReviewFeedbackWorkflowTest` do.

- [ ] **Step 5: Register the workflow package**

Add `com.simonrowe.factory.cvefix.workflow` to the existing `spring.temporal.workers-auto-discovery.workflow-packages` list in `software-factory/src/main/resources/application.yml`. This is the step that creates a worker on the `cve-fix` task queue; it is deliberately here rather than in Task 2, because until this task there is no `@WorkflowImpl` in that package to discover.

Confirm `FactoryApplicationTest` is still green afterwards — it boots the whole context with the Temporal test server, so a mis-typed package name surfaces there.

- [ ] **Step 6: Run checks and commit**

```bash
cd software-factory && ../gradlew :software-factory:check && cd ..
git add software-factory/src/main/java/com/simonrowe/factory/cvefix software-factory/src/main/resources/application.yml software-factory/src/test/java/com/simonrowe/factory/cvefix
git commit -m "feat: add the cvefix workflow

Skips distinctly when a CVE pull request is already open, so a stalled PR is
visible rather than looking like a clean run. The CI repair loop is bounded by
both the repair budget and a wall-clock cap, and leaves the pull request open
with a comment when it gives up either way.

The poll interval, repair budget and wall-clock cap travel on CveFixRequest
because a @WorkflowImpl is instantiated by the Temporal SDK, not Spring, and so
cannot inject CveFixProperties. The schedule initializer, which does hold the
bean, populates them."
```

---

### Task 11: Schedule, compose wiring and runbook

**Files:**
- Create: `.../cvefix/schedule/CveFixScheduleInitializer.java`
- Create: `software-factory/src/test/java/com/simonrowe/factory/cvefix/schedule/CveFixScheduleInitializerTest.java`
- Modify: `docker-compose.prod.yml` (the `software-factory` `environment:` block only)
- Create: `docs/runbooks/cvefix.md`
- Modify: `CLAUDE.md` (the `software-factory` bullet under Production Deployment)

**Interfaces:**
- Consumes: `ScheduleClient` (Temporal), `CveFixProperties`, `CveFixTaskQueues.CVE_FIX`.
- Produces: nothing consumed by later tasks.

**Context the implementer needs:**

The schedule is created in code so a deploy reconciles it, the way compose reconciles containers. Use the Temporal `ScheduleClient`: `createSchedule` on first boot, `getHandle(id).update(...)` when it already exists — the initializer must be idempotent, because it runs on every restart.

**Get the API right — this is easy to guess wrong.** Verified against `temporal-sdk-1.36.0`:

- `ScheduleOptions.Builder` has only `setTriggerImmediately`, `setBackfills`, `setMemo`, `setSearchAttributes` and `setTypedSearchAttributes`. It has **no** `setOverlap`.
- Overlap lives on `SchedulePolicy.Builder.setOverlap(ScheduleOverlapPolicy)`, attached with `Schedule.newBuilder().setPolicy(...)`.
- Paused state lives on `ScheduleState.newBuilder().setPaused(true)`, attached with `Schedule.newBuilder().setState(...)`.
- The workflow arguments go on `ScheduleActionStartWorkflow.newBuilder().setArguments(Object...)`.

So the schedule is assembled roughly like this:

```java
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(CveFixWorkflow.class)
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("cve-fix")
                            .setTaskQueue(CveFixTaskQueues.CVE_FIX)
                            .build())
                    // The workflow cannot read CveFixProperties — the Temporal SDK, not Spring,
                    // instantiates @WorkflowImpl classes. This initializer holds the bean, so the
                    // CI settings travel with the request.
                    .setArguments(
                        new CveFixRequest(
                            false,
                            properties.ci().pollInterval(),
                            properties.ci().repairBudget(),
                            properties.ci().maxWait()))
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(List.of(new ScheduleIntervalSpec(Duration.ofHours(24))))
                    .build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                    .build())
            // Created paused: enabling the feature flag must not start opening pull requests.
            .setState(ScheduleState.newBuilder().setPaused(true).build())
            .build();
```

Combined with the workflow's own open-PR skip, `SCHEDULE_OVERLAP_POLICY_SKIP` guarantees only one run is ever in flight.

The scheduled request has `dryRun = false`: the dry run is a one-off manual start, not the schedule's steady state (see Step 7's rollout order).

Gate the initializer on `factory.cvefix.enabled` exactly as `CveFixIndexInitializer` is — an unreachable Temporal must not fail the whole context.

Manual triggering of a *normal* run is the schedule's own *Trigger* action in the Temporal UI. **Add no HTTP endpoint**: `software-factory`'s internet-facing surface must stay exactly `POST /webhooks/github`.

A **`dryRun` trigger is not possible through that action.** `ScheduleHandle.trigger()` takes no workflow arguments, so the UI's Trigger re-runs the schedule's *configured* arguments — which have `dryRun = false`. A dry run is therefore started directly with the Temporal CLI, one workflow, no schedule involved; the command is in the runbook (Step 7) and in the rollout order below. That keeps the no-HTTP-endpoint constraint intact.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void createsTheScheduleWhenItDoesNotExist() {
    // mock ScheduleClient; capture the Schedule and assert a 24h interval, the cve-fix task
    // queue, and getPolicy().getOverlap() == SCHEDULE_OVERLAP_POLICY_SKIP. Overlap is on
    // SchedulePolicy, not ScheduleOptions — ScheduleOptions has no setOverlap at all.
  }

  @Test
  void updatesTheExistingScheduleRatherThanFailingOnRestart() {
    // createSchedule throws ScheduleAlreadyRunningException -> verify getHandle(...).update(...)
  }

  @Test
  void isPausedOnCreationSoTheFirstRunIsAManualTrigger() {
    // assert the captured Schedule's getState().isPaused() — ScheduleState.newBuilder()
    // .setPaused(true), not a ScheduleOptions flag
  }

  @Test
  void passesTheConfiguredCiSettingsAsTheWorkflowArgument() {
    // The workflow cannot inject CveFixProperties, so assert the captured
    // ScheduleActionStartWorkflow's arguments contain a CveFixRequest carrying
    // properties.ci().pollInterval(), repairBudget() and maxWait(), with dryRun false.
  }
```

That third test encodes a deliberate safety choice: the schedule is created **paused**, so enabling the feature flag does not immediately start opening pull requests. You unpause it in the Temporal UI after a successful manual dry run (Step 7's rollout order gives the command).

- [ ] **Step 2: Run it to confirm it fails; Step 3: implement; Step 4: run to green**

Run: `cd software-factory && ../gradlew :software-factory:test --tests '*CveFixScheduleInitializerTest*'`

- [ ] **Step 5: Add the three compose environment variables**

In `docker-compose.prod.yml`, inside the `software-factory` service's `environment:` block, after `FACTORY_FEEDBACK_DISTILL_MODEL`:

```yaml
      FACTORY_CVEFIX_ENABLED: ${FACTORY_CVEFIX_ENABLED:-false}
      # Read by a Java activity, never by the agent: ClaudeCliRunner strips it from the child
      # process because it sits outside SAFE_SECRET_ENVIRONMENT. That is correct here.
      DEPENDENCYTRACK_API_KEY: ${DEPENDENCYTRACK_API_KEY:-}
      # The container on the shared compose network, not the public hostname — this keeps the
      # call off the Cloudflare/pinggy path.
      DEPENDENCYTRACK_BASE_URL: ${DEPENDENCYTRACK_BASE_URL:-http://dependencytrack-apiserver:8080}
```

Note the service deliberately has no `env_file`, so each variable must be named individually. Use `:-` defaults, **not** `:?` required syntax: a missing `DEPENDENCYTRACK_API_KEY` must not break every `docker compose` command against this file while the feature is disabled.

**Exactly three variables, and no `FACTORY_CVEFIX_INSTALLATION_ID`.** The GitHub installation id is resolved at run time by the activity (Task 9), so there is no fourth variable and no configured value that can be left empty. If you find yourself adding one, re-read Task 9's note about `accessToken(null)`.

`DEPENDENCYTRACK_API_KEY` must hold a key with `VIEW_PORTFOLIO` + `VIEW_VULNERABILITY`; the `CI Upload` key cannot read findings. That is a prerequisite of enabling the flag, not of deploying this change — hence the `:-` default.

Do **not** add a `depends_on` for `dependencytrack-apiserver`. The workflow already fails cleanly when Dependency-Track is unreachable, and coupling the webhook receiver's startup to Dependency-Track would be a regression.

- [ ] **Step 6: Verify the compose file still parses and nothing else changed**

```bash
docker compose -f docker-compose.prod.yml config --quiet && echo "compose OK"
git diff --stat docker-compose.prod.yml
```

Expected: `compose OK`, and a diff touching only the `software-factory` environment block. `docker compose config` interpolates `.env`, so run it from a directory with one, or expect interpolation warnings for unrelated required variables.

- [ ] **Step 7: Write the runbook**

Create `docs/runbooks/cvefix.md` covering, in this order:

1. **What it does and what it deliberately does not**: opens one pull request per run on a fixed branch; never auto-merges; never builds anything locally (CI is the only build environment).
2. **Rollout order**, which matters:
   - (a) put a Dependency-Track API key whose team has `VIEW_PORTFOLIO` **and** `VIEW_VULNERABILITY` into the deploy `.env` as `DEPENDENCYTRACK_API_KEY`. The `CI Upload` key does **not** work: its team lacks `VIEW_VULNERABILITY`, so `/api/v1/finding/project/{uuid}` is refused, and it exists only as a GitHub Actions secret which cannot be read back. See the spec's Accepted risks.
   - (b) deploy with `FACTORY_CVEFIX_ENABLED=false` and confirm the stack is healthy.
   - (c) set it to `true` and restart `software-factory`.
   - (d) confirm a live poller on the `cve-fix` task queue — a healthy container can have registered no poller, in which case the schedule fires and nothing runs:

     ```bash
     temporal task-queue describe --task-queue cve-fix --task-queue-type workflow
     ```

   - (e) start **one** dry run directly, not through the schedule. `ScheduleHandle.trigger()` (the Temporal UI's *Trigger* action) takes no arguments, so it re-runs the schedule's configured `dryRun = false` request — it cannot be used for this:

     ```bash
     temporal workflow start \
       --type CveFixWorkflow \
       --task-queue cve-fix \
       --workflow-id cve-fix-dryrun-$(date +%Y%m%d%H%M) \
       --input '{"dryRun":true,"pollInterval":"PT3M","repairBudget":3,"maxWait":"PT3H"}'
     temporal workflow show --workflow-id <the id above>
     ```

     Read the history: it must reach `proposeAndPush` and stop before `openPullRequest`.
   - (f) unpause the schedule in the Temporal UI.
3. **The stall is by design.** A pull request left open at `CI_UNRESOLVED` halts every later run via `SKIPPED_PR_OPEN`. To resume, merge or close it. Include the query to check: `temporal workflow list --query 'WorkflowType="CveFixWorkflow"'`.
4. **Why CI status is read unauthenticated**, and the escalation path if the repository ever goes private: revisit the design, do not add `checks: read` to the shared token mint, which would 422 code-review and feedback too.
5. **Why the agent has no Bash tool** and what to do instead if local verification is ever wanted (that is a separate, deliberately rejected design — see the spec).
6. **Unfixable records**: where they live (`unfixable_findings` in the `software_factory` database), how to clear one to force a retry, why the fingerprint must stay sorted, and that it is computed in Java from Dependency-Track's data — never emitted by the agent, because a model-supplied key compared against a Java-computed one would disable suppression silently.
7. **Advisory checks**: `factory.cvefix.ci.advisory-checks` names check runs that can never make CI read RED, defaulting to the promptfoo `evaluate` job. Add a new advisory job's check name here, and understand why: a red advisory check would burn the whole repair budget and then block every later run through `SKIPPED_PR_OPEN`.

- [ ] **Step 8: Update `CLAUDE.md`**

Extend the existing `software-factory` bullet under **Production Deployment** to mention that the container now also hosts the `cve-fix` task queue and a paused-by-default 24-hour Temporal schedule, that it builds nothing locally, and to link `docs/runbooks/cvefix.md`. Keep it to two or three sentences in the established style.

- [ ] **Step 9: Full verification, then commit**

```bash
cd software-factory && ../gradlew :software-factory:check && cd ..
docker compose -f docker-compose.prod.yml config --quiet && echo "compose OK"
docker build -f Dockerfile.software-factory -t software-factory:test . && echo "image OK"
```

Expected: all three pass. The image build must still succeed **unchanged** — if you found yourself editing `Dockerfile.software-factory`, you have drifted from the design, which puts every build in CI.

```bash
git add software-factory docker-compose.prod.yml docs/runbooks/cvefix.md CLAUDE.md
git commit -m "feat: schedule the cvefix workflow every 24 hours

Created paused and behind a feature flag, so enabling it does not immediately
start opening pull requests. Manual triggering uses the schedule's own Trigger
action, so no new HTTP route is added and the internet-facing surface stays
exactly POST /webhooks/github.

overlap: SKIP plus the workflow's own open-PR check means only one run is ever
in flight."
```

---

## Self-review notes

Checked against the spec. Coverage:

| Spec section | Task |
| --- | --- |
| No MCP server | Other plan (Part A); nothing here registers one |
| `cvefix` module on its own task queue | 2 |
| Build runs in CI, image unchanged | Enforced in Task 11 Step 9 |
| No new HTTP endpoint | 11 |
| Workflow steps 1–8 | 9, 10 |
| Normal pull request, not a draft | 7 |
| Single fixed branch | 1 (`-B`), 2 (config), 7 (head filter) |
| Agent's boundary (no docker/git/DT credential) | 6, 9 |
| CI status needs no new permission | 8 |
| Shared-code extraction | 1 |
| Configuration and feature flag | 2, 11 |
| Persistence | 4 |
| Error handling | 3 (fail-loud), 8 (empty ≠ green, advisory ignore-list), 10 (`ApplicationFailure`, `maxWait`) |
| Testing patterns | Every task |
| Promptfoo eval filtering | Other plan, Task 1 |

Three deliberate additions beyond the spec, all safety rails rather than scope creep: `CveFixRequest.dryRun` (so the first manual run cannot open a pull request), creating the schedule **paused** (so flipping the feature flag does not immediately start work), and `ci.advisoryChecks` (so the advisory promptfoo job can never burn the repair budget and stall every later run). All three are noted in the runbook.

Two structural consequences worth keeping in mind while implementing, because they are easy to undo by accident:

- `CveFixRequest` carries `pollInterval`, `repairBudget` and `maxWait` because a `@WorkflowImpl` is instantiated by the Temporal SDK and cannot inject `CveFixProperties`. Any change that has workflow code read the properties bean is a compile-time-plausible, run-time-broken regression.
- The suppression fingerprint has exactly one producer, `ComponentFindings.fingerprint()`. Neither the schema nor `UnfixableComponent` has a `fingerprint` field, and reintroducing one silently disables suppression.

One item the spec leaves to this plan: the repair budget default of `3`. It is a guess until the Part A skill has been run for real — see that plan's Task 2 Step 7.
