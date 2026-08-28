package com.simonrowe.factory.linear.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.service.IssueFiler;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearActivitiesImplTest {

  private static final String PR_URL = "https://github.com/o/r/pull/7";
  private static final String TITLE = "Guidance pull request";

  private final IssueFiler filer = mock(IssueFiler.class);
  private final LinearGateway gateway = mock(LinearGateway.class);
  private final LinearActivitiesImpl activities = new LinearActivitiesImpl(filer, gateway);

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
    LinearApiException fault =
        new LinearApiException("Linear rejected the API key with 401", false);
    when(filer.file(any(IssueFiling.class))).thenThrow(fault);

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            e -> {
              ApplicationFailure failure = (ApplicationFailure) e;
              assertThat(failure.isNonRetryable()).isTrue();
              // SCREAMING_SNAKE, matching every other failure type in this codebase
              // (STALE_PULL_REQUEST, MISSING_GITHUB_CREDENTIALS, CVE_FIX_PR_NOT_OPENED).
              assertThat(failure.getType()).isEqualTo("LINEAR_API_ERROR");
              assertThat(failure.getDetails().get(String.class)).isEqualTo("deploy");
              // The cause must not be dropped: it is populated for the most opaque faults
              // (an unparseable Linear response), and a triager needs to see it.
              assertThat(failure.getCause()).isSameAs(fault);
            });
  }

  @Test
  void retryableFaultsPropagateSoTemporalCanRetryThem() {
    when(filer.file(any(IssueFiling.class)))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    assertThatThrownBy(() -> activities.fileIssue(filing()))
        .isInstanceOf(LinearApiException.class);
  }

  @Test
  void attachesRelatedUrlThatIsNotYetAttached() {
    when(gateway.issuesForFingerprint(PR_URL)).thenReturn(List.of());

    activities.attachUrl("i9", PR_URL, TITLE);

    verify(gateway).attachUrl("i9", PR_URL, TITLE);
  }

  @Test
  void skipsRelatedUrlThatIsAlreadyAttachedToThatIssue() {
    // The activity is retried, and Linear happily creates a second identical attachment. The
    // check makes the repair idempotent, which is the whole point of running it on retry.
    when(gateway.issuesForFingerprint(PR_URL))
        .thenReturn(List.of(
            new TrackedIssue("i9", "SIM-9", "url", IssueStateType.STARTED, Instant.EPOCH)));

    activities.attachUrl("i9", PR_URL, TITLE);

    verify(gateway, never()).attachUrl("i9", PR_URL, TITLE);
  }

  @Test
  void stillAttachesWhenTheSameUrlSitsOnAnotherIssue() {
    // The same pull request can legitimately be attached to two issues; only this issue's
    // existing attachment means the work is already done.
    when(gateway.issuesForFingerprint(PR_URL))
        .thenReturn(List.of(
            new TrackedIssue("other", "SIM-1", "url", IssueStateType.STARTED, Instant.EPOCH)));

    activities.attachUrl("i9", PR_URL, TITLE);

    verify(gateway).attachUrl("i9", PR_URL, TITLE);
  }

  @Test
  void mapsNonRetryableAttachFaultsToNonRetryableApplicationFailures() {
    when(gateway.issuesForFingerprint(PR_URL))
        .thenThrow(new LinearApiException("Linear rejected the API key with 401", false));

    assertThatThrownBy(() -> activities.attachUrl("i9", PR_URL, TITLE))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
  }

  @Test
  void retryableAttachFaultsPropagateSoTemporalCanRetryThem() {
    when(gateway.issuesForFingerprint(PR_URL))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    assertThatThrownBy(() -> activities.attachUrl("i9", PR_URL, TITLE))
        .isInstanceOf(LinearApiException.class);
  }
}
