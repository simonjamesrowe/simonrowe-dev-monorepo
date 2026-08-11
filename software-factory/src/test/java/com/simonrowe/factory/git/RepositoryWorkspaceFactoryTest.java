package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RepositoryWorkspaceFactoryTest {

  @Test
  void checksOutBranchWithResetFlagSoRepeatedCallsSucceed() {
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

  @Test
  void acceptsChangesInsideTheAllowlist() {
    RepositoryWorkspaceFactory.validateAllowedPaths(
        List.of("components/instructions/global.md", "components/skills/prod-deploy/SKILL.md"),
        List.of(
            "components/instructions/global.md",
            "components/instructions/monorepo-additions.md",
            "components/skills/**"));
  }

  @Test
  void rejectsAnyChangeOutsideTheAllowlist() {
    assertThatThrownBy(
            () ->
                RepositoryWorkspaceFactory.validateAllowedPaths(
                    List.of("components/instructions/global.md", "package.json"),
                    List.of("components/instructions/global.md", "components/skills/**")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("package.json");
  }

  @Test
  void parsesPorcelainStatusIntoPaths() {
    List<String> changed =
        RepositoryWorkspaceFactory.parsePorcelain(
            " M components/instructions/global.md\n?? components/skills/new-skill/SKILL.md\n");

    assertThat(changed)
        .containsExactly(
            "components/instructions/global.md", "components/skills/new-skill/SKILL.md");
  }

  @Test
  void parsesRenamesToTheirTarget() {
    List<String> changed =
        RepositoryWorkspaceFactory.parsePorcelain("R  old.md -> components/skills/x/SKILL.md\n");

    assertThat(changed).containsExactly("components/skills/x/SKILL.md");
  }

  @Test
  void commitAndPushReturnsTheTrimmedRevParseShaIssuedBeforeThePush() {
    ProcessRunner processRunner = mock(ProcessRunner.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(any())).thenReturn("");
    RepositoryWorkspaceFactory factory = new RepositoryWorkspaceFactory(credentials, processRunner);
    Path repo = Path.of("/tmp/repository");
    RepositoryWorkspace workspace = new RepositoryWorkspace(Path.of("/tmp/root"), repo, "main");
    Consumer<String> heartbeat = detail -> { };
    when(processRunner.run(any(), eq(repo), any(), anyMap(), anySet(), any(), eq(heartbeat)))
        .thenAnswer(
            invocation -> {
              List<String> command = invocation.getArgument(0);
              return command.contains("rev-parse")
                  ? new ProcessRunner.ProcessResult(0, "abc123\n", "")
                  : new ProcessRunner.ProcessResult(0, "", "");
            });

    String sha =
        factory.commitAndPush(
            workspace, "chore/dependency-cve-fixes", "message", "bot", "bot@example.com", 42L,
            heartbeat);

    assertThat(sha).isEqualTo("abc123");
    assertThat(sha).doesNotContain("\n");
    InOrder order = inOrder(processRunner);
    order
        .verify(processRunner)
        .run(
            argThat(command -> command.containsAll(List.of("git", "rev-parse", "HEAD"))),
            eq(repo), any(), anyMap(), anySet(), any(), eq(heartbeat));
    order
        .verify(processRunner)
        .run(
            argThat(command -> command.contains("push")), eq(repo), any(), anyMap(), anySet(),
            any(), eq(heartbeat));
  }

  @Test
  void commitAndPushDoesNotReturnHeadShaWhenThePushFails() {
    ProcessRunner processRunner = mock(ProcessRunner.class);
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(any())).thenReturn("");
    RepositoryWorkspaceFactory factory = new RepositoryWorkspaceFactory(credentials, processRunner);
    Path repo = Path.of("/tmp/repository");
    RepositoryWorkspace workspace = new RepositoryWorkspace(Path.of("/tmp/root"), repo, "main");
    Consumer<String> heartbeat = detail -> { };
    when(processRunner.run(any(), eq(repo), any(), anyMap(), anySet(), any(), eq(heartbeat)))
        .thenAnswer(
            invocation -> {
              List<String> command = invocation.getArgument(0);
              if (command.contains("rev-parse")) {
                return new ProcessRunner.ProcessResult(0, "abc123\n", "");
              }
              if (command.contains("push")) {
                return new ProcessRunner.ProcessResult(1, "", "remote rejected the push");
              }
              return new ProcessRunner.ProcessResult(0, "", "");
            });

    assertThatThrownBy(
            () ->
                factory.commitAndPush(
                    workspace, "chore/dependency-cve-fixes", "message", "bot",
                    "bot@example.com", 42L, heartbeat))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("push failed");
  }
}
