package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.Finding;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CveReportRendererTest {

  private static ComponentFindings component(
      final String project, final String purl, final String name, final Finding... findings) {
    List<String> vulnerabilityIds =
        Arrays.stream(findings).map(Finding::vulnerabilityId).toList();
    return new ComponentFindings(project, purl, name, "1.0", vulnerabilityIds, List.of(findings));
  }

  private static Finding finding(final String id, final String severity, final String advice) {
    return new Finding("p", "purl", "name", "1.0", id, severity, advice);
  }

  @Test
  void headsEachProjectAndNestsItsComponentsBeneath() {
    String report =
        CveReportRenderer.report(
            List.of(
                component(
                    "simonrowe-dev/backend",
                    "pkg:maven/a/b@1.0",
                    "b",
                    finding("CVE-1", "HIGH", "")),
                component(
                    "simonrowe-dev/frontend",
                    "pkg:npm/c@2.0",
                    "c",
                    finding("CVE-2", "LOW", ""))),
            2);

    assertThat(report)
        .contains("## simonrowe-dev/backend")
        .contains("## simonrowe-dev/frontend")
        .contains("### b `1.0`")
        .contains("### c `1.0`")
        .contains("**Package:** `pkg:maven/a/b@1.0`");
    assertThat(report.indexOf("## simonrowe-dev/backend"))
        .isLessThan(report.indexOf("### b `1.0`"));
    assertThat(report.indexOf("### b `1.0`"))
        .isLessThan(report.indexOf("## simonrowe-dev/frontend"));
  }

  @Test
  void headerCountsFindingsComponentsAndNamesEveryProject() {
    String report =
        CveReportRenderer.report(
            List.of(
                component("simonrowe-dev/backend", "pkg:maven/a/b@1.0", "b",
                    finding("CVE-1", "HIGH", "")),
                component("simonrowe-dev/frontend", "pkg:npm/c@2.0", "c",
                    finding("CVE-2", "LOW", ""))),
            5);

    assertThat(report)
        .contains("**5 finding(s)**")
        .contains("**2 component(s)**")
        .contains("**2 project(s)**")
        .contains("`simonrowe-dev/backend`, `simonrowe-dev/frontend`");
  }

  @Test
  void listsAdvisoriesMostSevereFirst() {
    String report =
        CveReportRenderer.report(
            List.of(
                component(
                    "p",
                    "pkg:maven/a/b@1.0",
                    "b",
                    finding("CVE-LOW", "LOW", ""),
                    finding("CVE-CRIT", "CRITICAL", ""),
                    finding("CVE-MED", "MEDIUM", ""))),
            3);

    assertThat(report.indexOf("**CVE-CRIT** (CRITICAL)"))
        .isLessThan(report.indexOf("**CVE-MED** (MEDIUM)"));
    assertThat(report.indexOf("**CVE-MED** (MEDIUM)"))
        .isLessThan(report.indexOf("**CVE-LOW** (LOW)"));
  }

  @Test
  void appendsTheRecommendationOnlyWhenPresent() {
    String report =
        CveReportRenderer.report(
            List.of(
                component(
                    "p",
                    "pkg:maven/a/b@1.0",
                    "b",
                    finding("CVE-1", "HIGH", "Upgrade to 2.0"),
                    finding("CVE-2", "LOW", "   "))),
            2);

    assertThat(report).contains("**CVE-1** (HIGH): Upgrade to 2.0");
    assertThat(report).contains("**CVE-2** (LOW)\n");
    assertThat(report).doesNotContain("**CVE-2** (LOW):");
  }

  @Test
  void rendersAnUnattributedComponentUnderAnExplicitHeading() {
    // Reachable on Temporal replay of a history written before the project field existed:
    // an absent JSON property deserializes to null.
    String report =
        CveReportRenderer.report(
            List.of(component(null, "pkg:maven/a/b@1.0", "b", finding("CVE-1", "HIGH", ""))), 1);

    assertThat(report).contains("## (unknown project)");
  }

  @Test
  void emitsOneHeadingPerProjectNotOnePerComponent() {
    String report =
        CveReportRenderer.report(
            List.of(
                component("p", "pkg:maven/a/b@1.0", "b", finding("CVE-1", "HIGH", "")),
                component("p", "pkg:maven/a/c@1.0", "c", finding("CVE-2", "HIGH", ""))),
            2);

    assertThat(report.split("## p", -1)).hasSize(2);
  }

  @Test
  void advisoriesLineListsEveryVulnerabilityId() {
    String report =
        CveReportRenderer.report(
            List.of(
                component(
                    "p",
                    "pkg:maven/a/b@1.0",
                    "b",
                    finding("CVE-1", "HIGH", ""),
                    finding("CVE-2", "MEDIUM", ""),
                    finding("CVE-3", "LOW", ""))),
            3);

    assertThat(report).contains("- **Advisories:** CVE-1, CVE-2, CVE-3");
  }

  @Test
  void rendersPlaceholderForNullVulnerabilityId() {
    // Reachable on Temporal replay of a history written before every finding carried an id:
    // ComponentFindings deliberately tolerates a null vulnerabilityId.
    String report =
        CveReportRenderer.report(
            List.of(component("p", "pkg:maven/a/b@1.0", "b", finding(null, "HIGH", ""))), 1);

    assertThat(report)
        .contains("- **Advisories:** (unidentified advisory)")
        .contains("- **(unidentified advisory)** (HIGH)")
        .doesNotContain("null");
  }
}
