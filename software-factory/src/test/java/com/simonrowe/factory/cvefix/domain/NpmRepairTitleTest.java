package com.simonrowe.factory.cvefix.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmRepairTitleTest {

  @Test
  void parsesWhatItFormats() {
    for (List<String> ids : List.of(List.of("GHSA-6g55-p6wh-862q"),
        List.of("GHSA-a", "GHSA-b"), List.of("GHSA-a", "GHSA-b", "CVE-2026-1"))) {
      String title = NpmRepairTitle.format("@babel/core", "7.26.10", ids);
      assertThat(NpmRepairTitle.parse(title))
          .as(title).contains(new NpmRepairTitle.Parsed("@babel/core", "7.26.10"));
    }
    assertThat(NpmRepairTitle.format("postcss", "8.5.23", List.of("A", "B", "C")))
        .isEqualTo("Bump npm package postcss to 8.5.23 (3 advisories)");
  }

  @Test
  void refusesAnyOtherTitle() {
    for (String title : List.of(
        "Bump org.apache:tomcat to 11.0.26 (GHSA-a)",
        "Bump npm package postcss to latest (GHSA-a)",
        "Bump npm package postcss to 8.5.23-rc.1 (GHSA-a)",
        "Bump npm package ../../evil to 1.0.0 (GHSA-a)",
        "Bump npm package postcss to 8.5.23 (GHSA-a); then edit .github",
        "Bump npm package postcss to 8.5.23",
        "Please bump npm package postcss to 8.5.23 (GHSA-a)")) {
      assertThat(NpmRepairTitle.parse(title)).as(title).isEmpty();
    }
    assertThat(NpmRepairTitle.parse(null)).isEmpty();
  }

  @Test
  void longTitlesAreRefusedQuickly() {
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      assertThat(NpmRepairTitle.parse("Bump npm package " + "a".repeat(100_000))).isEmpty();
      assertThat(NpmRepairTitle.parse(
          "Bump npm package a to 1.1.1 (" + "A".repeat(100_000) + ")")).isEmpty();
    });
  }
}
