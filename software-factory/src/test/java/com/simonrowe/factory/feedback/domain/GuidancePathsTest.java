package com.simonrowe.factory.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuidancePathsTest {

  private static final String AGENT_SETUP = "simonjamesrowe/agent-setup";

  @Test
  void agentSetupGetsItsOwnAllowlistAndEveryOtherRepositoryGetsClaudeMd() {
    assertThat(GuidancePaths.forRepository("simonjamesrowe", "agent-setup", AGENT_SETUP))
        .isEqualTo(GuidancePaths.AGENT_SETUP);
    assertThat(GuidancePaths.forRepository("SimonJamesRowe", "Agent-Setup", AGENT_SETUP))
        .isEqualTo(GuidancePaths.AGENT_SETUP);
    assertThat(
            GuidancePaths.forRepository("simonjamesrowe", "simonrowe-dev-monorepo", AGENT_SETUP))
        .isEqualTo(GuidancePaths.SOURCE_REPOSITORY);
  }

  @Test
  void agentSetupAllowsItsInstructionFilesAndAnySkill() {
    assertThat(
            GuidancePaths.firstOutside(
                List.of(
                    "components/instructions/global.md",
                    "components/instructions/monorepo-additions.md",
                    "components/skills/pr-review-loop/SKILL.md",
                    "components/skills/a/b/c.md"),
                GuidancePaths.AGENT_SETUP))
        .isEmpty();
  }

  @Test
  void nearMissesAreOutside() {
    for (String path :
        List.of(
            "components/instructions/other.md",
            "components/instructions/global.md.bak",
            "components/global.md",
            "install.sh",
            ".github/workflows/ci.yml")) {
      assertThat(GuidancePaths.firstOutside(List.of(path), GuidancePaths.AGENT_SETUP))
          .as(path)
          .contains(path);
    }
  }

  @Test
  void theSourceRepositoryAllowsOnlyItsRootClaudeMd() {
    assertThat(GuidancePaths.firstOutside(List.of("CLAUDE.md"), GuidancePaths.SOURCE_REPOSITORY))
        .isEmpty();
    assertThat(
            GuidancePaths.firstOutside(
                List.of("CLAUDE.md", "docs/CLAUDE.md"), GuidancePaths.SOURCE_REPOSITORY))
        .contains("docs/CLAUDE.md");
    assertThat(
            GuidancePaths.firstOutside(List.of("AGENTS.md"), GuidancePaths.SOURCE_REPOSITORY))
        .contains("AGENTS.md");
  }
}
