package com.simonrowe.factory.logwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.domain.LogWatchPhase;
import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** The token gate, the disabled gate, and the default-window behaviour of the manual API. */
class LogWatchControllerTest {

  private static final String TOKEN = "trigger-token";

  private LogWatchWorkflowService service;

  @BeforeEach
  void setUp() {
    service = mock(LogWatchWorkflowService.class);
    when(service.start(any(), any(), anyBoolean()))
        .thenReturn(new LogWatchScanAccepted("wf", "run", "accepted"));
  }

  private LogWatchController controller(final boolean enabled) {
    CodeReviewProperties codeReview =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "secret", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(TOKEN, null),
            "https://temporal.test");
    return new LogWatchController(
        new FactoryTokenAuthenticator(codeReview),
        new LogWatchProperties(enabled, 2, 5, null, 5000, 3, null, null),
        service);
  }

  @Test
  void startsScanWithTheRightToken() {
    ResponseEntity<LogWatchScanAccepted> response =
        controller(true).start(TOKEN, new LogWatchScanRequest(null, null, false));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNotNull();
    verify(service).start(null, null, false);
  }

  @Test
  @DisplayName("an absent body is treated as a default, non-dry real scan")
  void toleratesAnAbsentBody() {
    controller(true).start(TOKEN, null);

    verify(service).start(null, null, false);
  }

  @Test
  @DisplayName("an absent dryRun flag is false, not null")
  void absentDryRunFlagIsFalse() {
    controller(true).start(TOKEN, new LogWatchScanRequest(null, null, null));

    verify(service).start(null, null, false);
  }

  @Test
  void passesThroughAnExplicitWindowAndDryRun() {
    Instant from = Instant.parse("2026-09-01T00:00:00Z");
    Instant to = Instant.parse("2026-09-02T00:00:00Z");

    controller(true).start(TOKEN, new LogWatchScanRequest(from, to, true));

    verify(service).start(eq(from), eq(to), eq(true));
  }

  @Test
  @DisplayName("a disabled module reports 503, distinct from an outage")
  void refusesWhenDisabled() {
    assertThatThrownBy(
            () -> controller(false).start(TOKEN, new LogWatchScanRequest(null, null, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("503");
  }

  @Test
  void refusesWithoutValidToken() {
    assertThatThrownBy(() -> controller(true).start(null, null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> controller(true).start("wrong", null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("the token is checked before the enabled flag, so a disabled module leaks nothing")
  void authenticatesBeforeReportingWhetherTheModuleIsEnabled() {
    // Otherwise an unauthenticated caller could distinguish "disabled" from "enabled" by the
    // status code, which is a small but free information leak.
    assertThatThrownBy(() -> controller(false).start("wrong", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("503");
  }

  @Test
  void readsProgress() {
    when(service.progress("wf"))
        .thenReturn(new LogWatchProgress(LogWatchPhase.READING, "reading", 0));

    assertThat(controller(true).progress(TOKEN, "wf").phase()).isEqualTo(LogWatchPhase.READING);
  }
}
