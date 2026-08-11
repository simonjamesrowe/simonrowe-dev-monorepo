package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceFactoryTest {

  @TempDir Path temporaryDirectory;

  @Test
  void excludesTrackedSecretsFromTheDiffAndCheckout() throws IOException {
    Path origin = temporaryDirectory.resolve("origin.git");
    Path seed = temporaryDirectory.resolve("seed");
    git(temporaryDirectory, "init", "--bare", origin.toString());
    git(temporaryDirectory, "init", seed.toString());
    git(seed, "config", "user.name", "Reviewer Test");
    git(seed, "config", "user.email", "reviewer@example.com");

    Files.writeString(seed.resolve("visible.txt"), "before\n");
    Files.writeString(seed.resolve(".env"), "TOKEN=before\n");
    git(seed, "add", ".");
    git(seed, "commit", "-m", "base");
    final String baseSha = git(seed, "rev-parse", "HEAD").trim();

    Files.writeString(seed.resolve("visible.txt"), "after\n");
    Files.writeString(seed.resolve(".env"), "TOKEN=must-not-reach-the-model\n");
    Files.createSymbolicLink(seed.resolve("outside-link"), Path.of("/etc/hosts"));
    git(seed, "commit", "-am", "pull request");
    git(seed, "add", "outside-link");
    git(seed, "commit", "--amend", "--no-edit");
    String headSha = git(seed, "rev-parse", "HEAD").trim();
    git(seed, "push", origin.toString(), "HEAD:refs/pull/1/head");

    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(null)).thenReturn("");
    GitWorkspaceFactory factory =
        new GitWorkspaceFactory(properties(), credentials, new ProcessRunner());
    PullRequestContext pullRequest =
        new PullRequestContext(
            "owner",
            "repository",
            1,
            "title",
            "",
            origin.toString(),
            baseSha,
            headSha,
            null);

    try (GitWorkspaceFactory.Workspace workspace =
        factory.create(pullRequest, ignored -> { })) {
      assertThat(workspace.changedFiles()).containsExactly("visible.txt");
      assertThat(Files.readString(workspace.diffPath()))
          .contains("after")
          .doesNotContain("must-not-reach-the-model");
      assertThat(workspace.repository().resolve(".env")).doesNotExist();
      assertThat(workspace.repository().resolve("outside-link")).doesNotExist();
    }
  }

  @Test
  void authenticatesGitWithBasicCredentialsRatherThanBearerToken() throws IOException {
    Path origin = temporaryDirectory.resolve("auth-origin.git");
    Path seed = temporaryDirectory.resolve("auth-seed");
    git(temporaryDirectory, "init", "--bare", origin.toString());
    git(temporaryDirectory, "init", seed.toString());
    git(seed, "config", "user.name", "Reviewer Test");
    git(seed, "config", "user.email", "reviewer@example.com");

    Files.writeString(seed.resolve("visible.txt"), "before\n");
    git(seed, "add", ".");
    git(seed, "commit", "-m", "base");
    final String baseSha = git(seed, "rev-parse", "HEAD").trim();

    Files.writeString(seed.resolve("visible.txt"), "after\n");
    git(seed, "commit", "-am", "pull request");
    final String headSha = git(seed, "rev-parse", "HEAD").trim();
    git(seed, "push", origin.toString(), "HEAD:refs/pull/1/head");

    List<Map<String, String>> environments = new ArrayList<>();
    ProcessRunner recordingRunner =
        new ProcessRunner() {
          @Override
          public ProcessResult run(
              final List<String> command,
              final Path workingDirectory,
              final String standardInput,
              final Map<String, String> environment,
              final Set<String> removedEnvironment,
              final Duration timeout,
              final Consumer<String> heartbeat) {
            environments.add(environment);
            return super.run(
                command,
                workingDirectory,
                standardInput,
                environment,
                removedEnvironment,
                timeout,
                heartbeat);
          }
        };

    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(42L)).thenReturn("ghs_example");
    GitWorkspaceFactory factory =
        new GitWorkspaceFactory(properties(), credentials, recordingRunner);
    PullRequestContext pullRequest =
        new PullRequestContext(
            "owner", "repository", 1, "title", "", origin.toString(), baseSha, headSha, 42L);

    try (GitWorkspaceFactory.Workspace workspace =
        factory.create(pullRequest, ignored -> { })) {
      assertThat(workspace.changedFiles()).containsExactly("visible.txt");
    }

    String expectedHeader =
        "Authorization: Basic "
            + Base64.getEncoder()
                .encodeToString(
                    "x-access-token:ghs_example".getBytes(StandardCharsets.UTF_8));
    assertThat(environments).isNotEmpty();
    assertThat(environments)
        .allSatisfy(
            environment ->
                assertThat(environment)
                    .containsEntry("GIT_TERMINAL_PROMPT", "0")
                    .containsEntry("GIT_CONFIG_KEY_0", "http.extraHeader")
                    .containsEntry("GIT_CONFIG_VALUE_0", expectedHeader));
  }

  @Test
  void buildsBasicAuthorizationHeaderFromTheInstallationToken() {
    assertThat(GitWorkspaceFactory.basicAuthorizationHeader("ghs_example"))
        .isEqualTo("Authorization: Basic eC1hY2Nlc3MtdG9rZW46Z2hzX2V4YW1wbGU=")
        .doesNotContain("Bearer");
  }

  @Test
  void acceptsExamplesButRejectsCredentialFileNames() {
    assertThat(GitWorkspaceFactory.isSafeReviewPath("config/.env.example")).isTrue();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("config/.env.production")).isFalse();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("keys/app.pem")).isFalse();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("../outside")).isFalse();
  }

  private CodeReviewProperties properties() {
    return new CodeReviewProperties(
        new CodeReviewProperties.Github(
            "https://api.github.com", "", "", "", "", Duration.ofSeconds(5)),
        new CodeReviewProperties.Agent(
            "claude",
            "sonnet",
            "medium",
            1,
            Duration.ofMinutes(1),
            temporaryDirectory.resolve("workspaces"),
            1024 * 1024,
            10,
            "test"),
        new CodeReviewProperties.Api(""), "https://temporal.test");
  }

  private static String git(final Path directory, final String... arguments) throws IOException {
    String[] command = new String[arguments.length + 1];
    command[0] = "git";
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .start();
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IOException("git failed: " + output);
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("git interrupted", exception);
    }
  }
}
