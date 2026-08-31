package com.simonrowe.factory.logwatch.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.SourceHealth;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check that stops this module reporting a confident all-clear over a blind spot.
 *
 * <p>The central case is {@link #replaysTheAugustOutage()}: for three weeks Grafana Cloud accepted
 * nothing while every health signal stayed green, and a module without this check would have filed
 * nothing and been self-consistently correct every single night.
 */
class SourceHealthCheckerTest {

  private static final Duration DAY = Duration.ofHours(24);
  private static final int FLOOR = 3;

  @Test
  @DisplayName("the August 2026 outage, replayed, is SILENT and names the 429")
  void replaysTheAugustOutage() {
    String realError =
        "server returned HTTP status 429 Too Many Requests (429): ingestion rate limit "
            + "exceeded for user 1539009 (limit: 0 bytes/sec)";

    SourceHealth health =
        SourceHealthChecker.check(Optional.of(realError), true, false, 0, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.SILENT);
    assertThat(health.tier()).isEqualTo(SourceHealth.Tier.ALLOY_COMPONENT);
    assertThat(health.usable()).isFalse();
    // The evidence has to name the cause. "No logs found" sends an operator to check credentials
    // that are perfectly fine; this sends them to the billing page.
    assertThat(health.evidence()).contains("0 bytes/sec");
  }

  @Test
  @DisplayName("a healthy write path makes quiet mean quiet")
  void healthyAlloyMakesEmptinessTrustworthy() {
    SourceHealth health =
        SourceHealthChecker.check(Optional.empty(), true, false, 0, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.ALIVE);
    assertThat(health.tier()).isEqualTo(SourceHealth.Tier.ALLOY_COMPONENT);
    assertThat(health.usable()).isTrue();
  }

  @Test
  @DisplayName("Alloy's verdict outranks coverage, even when coverage looks fine")
  void alloyOutranksCoverage() {
    SourceHealth health =
        SourceHealthChecker.check(Optional.of("401 unauthorized"), true, false, 20, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.SILENT);
    assertThat(health.tier()).isEqualTo(SourceHealth.Tier.ALLOY_COMPONENT);
  }

  @Test
  @DisplayName("with Alloy unreachable, thin coverage over a long window is SILENT")
  void thinCoverageIsSilent() {
    SourceHealth health =
        SourceHealthChecker.check(Optional.empty(), false, false, 1, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.SILENT);
    assertThat(health.tier()).isEqualTo(SourceHealth.Tier.CONTAINER_COVERAGE);
    assertThat(health.evidence()).contains("inferred from silence");
  }

  @Test
  void adequateCoverageIsAlive() {
    SourceHealth health =
        SourceHealthChecker.check(Optional.empty(), false, false, 8, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.ALIVE);
    assertThat(health.tier()).isEqualTo(SourceHealth.Tier.CONTAINER_COVERAGE);
  }

  /**
   * The false-positive this whole tier has to avoid.
   *
   * <p>A post-deploy scan covers five minutes, and a five-minute window over a stack that happens
   * to be idle legitimately contains no error or warning lines at all. Applying the coverage floor
   * to it would file a source-health ticket after every quiet deploy, which teaches an operator to
   * ignore precisely the signal this module exists to raise.
   */
  @Test
  @DisplayName("a five-minute post-deploy window is never judged on coverage")
  void shortWindowsAreNotJudgedOnCoverage() {
    SourceHealth health =
        SourceHealthChecker.check(
            Optional.empty(), false, false, 0, FLOOR, Duration.ofMinutes(5));

    assertThat(health.status()).isEqualTo(SourceHealth.Status.ALIVE);
    assertThat(health.evidence()).contains("too short to infer");
  }

  @Test
  @DisplayName("a failed query is UNREACHABLE, and outranks everything")
  void failedQueryIsUnreachable() {
    SourceHealth health =
        SourceHealthChecker.check(Optional.empty(), true, true, 20, FLOOR, DAY);

    assertThat(health.status()).isEqualTo(SourceHealth.Status.UNREACHABLE);
    assertThat(health.usable()).isFalse();
  }

  @Test
  @DisplayName("SILENT and UNREACHABLE are distinct - they have different fixes")
  void silentAndUnreachableAreNotConflated() {
    SourceHealth silent =
        SourceHealthChecker.check(Optional.of("429"), true, false, 0, FLOOR, DAY);
    SourceHealth unreachable =
        SourceHealthChecker.check(Optional.empty(), false, true, 0, FLOOR, DAY);

    assertThat(silent.status()).isNotEqualTo(unreachable.status());
    assertThat(silent.usable()).isFalse();
    assertThat(unreachable.usable()).isFalse();
  }
}
