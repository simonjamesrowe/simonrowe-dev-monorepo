package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
