package com.simonrowe.factory.feedback.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuidanceWorkspaceFactoryTest {

  @Test
  void acceptsChangesInsideTheAllowlist() {
    GuidanceWorkspaceFactory.validateAllowedPaths(
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
                GuidanceWorkspaceFactory.validateAllowedPaths(
                    List.of("components/instructions/global.md", "package.json"),
                    List.of("components/instructions/global.md", "components/skills/**")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("package.json");
  }

  @Test
  void parsesPorcelainStatusIntoPaths() {
    List<String> changed =
        GuidanceWorkspaceFactory.parsePorcelain(
            " M components/instructions/global.md\n?? components/skills/new-skill/SKILL.md\n");

    assertThat(changed)
        .containsExactly(
            "components/instructions/global.md", "components/skills/new-skill/SKILL.md");
  }

  @Test
  void parsesRenamesToTheirTarget() {
    List<String> changed =
        GuidanceWorkspaceFactory.parsePorcelain("R  old.md -> components/skills/x/SKILL.md\n");

    assertThat(changed).containsExactly("components/skills/x/SKILL.md");
  }
}
