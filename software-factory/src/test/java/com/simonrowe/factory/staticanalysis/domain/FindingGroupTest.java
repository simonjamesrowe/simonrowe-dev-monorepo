package com.simonrowe.factory.staticanalysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FindingGroupTest {

  private static SonarIssue issue(
      final String key, final String rule, final String severity, final String type,
      final String path, final int line) {
    return new SonarIssue(key, rule, severity, type, path, line, "message " + key);
  }

  @Test
  void groupsByModuleAndRule() {
    List<FindingGroup> groups = FindingGroup.group(
        List.of(
            issue("a", "java:S1192", "CRITICAL", "CODE_SMELL", "backend/src/A.java", 3),
            issue("b", "java:S1192", "CRITICAL", "CODE_SMELL", "backend/src/B.java", 9),
            issue("c", "java:S1192", "CRITICAL", "CODE_SMELL", "software-factory/src/C.java", 1)),
        List.of(), "MAJOR", Map.of("java:S1192", "String literals should not be duplicated"));

    assertThat(groups).extracting(FindingGroup::keyParts).containsExactly(
        List.of("rule", "backend", "java:S1192"),
        List.of("rule", "software-factory", "java:S1192"));
    assertThat(groups.getFirst().issues()).extracting(SonarIssue::key).containsExactly("a", "b");
    assertThat(groups.getFirst().ruleName()).isEqualTo("String literals should not be duplicated");
  }

  @Test
  @DisplayName("the floor drops minor code smells but never a bug or a vulnerability")
  void appliesTheFloorToCodeSmellsOnly() {
    List<FindingGroup> groups = FindingGroup.group(
        List.of(
            issue("smell", "ts:S1", "MINOR", "CODE_SMELL", "frontend/a.ts", 1),
            issue("bug", "ts:S2", "MINOR", "BUG", "frontend/b.ts", 1),
            issue("vuln", "ts:S3", "INFO", "VULNERABILITY", "frontend/c.ts", 1),
            issue("major", "ts:S4", "MAJOR", "CODE_SMELL", "frontend/d.ts", 1)),
        List.of(), "MAJOR", Map.of());

    assertThat(groups).extracting(FindingGroup::rule)
        .containsExactlyInAnyOrder("ts:S2", "ts:S3", "ts:S4");
  }

  @Test
  @DisplayName("quality-gate failures first, then defects, then severity, then size")
  void ordersForFiling() {
    List<FindingGroup> groups = FindingGroup.group(
        List.of(
            issue("1", "r:small", "MAJOR", "CODE_SMELL", "backend/a", 1),
            issue("2", "r:big", "MAJOR", "CODE_SMELL", "backend/a", 2),
            issue("3", "r:big", "MAJOR", "CODE_SMELL", "backend/b", 2),
            issue("4", "r:critical", "CRITICAL", "CODE_SMELL", "backend/c", 1),
            issue("5", "r:bug", "MINOR", "BUG", "backend/d", 1)),
        List.of(new QualityGateCondition("new_coverage", "LT", "80", "71.2")),
        "MAJOR", Map.of());

    assertThat(groups).extracting(FindingGroup::rule)
        .containsExactly("new_coverage", "r:bug", "r:critical", "r:big", "r:small");
    assertThat(groups.getFirst().keyParts()).containsExactly("quality-gate", "new_coverage");
  }

  @Test
  void ordersIssuesByLocationWhateverOrderSonarSentThem() {
    FindingGroup group = new FindingGroup(FindingKind.RULE, "frontend", "r", null,
        List.of(
            issue("x", "r", "MAJOR", "CODE_SMELL", "frontend/b.ts", 2),
            issue("y", "r", "MAJOR", "CODE_SMELL", "frontend/a.ts", 9),
            issue("z", "r", "MAJOR", "CODE_SMELL", "frontend/a.ts", 3)),
        null);

    assertThat(group.issues()).extracting(SonarIssue::key).containsExactly("z", "y", "x");
  }

  @Test
  void attributesPathsToModules() {
    assertThat(FindingGroup.moduleOf("backend/src/Main.java")).isEqualTo("backend");
    assertThat(FindingGroup.moduleOf("frontend/src/App.tsx")).isEqualTo("frontend");
    assertThat(FindingGroup.moduleOf("software-factory/x")).isEqualTo("software-factory");
    assertThat(FindingGroup.moduleOf("scripts/backup.sh")).isEqualTo(FindingGroup.REPOSITORY);
    assertThat(FindingGroup.moduleOf("build.gradle.kts")).isEqualTo(FindingGroup.REPOSITORY);
    assertThat(FindingGroup.moduleOf(null)).isEqualTo(FindingGroup.REPOSITORY);
  }

  @Test
  @DisplayName("key parts never carry prose, so a renamed rule does not fork its ticket")
  void keyPartsIgnoreTheRuleName() {
    FindingGroup before = new FindingGroup(FindingKind.RULE, "backend", "java:S1", "Old name",
        List.of(), null);
    FindingGroup after = new FindingGroup(FindingKind.RULE, "backend", "java:S1", "New name",
        List.of(), null);

    assertThat(before.keyParts()).isEqualTo(after.keyParts());
  }
}
