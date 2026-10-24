package com.simonrowe.factory.ticketagent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConventionalTitleTest {

  /** The title the agent actually gave #287 on 2026-10-24. */
  @Test
  void titleWithoutTypeIsGivenFix() {
    assertThat(ConventionalTitle.of("Bump plexus-utils to 3.6.1 (GHSA-6fmv-xxpf-w3cw)"))
        .isEqualTo("fix: bump plexus-utils to 3.6.1 (GHSA-6fmv-xxpf-w3cw)");
  }

  @Test
  void conventionalTitlesAreLeftAlone() {
    for (String title : new String[] {
        "fix: update npm package zod to 4.6.7",
        "feat: add a reading list",
        "chore(deps): bump lz4",
        "refactor!: drop the old exporter"}) {
      assertThat(ConventionalTitle.of("  " + title + " ")).isEqualTo(title);
    }
  }

  @Test
  void acronymsKeepTheirCapitals() {
    assertThat(ConventionalTitle.of("CVE fix for plexus-utils"))
        .isEqualTo("fix: CVE fix for plexus-utils");
  }

  /** A type without the colon and space, or an unknown type, is not conventional. */
  @Test
  void nearMissesGetTheDefaultType() {
    assertThat(ConventionalTitle.of("fix guard the exporter"))
        .isEqualTo("fix: fix guard the exporter");
    assertThat(ConventionalTitle.of("Fixes: the exporter")).isEqualTo("fix: fixes: the exporter");
    assertThat(ConventionalTitle.of("fix:no space")).isEqualTo("fix: fix:no space");
  }

  @Test
  void blankIsNullSoTheCallersFallbackApplies() {
    assertThat(ConventionalTitle.of(null)).isNull();
    assertThat(ConventionalTitle.of("   ")).isNull();
  }

  @Test
  void longTitlesAreCheckedQuickly() {
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      assertThat(ConventionalTitle.of("fix(" + "a".repeat(100_000))).startsWith("fix: fix(");
      assertThat(ConventionalTitle.of("fix: " + "x".repeat(100_000))).startsWith("fix: x");
    });
  }
}
