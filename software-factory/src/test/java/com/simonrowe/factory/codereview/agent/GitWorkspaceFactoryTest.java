package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    CodeReviewProperties properties =
        new CodeReviewProperties(
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
            new CodeReviewProperties.Api(""));
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(null)).thenReturn("");
    GitWorkspaceFactory factory =
        new GitWorkspaceFactory(properties, credentials, new ProcessRunner());
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
  void acceptsExamplesButRejectsCredentialFileNames() {
    assertThat(GitWorkspaceFactory.isSafeReviewPath("config/.env.example")).isTrue();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("config/.env.production")).isFalse();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("keys/app.pem")).isFalse();
    assertThat(GitWorkspaceFactory.isSafeReviewPath("../outside")).isFalse();
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
