package com.simonrowe.factory.cvefix.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
  void promptIncludesTheFailureContextOnRepairAttempt() {
    String prompt = ClaudeCliFixEngine.prompt(List.of(), "gradle: cannot find symbol Foo");

    assertThat(prompt).contains("gradle: cannot find symbol Foo");
    assertThat(prompt).contains("previous attempt");
  }

  @Test
  void promptOmitsTheRepairSectionOnFirstAttempt() {
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
  void parsesValidProposal() throws Exception {
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
