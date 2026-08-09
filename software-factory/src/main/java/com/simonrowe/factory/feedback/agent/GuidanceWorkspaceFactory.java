package com.simonrowe.factory.feedback.agent;

import com.simonrowe.factory.codereview.agent.GitWorkspaceFactory;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Prepares a disposable checkout of a guidance repository (e.g. an agent-setup / instructions
 * repo) for an agent to edit, then validates and pushes the resulting changes.
 *
 * <p>The agent that edits files in the checkout never touches git or holds credentials: this
 * factory owns cloning, allowlist validation of the changed paths, and the commit/push.
 */
@Component
public class GuidanceWorkspaceFactory {

  private static final Duration GIT_TIMEOUT = Duration.ofMinutes(3);

  private final FeedbackProperties properties;
  private final GitHubCredentials credentials;
  private final ProcessRunner processRunner;

  public GuidanceWorkspaceFactory(
      final FeedbackProperties properties,
      final GitHubCredentials credentials,
      final ProcessRunner processRunner) {
    this.properties = properties;
    this.credentials = credentials;
    this.processRunner = processRunner;
  }

  /** Shallow-clones the default branch of {@code owner/repository} into a temp workspace. */
  public GuidanceWorkspace create(
      final String owner,
      final String repository,
      final Long installationId,
      final Consumer<String> heartbeat) {
    Path workspace = null;
    try {
      Path root = properties.workspaceRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      workspace = Files.createTempDirectory(root, "guidance-");
      Path checkout = workspace.resolve("repository");
      heartbeat.accept("Cloning " + owner + "/" + repository);
      runGit(
          List.of(
              "git",
              "clone",
              "--quiet",
              "--depth",
              "1",
              "https://github.com/" + owner + "/" + repository + ".git",
              checkout.toString()),
          workspace,
          installationId,
          heartbeat);
      ProcessRunner.ProcessResult head =
          runGit(
              List.of("git", "symbolic-ref", "--short", "HEAD"),
              checkout,
              installationId,
              heartbeat);
      return new GuidanceWorkspace(workspace, checkout, head.standardOutput().trim());
    } catch (RuntimeException | IOException exception) {
      if (workspace != null) {
        deleteTree(workspace);
      }
      throw new IllegalStateException("Unable to prepare guidance workspace", exception);
    }
  }

  /** Paths touched in the workspace (git status --porcelain), repo-relative. */
  public List<String> changedPaths(
      final GuidanceWorkspace workspace, final Consumer<String> heartbeat) {
    ProcessRunner.ProcessResult status =
        runGit(
            List.of("git", "status", "--porcelain"), workspace.repository(), null, heartbeat);
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

  /** Throws IllegalStateException when any changed path escapes the allowlist. */
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
          "Distillation touched files outside the allowlist: " + String.join(", ", violations));
    }
  }

  /** Branch + add + commit + force-push. Never invoked by the agent — Java only. */
  public void commitAndPush(
      final GuidanceWorkspace workspace,
      final String branch,
      final String message,
      final Long installationId,
      final Consumer<String> heartbeat) {
    Path repo = workspace.repository();
    runGit(List.of("git", "checkout", "--quiet", "-b", branch), repo, installationId, heartbeat);
    runGit(List.of("git", "add", "--all"), repo, installationId, heartbeat);
    runGit(
        List.of(
            "git",
            "-c",
            "user.name=" + properties.gitAuthorName(),
            "-c",
            "user.email=" + properties.gitAuthorEmail(),
            "commit",
            "--quiet",
            "-m",
            message),
        repo,
        installationId,
        heartbeat);
    heartbeat.accept("Pushing " + branch);
    // --force: the branch namespace feedback/* belongs to the factory; a manual re-drive
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
                "GIT_TERMINAL_PROMPT",
                "0",
                "GIT_CONFIG_COUNT",
                "1",
                "GIT_CONFIG_KEY_0",
                "http.extraHeader",
                "GIT_CONFIG_VALUE_0",
                GitWorkspaceFactory.basicAuthorizationHeader(accessToken));
    ProcessRunner.ProcessResult result =
        processRunner.run(
            command, directory, null, environment, Set.of(), GIT_TIMEOUT, heartbeat);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          command.get(1) + " failed: " + abbreviate(result.standardError(), 600));
    }
    return result;
  }

  private static String abbreviate(final String value, final int maximumLength) {
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }

  private static void deleteTree(final Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(GuidanceWorkspaceFactory::deleteQuietly);
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

  /** Disposable guidance-repository checkout owned by one activity execution. */
  public static final class GuidanceWorkspace implements AutoCloseable {

    private final Path root;
    private final Path repository;
    private final String defaultBranch;

    GuidanceWorkspace(final Path root, final Path repository, final String defaultBranch) {
      this.root = root;
      this.repository = repository;
      this.defaultBranch = defaultBranch;
    }

    public Path repository() {
      return repository;
    }

    public String defaultBranch() {
      return defaultBranch;
    }

    @Override
    public void close() {
      deleteTree(root);
    }
  }
}
