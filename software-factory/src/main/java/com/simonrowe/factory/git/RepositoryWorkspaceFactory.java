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

  /**
   * Branch, add, commit and force-push. Never invoked by an agent — Java only.
   *
   * @return the pushed commit's sha, so a caller (such as the CVE-fix activity) can poll CI for
   *     this exact commit without a second round trip to GitHub
   */
  public String commitAndPush(
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
    String headSha =
        runGit(List.of("git", "rev-parse", "HEAD"), repo, installationId, heartbeat)
            .standardOutput()
            .trim();
    heartbeat.accept("Pushing " + branch);
    // --force: this branch namespace belongs to the factory; a re-drive or a repair iteration
    // replaces its own earlier proposal.
    runGit(
        List.of("git", "push", "--force", "--quiet", "origin", "HEAD:refs/heads/" + branch),
        repo,
        installationId,
        heartbeat);
    return headSha;
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
