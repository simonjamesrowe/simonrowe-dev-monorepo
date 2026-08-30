package com.simonrowe.factory.cvefix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentFindingsTest {

  private static Finding finding(
      final String project, final String purl, final String id, final String severity) {
    return new Finding(project, purl, "lib", "1", id, severity, "");
  }

  @Test
  void sortsFindingsBySeverityThenVulnerabilityId() {
    ComponentFindings component =
        new ComponentFindings(
            "simonrowe-dev/backend",
            "pkg:maven/a/b@1",
            "b",
            "1",
            List.of(),
            List.of(
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-2", "LOW"),
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-9", "CRITICAL"),
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-1", "HIGH"),
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-0", "CRITICAL")));

    assertThat(component.findings())
        .extracting(Finding::vulnerabilityId)
        .containsExactly("CVE-0", "CVE-9", "CVE-1", "CVE-2");
  }

  @Test
  void keepsTheSamePurlInTwoProjectsApart() {
    List<ComponentFindings> grouped =
        ComponentFindings.group(
            List.of(
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-1", "HIGH"),
                finding("simonrowe-dev/frontend", "pkg:maven/a/b@1", "CVE-1", "HIGH")));

    assertThat(grouped).hasSize(2);
    assertThat(grouped)
        .extracting(ComponentFindings::project)
        .containsExactly("simonrowe-dev/backend", "simonrowe-dev/frontend");
  }

  @Test
  void keepsProjectsInFirstAppearanceOrderRegardlessOfSeverity() {
    // The client iterates the configured project list, so first appearance IS config order.
    // A more severe finding in a later project must not promote that project's heading.
    List<ComponentFindings> grouped =
        ComponentFindings.group(
            List.of(
                finding("simonrowe-dev/backend", "pkg:maven/a/b@1", "CVE-1", "LOW"),
                finding("simonrowe-dev/frontend", "pkg:npm/c@2", "CVE-2", "CRITICAL")));

    assertThat(grouped)
        .extracting(ComponentFindings::project)
        .containsExactly("simonrowe-dev/backend", "simonrowe-dev/frontend");
  }

  @Test
  void ordersComponentsWithinProjectByWorstSeverityThenPurl() {
    List<ComponentFindings> grouped =
        ComponentFindings.group(
            List.of(
                finding("p", "pkg:maven/z/z@1", "CVE-1", "MEDIUM"),
                finding("p", "pkg:maven/a/a@1", "CVE-2", "MEDIUM"),
                finding("p", "pkg:maven/m/m@1", "CVE-3", "LOW"),
                finding("p", "pkg:maven/m/m@1", "CVE-4", "CRITICAL")));

    assertThat(grouped)
        .extracting(ComponentFindings::purl)
        .containsExactly("pkg:maven/m/m@1", "pkg:maven/a/a@1", "pkg:maven/z/z@1");
  }

  @Test
  void populatesVulnerabilityIdsInTheSameOrderAsTheFindings() {
    List<ComponentFindings> grouped =
        ComponentFindings.group(
            List.of(
                finding("p", "pkg:maven/a/b@1", "CVE-2", "LOW"),
                finding("p", "pkg:maven/a/b@1", "CVE-1", "CRITICAL")));

    assertThat(grouped.get(0).vulnerabilityIds()).containsExactly("CVE-1", "CVE-2");
    assertThat(grouped.get(0).findings())
        .extracting(Finding::vulnerabilityId)
        .containsExactly("CVE-1", "CVE-2");
  }

  @Test
  void toleratesNullListsAndNullSeverity() {
    ComponentFindings component =
        new ComponentFindings("p", "pkg:maven/a/b@1", "b", "1", null, null);

    assertThat(component.vulnerabilityIds()).isEmpty();
    assertThat(component.findings()).isEmpty();

    ComponentFindings withNullSeverity =
        new ComponentFindings(
            "p",
            "pkg:maven/a/b@1",
            "b",
            "1",
            List.of(),
            List.of(finding("p", "pkg:maven/a/b@1", "CVE-1", null)));

    assertThat(withNullSeverity.findings()).hasSize(1);
  }

  @Test
  void toleratesNullVulnerabilityIdThroughGroup() {
    List<ComponentFindings> grouped =
        ComponentFindings.group(List.of(finding("p", "pkg:maven/a/b@1", null, "HIGH")));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.get(0).vulnerabilityIds()).containsExactly((String) null);
  }
}
