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
    String prompt =
        ClaudeCliFixEngine.prompt(List.of(), "gradle: cannot find symbol Foo", List.of());

    assertThat(prompt).contains("gradle: cannot find symbol Foo");
    assertThat(prompt).contains("previous attempt");
  }

  @Test
  void promptOmitsTheRepairSectionOnFirstAttempt() {
    assertThat(ClaudeCliFixEngine.prompt(List.of(), null, List.of()))
        .doesNotContain("previous attempt");
  }

  @Test
  void repairPromptListsTheBumpsAnEarlierAttemptAlreadyHadRejected() {
    String prompt =
        ClaudeCliFixEngine.prompt(
            List.of(),
            "npm ci failed",
            List.of("org.example/lib 1.0.0 -> 2.0.0 (CVE-1)", "left-pad 1.0.0 -> 1.3.0 (CVE-2)"));

    assertThat(prompt).contains("org.example/lib 1.0.0 -> 2.0.0 (CVE-1)");
    assertThat(prompt).contains("left-pad 1.0.0 -> 1.3.0 (CVE-2)");
    // The agent must be told why the manifests disagree with what it already tried, or the
    // instruction to choose a different target version is not actionable.
    assertThat(prompt).contains("Do not propose any of them again");
    assertThat(prompt).contains("fresh checkout of the default branch");
  }

  @Test
  void firstAttemptPromptListsNoRejectedBumps() {
    String prompt = ClaudeCliFixEngine.prompt(List.of(), null, List.of());

    assertThat(prompt).doesNotContain("Do not propose any of them again");
    assertThat(prompt).doesNotContain("CI rejected");
  }

  @Test
  void repairPromptOmitsTheRejectedBlockWhenTheFailedAttemptPushedNoBump() {
    String prompt = ClaudeCliFixEngine.prompt(List.of(), "npm ci failed", List.of());

    assertThat(prompt).contains("npm ci failed");
    assertThat(prompt).doesNotContain("Do not propose any of them again");
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
