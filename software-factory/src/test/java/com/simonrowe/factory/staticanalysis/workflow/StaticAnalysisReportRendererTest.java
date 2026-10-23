package com.simonrowe.factory.staticanalysis.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.FindingKind;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import com.simonrowe.factory.staticanalysis.domain.SonarProject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StaticAnalysisReportRendererTest {

  private static final SonarProject PROJECT = new SonarProject(
      "https://sonarcloud.io", "simonjamesrowe", "simonjamesrowe_simonrowe-dev-monorepo", "main");

  private static final AnalysisHealth FRESH = new AnalysisHealth(AnalysisHealth.Status.FRESH,
      "fresh", "5c893af3", Instant.parse("2026-10-20T14:18:11Z"), "5c893af3");

  @Test
  void titlesRuleGroupByItsNameAndModule() {
    FindingGroup group = new FindingGroup(FindingKind.RULE, "frontend", "typescript:S6845",
        "Non-interactive DOM elements should not have the `tabindex` attribute", List.of(), null);

    assertThat(StaticAnalysisReportRenderer.title(group)).isEqualTo(
        """
        Static analysis (frontend): Non-interactive DOM elements should not have the \
        `tabindex` attribute""");
  }

  @Test
  void fallsBackToTheRuleKeyWithoutName() {
    FindingGroup group =
        new FindingGroup(FindingKind.RULE, "backend", "java:S1192", null, List.of(), null);

    assertThat(StaticAnalysisReportRenderer.title(group))
        .isEqualTo("Static analysis (backend): java:S1192");
  }

  @Test
  void listsEveryLocationWithLinkToSonarCloud() {
    FindingGroup group = new FindingGroup(FindingKind.RULE, "frontend", "typescript:S6845", null,
        List.of(new SonarIssue("AaFQ", "typescript:S6845", "MAJOR", "CODE_SMELL",
            "frontend/src/ReadAlong.tsx", 252, "`tabIndex` should only be declared")),
        null);

    String body = StaticAnalysisReportRenderer.body(group, PROJECT, FRESH);

    assertThat(body)
        .contains("- `frontend/src/ReadAlong.tsx:252` (MAJOR code smell): `tabIndex`")
        .contains("""
            https://sonarcloud.io/project/issues?id=simonjamesrowe_simonrowe-dev-monorepo\
            &branch=main&open=AaFQ""")
        .contains("rules?open=typescript%3AS6845")
        .contains("analysis of `5c893af3`");
  }

  @Test
  @DisplayName("the body tells the agent that silencing the rule is not a fix")
  void forbidsSilencing() {
    FindingGroup group =
        new FindingGroup(FindingKind.RULE, "backend", "java:S1192", null, List.of(), null);

    assertThat(StaticAnalysisReportRenderer.body(group, PROJECT, FRESH))
        .contains("Do not silence it", "NOSONAR", "@SuppressWarnings", "eslint-disable");
  }

  @Test
  void capsTheListedIssues() {
    List<SonarIssue> issues = IntStream.rangeClosed(1, 60)
        .mapToObj(line -> new SonarIssue("k" + line, "java:S1192", "MAJOR", "CODE_SMELL",
            "backend/A.java", line, "m"))
        .toList();
    FindingGroup group =
        new FindingGroup(FindingKind.RULE, "backend", "java:S1192", null, issues, null);

    String body = StaticAnalysisReportRenderer.body(group, PROJECT, FRESH);

    assertThat(body).contains("**60 open issue(s)**", "... and 10 more in SonarCloud");
    assertThat(body).contains("A.java:50`").doesNotContain("A.java:51`");
  }

  @Test
  void describesFailingQualityGateCondition() {
    FindingGroup group =
        FindingGroup.qualityGate(new QualityGateCondition("new_coverage", "LT", "80", "71.2"));

    assertThat(StaticAnalysisReportRenderer.title(group))
        .isEqualTo("Static analysis: quality gate failing on coverage on new code");
    assertThat(StaticAnalysisReportRenderer.body(group, PROJECT, FRESH))
        .contains("71.2, where the gate requires at least 80");
  }

  @Test
  void saysWhichRevisionClosedTicketWasCheckedAgainst() {
    assertThat(StaticAnalysisReportRenderer.resolutionComment(
            Duration.ofHours(12), "run-1", FRESH))
        .contains("`5c893af3`", "12h", "run-1");
  }
}
