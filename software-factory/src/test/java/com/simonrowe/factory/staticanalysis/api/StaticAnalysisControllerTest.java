package com.simonrowe.factory.staticanalysis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisPhase;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisProgress;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** The token gate, the disabled gate and the dry-run flag of the manual API. */
class StaticAnalysisControllerTest {

  private static final String TOKEN = "trigger-token";

  private StaticAnalysisWorkflowService service;

  @BeforeEach
  void setUp() {
    service = mock(StaticAnalysisWorkflowService.class);
    when(service.start(anyBoolean()))
        .thenReturn(new StaticAnalysisScanAccepted("wf", "run", "accepted"));
  }

  private StaticAnalysisController controller(final boolean enabled) {
    CodeReviewProperties codeReview =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "secret", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(TOKEN, null),
            "https://temporal.test");
    return new StaticAnalysisController(
        new FactoryTokenAuthenticator(codeReview),
        new StaticAnalysisProperties(
            enabled, null, null, null, null, null, null, null, null, 0, null, null, null, null,
            null),
        service);
  }

  @Test
  void startsScanWithTheRightToken() {
    ResponseEntity<StaticAnalysisScanAccepted> response =
        controller(true).start(TOKEN, new StaticAnalysisScanRequest(true));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNotNull();
    verify(service).start(true);
  }

  @Test
  @DisplayName("an absent body or flag is a real scan, not a null")
  void toleratesAnAbsentBodyAndFlag() {
    controller(true).start(TOKEN, null);
    controller(true).start(TOKEN, new StaticAnalysisScanRequest(null));

    verify(service, times(2)).start(false);
  }

  @Test
  @DisplayName("a disabled module reports 503, distinct from an outage")
  void refusesWhenDisabled() {
    assertThatThrownBy(() -> controller(false).start(TOKEN, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("503");
  }

  @Test
  void checksTheTokenBeforeTheEnabledFlag() {
    assertThatThrownBy(() -> controller(false).start("wrong", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageNotContaining("503");
    assertThatThrownBy(() -> controller(true).progress(null, "wf"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void readsProgress() {
    when(service.progress("wf")).thenReturn(
        new StaticAnalysisProgress(StaticAnalysisPhase.FILING, "filing", 2));

    assertThat(controller(true).progress(TOKEN, "wf").phase())
        .isEqualTo(StaticAnalysisPhase.FILING);
  }
}
