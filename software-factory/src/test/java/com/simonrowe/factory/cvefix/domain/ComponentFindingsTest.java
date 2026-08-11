package com.simonrowe.factory.cvefix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentFindingsTest {

  @Test
  void sortsOutOfOrderVulnerabilityIdsSoFingerprintIsStable() {
    ComponentFindings findings =
        new ComponentFindings(
            "pkg:maven/a/b@1", "b", "1", List.of("CVE-9", "CVE-1", "CVE-5"), List.of());

    assertThat(findings.vulnerabilityIds()).containsExactly("CVE-1", "CVE-5", "CVE-9");
    assertThat(findings.fingerprint()).isEqualTo("pkg:maven/a/b@1|CVE-1,CVE-5,CVE-9");
  }
}
