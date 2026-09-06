package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogWatchReportRendererTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  private static LogSignature signature(final String sourceKey, final int distinctVariants) {
    return new LogSignature(
        "sig one", Severity.ERROR, "simonrowe-dev-monorepo-backend-1", 9, T0, T0, "raw one",
        sourceKey,
        List.of(
            new LogSignature.Variant("sig one", 6, "raw one"),
            new LogSignature.Variant("sig two", 3, "raw two")),
        distinctVariants);
  }

  @Test
  @DisplayName("the title names the emitting class, abbreviated to its last segment")
  void titleNamesTheSource() {
    String title =
        LogWatchReportRenderer.title(
            signature("logger:com.embabel.agent.spi.validation.DefaultAgentValidationManager", 2));

    assertThat(title).contains("DefaultAgentValidationManager");
    assertThat(title).contains("simonrowe-dev-monorepo-backend-1");
    assertThat(title).doesNotContain("com.embabel.agent.spi");
  }

  @Test
  @DisplayName("a line-keyed group keeps the old title shape, with no empty parentheses")
  void titleOmitsTheSourceWhenThereIsNone() {
    assertThat(LogWatchReportRenderer.title(signature("line:ERROR: something broke", 1)))
        .doesNotContain("()");
  }

  @Test
  @DisplayName("a Temporal msg source is a sentence and is not abbreviated at its full stop")
  void sentenceSourcesAreNotAbbreviated() {
    assertThat(LogWatchReportRenderer.shortSource(signature("logger:Operation failed.", 1)))
        .isEqualTo("Operation failed.");
  }

  @Test
  @DisplayName("the body lists every variant with its count")
  void bodyListsVariants() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 2), T0, T0);

    assertThat(body).contains("sig one");
    assertThat(body).contains("sig two");
    assertThat(body).contains("6");
    assertThat(body).contains("3");
  }

  @Test
  @DisplayName("the body says how many variants were withheld, so a cap is never silent")
  void bodyReportsTruncation() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 11), T0, T0);

    assertThat(body).contains("9 further");
  }

  @Test
  @DisplayName("the body names the source key as what deduplicates the issue")
  void bodyNamesTheDedupKey() {
    String body = LogWatchReportRenderer.body(signature("logger:com.example.Thing", 2), T0, T0);

    assertThat(body).contains("com.example.Thing");
    assertThat(body).contains("deduplicates");
  }
}
