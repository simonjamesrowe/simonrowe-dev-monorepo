package com.simonrowe.factory.cvefix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MavenVersionTest {

  private static void ascending(final String... versions) {
    for (int i = 1; i < versions.length; i++) {
      assertThat(MavenVersion.of(versions[i - 1]))
          .as("%s < %s", versions[i - 1], versions[i])
          .isLessThan(MavenVersion.of(versions[i]));
    }
  }

  @Test
  void ordersNumbersNumericallyNotAlphabetically() {
    ascending("1.2", "1.9", "1.10", "1.10.1", "2.0");
    ascending("11.0.9", "11.0.25", "11.0.26");
  }

  @Test
  void ordersPreReleasesBeforeTheRelease() {
    ascending("1.0-alpha1", "1.0-beta2", "1.0-M1", "1.0-RC1", "1.0-SNAPSHOT", "1.0", "1.0-sp1",
        "1.0.1");
  }

  @Test
  void releaseWordsAndTrailingZerosAreTheSameVersion() {
    for (List<String> same : List.of(
        List.of("4.1.118.Final", "4.1.118"),
        List.of("1.0.0", "1", "1.0.GA", "1.0-RELEASE"),
        List.of("1.0-a1", "1.0-alpha-1"))) {
      assertThat(MavenVersion.of(same.get(0))).isEqualTo(MavenVersion.of(same.get(1)));
      assertThat(MavenVersion.of(same.get(0)).hashCode())
          .isEqualTo(MavenVersion.of(same.get(1)).hashCode());
    }
    ascending("4.1.118.Final", "4.1.119.Final");
  }

  @Test
  void readsTheMajorVersion() {
    assertThat(MavenVersion.of("11.0.25").major()).isEqualTo(11);
    assertThat(MavenVersion.of("v3-rev20250511-2.0.0").major()).isEqualTo(-1);
  }
}
