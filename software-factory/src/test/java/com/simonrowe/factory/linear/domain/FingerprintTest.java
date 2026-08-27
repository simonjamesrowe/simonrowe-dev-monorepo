package com.simonrowe.factory.linear.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprintTest {

  @Test
  void isStableAcrossCalls() {
    assertThat(Fingerprint.of("deploy", List.of("recreate", "backend")))
        .isEqualTo(Fingerprint.of("deploy", List.of("recreate", "backend")));
  }

  @Test
  void isSixtyFourLowercaseHexCharacters() {
    assertThat(Fingerprint.of("cvefix", List.of("pkg:maven/com.foo/bar@1.0")))
        .hasSize(64)
        .matches("[0-9a-f]{64}");
  }

  @Test
  void differsByProducerEvenWithIdenticalKeyParts() {
    assertThat(Fingerprint.of("deploy", List.of("x")))
        .isNotEqualTo(Fingerprint.of("cvefix", List.of("x")));
  }

  @Test
  void differsByKeyPartOrderAndBoundary() {
    // Joining without a separator would make ("ab","c") and ("a","bc") collide, so the
    // separator is load-bearing, not cosmetic.
    assertThat(Fingerprint.of("deploy", List.of("ab", "c")))
        .isNotEqualTo(Fingerprint.of("deploy", List.of("a", "bc")));
    assertThat(Fingerprint.of("deploy", List.of("a", "b")))
        .isNotEqualTo(Fingerprint.of("deploy", List.of("b", "a")));
  }

  @Test
  void buildsTheAttachmentUrlWithoutDoubleSlashes() {
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    assertThat(Fingerprint.urlFor("https://factory.simonrowe.dev/fingerprint/", fingerprint))
        .isEqualTo("https://factory.simonrowe.dev/fingerprint/" + fingerprint);
    assertThat(Fingerprint.urlFor("https://factory.simonrowe.dev/fingerprint", fingerprint))
        .isEqualTo("https://factory.simonrowe.dev/fingerprint/" + fingerprint);
  }

  @Test
  void refusesAnEmptyKeyPartSetRatherThanFilingEverythingAsOneProblem() {
    assertThatThrownBy(() -> Fingerprint.of("deploy", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key parts");
  }

  @Test
  void refusesBlankProducer() {
    assertThatThrownBy(() -> Fingerprint.of(" ", List.of("x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("producer");
  }
}
