package com.simonrowe.factory.logwatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the compact constructor's replay-safety default: a {@code sourceKey} of {@code null},
 * which is what a pre-046 activity result looks like once replayed from Temporal history, must
 * not survive into a usable {@link LogSignature}.
 */
class LogSignatureTest {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

  @Test
  @DisplayName("a null sourceKey defaults to the same discriminated form an unidentifiable line "
      + "gets, keyed on the group's own signature")
  void nullSourceKeyDefaultsToDiscriminatedLineForm() {
    LogSignature signature =
        new LogSignature(
            "Agent 'WeeklyDigest' must have at least one goal defined",
            Severity.ERROR,
            "backend",
            3,
            NOW,
            NOW,
            "- MISSING_GOALS: Agent 'WeeklyDigest' must have at least one goal defined",
            null,
            List.of(),
            0);

    assertThat(signature.sourceKey())
        .isEqualTo("line:Agent 'WeeklyDigest' must have at least one goal defined");
  }

  @Test
  @DisplayName("a real sourceKey is left exactly as given")
  void nonNullSourceKeyIsUnchanged() {
    LogSignature signature =
        new LogSignature(
            "signature", Severity.WARN, "alloy", 1, NOW, NOW, "example",
            "logger:loki.write.grafana_cloud", List.of(), 0);

    assertThat(signature.sourceKey()).isEqualTo("logger:loki.write.grafana_cloud");
  }
}
