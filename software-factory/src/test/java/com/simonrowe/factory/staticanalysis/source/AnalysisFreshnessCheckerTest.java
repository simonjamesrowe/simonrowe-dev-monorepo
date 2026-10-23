package com.simonrowe.factory.staticanalysis.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth.Status;
import com.simonrowe.factory.staticanalysis.source.BranchHeadClient.Head;
import com.simonrowe.factory.staticanalysis.source.SonarCloudClient.Analysis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnalysisFreshnessCheckerTest {

  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  private static final Duration GRACE = Duration.ofHours(6);
  private static final Analysis ANALYSIS =
      new Analysis("aaaaaaaaaaaa", Instant.parse("2026-10-20T14:18:11Z"));

  @Test
  void analysisOfTheHeadIsFresh() {
    AnalysisHealth health = AnalysisFreshnessChecker.judge(
        ANALYSIS, new Head("aaaaaaaaaaaa", NOW.minus(Duration.ofDays(3))), null, NOW, GRACE,
        "main");

    assertThat(health.status()).isEqualTo(Status.FRESH);
    assertThat(health.analysedRevision()).isEqualTo("aaaaaaaaaaaa");
  }

  @Test
  @DisplayName("a head younger than the grace is still being analysed, so the last one stands")
  void recentHeadUsesThePreviousAnalysis() {
    AnalysisHealth health = AnalysisFreshnessChecker.judge(
        ANALYSIS, new Head("bbbbbbbbbbbb", NOW.minus(Duration.ofMinutes(25))), null, NOW, GRACE,
        "main");

    assertThat(health.status()).isEqualTo(Status.FRESH);
    assertThat(health.evidence()).contains("25m", "bbbbbbbb", "aaaaaaaa");
  }

  @Test
  @DisplayName("a head unanalysed past the grace is stale: what happened to main from 2026-10-20")
  void unanalysedHeadPastTheGraceIsStale() {
    AnalysisHealth health = AnalysisFreshnessChecker.judge(
        ANALYSIS, new Head("f9b7d532aaaa", Instant.parse("2026-10-21T04:33:24Z")), null, NOW,
        GRACE, "main");

    assertThat(health.status()).isEqualTo(Status.STALE);
    assertThat(health.usable()).isFalse();
    assertThat(health.evidence()).contains("f9b7d532", "aaaaaaaa", "build job fails");
  }

  @Test
  void noAnalysisAtAllIsReported() {
    AnalysisHealth health = AnalysisFreshnessChecker.judge(
        null, new Head("bbbbbbbbbbbb", NOW), null, NOW, GRACE, "main");

    assertThat(health.status()).isEqualTo(Status.NO_ANALYSIS);
  }

  @Test
  @DisplayName("GitHub being down does not stop the module reading a true analysis")
  void unreadableHeadStillUsesTheAnalysis() {
    AnalysisHealth health = AnalysisFreshnessChecker.judge(
        ANALYSIS, null, "GitHub GET /repos returned 503", NOW, GRACE, "main");

    assertThat(health.status()).isEqualTo(Status.FRESH);
    assertThat(health.evidence()).contains("could not confirm", "503");
  }

  @Test
  void unreachableSonarCloudIsReportedNotThrown() {
    SonarCloudClient sonar = mock(SonarCloudClient.class);
    BranchHeadClient github = mock(BranchHeadClient.class);
    when(sonar.latestAnalysis())
        .thenThrow(new IllegalStateException("SonarCloud GET /api/x returned 502"));

    AnalysisHealth health = new AnalysisFreshnessChecker(sonar, github, properties(),
        Clock.fixed(NOW, ZoneOffset.UTC)).check();

    assertThat(health.status()).isEqualTo(Status.UNREACHABLE);
    assertThat(health.evidence()).contains("502");
  }

  @Test
  void checksTheHeadAgainstTheLatestAnalysis() {
    SonarCloudClient sonar = mock(SonarCloudClient.class);
    BranchHeadClient github = mock(BranchHeadClient.class);
    when(sonar.latestAnalysis()).thenReturn(Optional.of(ANALYSIS));
    when(github.head()).thenThrow(new IllegalStateException("GitHub GET returned 403"));

    AnalysisHealth health = new AnalysisFreshnessChecker(sonar, github, properties(),
        Clock.fixed(NOW, ZoneOffset.UTC)).check();

    assertThat(health.status()).isEqualTo(Status.FRESH);
    assertThat(health.headRevision()).isNull();
  }

  private static StaticAnalysisProperties properties() {
    return new StaticAnalysisProperties(
        true, null, null, null, null, null, null, null, null, 0, null, null, null, null, null);
  }
}
