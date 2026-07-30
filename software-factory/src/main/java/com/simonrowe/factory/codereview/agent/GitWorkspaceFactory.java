package com.simonrowe.factory.codereview.agent;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Builds a disposable exact-SHA checkout and stores the diff outside Temporal history. */
@Component
public class GitWorkspaceFactory {

  private static final Duration GIT_TIMEOUT = Duration.ofMinutes(3);

  private final CodeReviewProperties properties;
  private final GitHubCredentials credentials;
  private final ProcessRunner processRunner;

  public GitWorkspaceFactory(
      final CodeReviewProperties properties,
      final GitHubCredentials credentials,
      final ProcessRunner processRunner) {
    this.properties = properties;
    this.credentials = credentials;
    this.processRunner = processRunner;
  }

  public Workspace create(
      final PullRequestContext pullRequest, final Consumer<String> heartbeat) {
    Path workspace = null;
    try {
      Path root = properties.agent().workspaceRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      workspace = Files.createTempDirectory(root, "review-");
      Path repository = workspace.resolve("repository");

      heartbeat.accept("Cloning pull request repository");
      runGit(
          List.of(
              "git",
              "clone",
              "--quiet",
              "--filter=blob:none",
              "--no-checkout",
              pullRequest.cloneUrl(),
              repository.toString()),
          workspace,
          pullRequest,
          heartbeat);
      runGit(
          List.of(
              "git",
              "fetch",
              "--quiet",
              "origin",
              "+refs/pull/"
                  + pullRequest.pullNumber()
                  + "/head:refs/remotes/origin/review-head"),
          repository,
          pullRequest,
          heartbeat);
      runGit(
          List.of("git", "checkout", "--quiet", "--detach", pullRequest.headSha()),
          repository,
          pullRequest,
          heartbeat);

      ProcessRunner.ProcessResult changedFilesResult =
          runGit(
              List.of(
                  "git",
                  "diff",
                  "--name-only",
                  pullRequest.baseSha() + "..." + pullRequest.headSha()),
              repository,
              pullRequest,
              heartbeat);
      List<String> changedFiles =
          changedFilesResult
              .standardOutput()
              .lines()
              .filter(line -> !line.isBlank())
              .filter(GitWorkspaceFactory::isSafeReviewPath)
              .filter(path -> !Files.isSymbolicLink(repository.resolve(path)))
              .toList();
      if (changedFiles.size() > properties.agent().maxChangedFiles()) {
        throw new IllegalStateException(
            "Review exceeds changed-file limit of " + properties.agent().maxChangedFiles());
      }

      byte[] diff = new byte[0];
      if (!changedFiles.isEmpty()) {
        List<String> diffCommand =
            new ArrayList<>(
                List.of(
                    "git",
                    "diff",
                    "--no-ext-diff",
                    "--no-color",
                    "--unified=3",
                    pullRequest.baseSha() + "..." + pullRequest.headSha(),
                    "--"));
        diffCommand.addAll(changedFiles);
        ProcessRunner.ProcessResult diffResult =
            runGit(diffCommand, repository, pullRequest, heartbeat);
        diff =
            diffResult.standardOutput().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      }
      if (diff.length > properties.agent().maxDiffBytes()) {
        throw new IllegalStateException(
            "Review exceeds diff limit of " + properties.agent().maxDiffBytes() + " bytes");
      }

      removeUnsafeFiles(repository);
      Path reviewerDirectory = Files.createTempDirectory(repository, ".temporal-review-");
      Path diffPath = reviewerDirectory.resolve("changes.diff");
      Files.write(diffPath, diff);
      return new Workspace(workspace, repository, diffPath, changedFiles);
    } catch (RuntimeException | IOException exception) {
      if (workspace != null) {
        deleteTree(workspace);
      }
      throw new IllegalStateException("Unable to prepare review workspace", exception);
    }
  }

  private ProcessRunner.ProcessResult runGit(
      final List<String> command,
      final Path directory,
      final PullRequestContext pullRequest,
      final Consumer<String> heartbeat) {
    String accessToken = credentials.accessToken(pullRequest.installationId());
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
                "Authorization: Bearer " + accessToken);
    ProcessRunner.ProcessResult result =
        processRunner.run(
            command, directory, null, environment, Set.of(), GIT_TIMEOUT, heartbeat);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          command.get(1) + " failed: " + abbreviate(result.standardError(), 600));
    }
    return result;
  }

  private static void removeUnsafeFiles(final Path repository) throws IOException {
    try (Stream<Path> paths = Files.walk(repository)) {
      for (Path path : paths.toList()) {
        if (Files.isSymbolicLink(path)
            || (Files.isRegularFile(path)
                && isSensitiveFileName(path.getFileName().toString()))) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  static boolean isSafeReviewPath(final String value) {
    try {
      Path path = Path.of(value).normalize();
      if (path.isAbsolute() || path.startsWith("..") || path.getFileName() == null) {
        return false;
      }
      return !isSensitiveFileName(path.getFileName().toString());
    } catch (java.nio.file.InvalidPathException exception) {
      return false;
    }
  }

  private static boolean isSensitiveFileName(final String value) {
    String fileName = value.toLowerCase(java.util.Locale.ROOT);
    boolean dotenv =
        fileName.equals(".env")
            || (fileName.startsWith(".env.")
                && !fileName.endsWith(".example")
                && !fileName.endsWith(".sample"));
    return dotenv
        || fileName.equals(".netrc")
        || fileName.equals(".npmrc")
        || fileName.endsWith(".pem")
        || fileName.endsWith(".p12")
        || fileName.endsWith(".pfx")
        || fileName.endsWith(".key");
  }

  private static String abbreviate(final String value, final int maximumLength) {
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }

  private static void deleteTree(final Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(GitWorkspaceFactory::deleteQuietly);
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

  /** Disposable checkout owned by one activity execution. */
  public static final class Workspace implements AutoCloseable {

    private final Path root;
    private final Path repository;
    private final Path diffPath;
    private final List<String> changedFiles;

    Workspace(
        final Path root,
        final Path repository,
        final Path diffPath,
        final List<String> changedFiles) {
      this.root = root;
      this.repository = repository;
      this.diffPath = diffPath;
      this.changedFiles = List.copyOf(changedFiles);
    }

    public Path repository() {
      return repository;
    }

    public Path diffPath() {
      return diffPath;
    }

    public List<String> changedFiles() {
      return changedFiles;
    }

    @Override
    public void close() {
      deleteTree(root);
    }
  }
}
