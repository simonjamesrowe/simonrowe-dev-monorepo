package com.simonrowe.factory.linear.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.service.IssueFiler;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearActivitiesImplTest {

  private final IssueFiler filer = mock(IssueFiler.class);
  private final LinearActivitiesImpl activities = new LinearActivitiesImpl(filer);

  private static IssueFiling filing() {
    return new IssueFiling("deploy", List.of("recreate", "backend"), "t", "b", "d", "run-1", "w");
  }

  @Test
  void returnsWhatTheFilerDecided() {
    when(filer.file(any(IssueFiling.class)))
        .thenReturn(new FiledIssue(FilingDecision.FILED_NEW, "SIM-9", "url", "fp"));

    assertThat(activities.fileIssue(filing()).issueIdentifier()).isEqualTo("SIM-9");
  }

  @Test
  void mapsNonRetryableFaultsToNonRetryableApplicationFailures() {
    // Temporal retries every exception by default. A revoked or read-only API key would then
    // burn the whole retry budget on a fault that cannot resolve itself.
    when(filer.file(any(IssueFiling.class)))
        .thenThrow(new LinearApiException("Linear rejected the API key with 401", false));

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
  }

  @Test
  void retryableFaultsPropagateSoTemporalCanRetryThem() {
    when(filer.file(any(IssueFiling.class)))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(LinearApiException.class);
  }
}
