package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.cvefix.domain.NpmRepairTitle;
import com.simonrowe.factory.cvefix.domain.RepairPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class CveRepairTicketTest {

  private static final RepairPlan NPM = new RepairPlan("simonrowe-dev/frontend",
      "pkg:npm/postcss@8.5.6", "postcss", "8.5.6", "8.5.23",
      List.of("GHSA-6g55-p6wh-862q", "GHSA-r28c-9q8g-f849"), "HIGH", "npm");

  /** The agent finds the npm path by parsing this title, so the two must agree. */
  @Test
  void npmTitleIsOneTheAgentRecognises() {
    String title = CveRepairTicket.title(NPM);

    assertThat(title).isEqualTo(
        "Bump npm package postcss to 8.5.23 (GHSA-6g55-p6wh-862q, GHSA-r28c-9q8g-f849)");
    assertThat(NpmRepairTitle.parse(title))
        .contains(new NpmRepairTitle.Parsed("postcss", "8.5.23"));
  }

  @Test
  void npmBodySaysTheFactoryRegeneratesTheLockfile() {
    assertThat(CveRepairTicket.body(NPM))
        .contains("npm package `postcss` 8.5.6 in `simonrowe-dev/frontend`", "**8.5.23**",
            "No agent edits anything", "--package-lock-only --ignore-scripts",
            "comes back to a person", "GHSA-6g55-p6wh-862q, GHSA-r28c-9q8g-f849")
        .doesNotContain("constraints {}");
  }

  @Test
  void npmAndMavenKeysStayApart() {
    assertThat(CveRepairTicket.keyParts(NPM)).containsExactly(
        "simonjamesrowe/simonrowe-dev-monorepo", "repair", "simonrowe-dev/frontend",
        "pkg:npm/postcss@8.5.6");
  }
}
