package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CveFixWorkflowTest {

  private static final String PURL = "pkg:maven/org.example/lib@1.0.0";
  private static final String NPM_PURL = "pkg:npm/widget@2.0.0";
  private static final List<ComponentFindings> COMPONENTS = List.of(
      new ComponentFindings(
          "simonrowe-dev/backend", PURL, "lib", "1.0.0",
          List.of("CVE-2026-1", "CVE-2026-2"),
          List.of(
              new Finding(
                  "simonrowe-dev/backend", PURL, "lib", "1.0.0", "CVE-2026-1", "HIGH", "Upgrade"),
              new Finding(
                  "simonrowe-dev/backend", PURL, "lib", "1.0.0", "CVE-2026-2", "MEDIUM",
                  "Upgrade"))),
      new ComponentFindings(
          "simonrowe-dev/frontend", NPM_PURL, "widget", "2.0.0",
          List.of("CVE-2026-3"),
          List.of(
              new Finding(
                  "simonrowe-dev/frontend", NPM_PURL, "widget", "2.0.0", "CVE-2026-3", "LOW",
                  ""))));

  @Test
  void filesOneConsolidatedRepositoryIssueContainingAllFindings() {
    CveFixActivities activities = mock(CveFixActivities.class);
    LinearActivities linear = mock(LinearActivities.class);
    when(activities.fetchFindings()).thenReturn(COMPONENTS);
    when(linear.fileIssue(any())).thenReturn(
        new FiledIssue(
            FilingDecision.FILED_NEW, "linear-id", "SIM-9", "https://linear/SIM-9", "fp"));

    CveFixResult result = run(activities, linear, true);

    assertThat(result.status()).isEqualTo(CveFixStatus.COMPLETED);
    assertThat(result.filed()).isEqualTo(1);
    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    assertThat(filing.getValue().keyParts())
        .containsExactly("simonjamesrowe/simonrowe-dev-monorepo", "current-vulnerabilities");
    assertThat(filing.getValue().body()).contains("CVE-2026-1", "CVE-2026-2", PURL);
    verify(activities).recordRun(any());
  }

  @Test
  void recordsNoFindingsWithoutCallingLinear() {
    CveFixActivities activities = mock(CveFixActivities.class);
    LinearActivities linear = mock(LinearActivities.class);
    when(activities.fetchFindings()).thenReturn(List.of());

    CveFixResult result = run(activities, linear, true);

    assertThat(result.status()).isEqualTo(CveFixStatus.NO_FINDINGS);
    verify(linear, never()).fileIssue(any());
    verify(activities).recordRun(any());
  }

  @Test
  void failsVisiblyWhenLinearFilingIsDisabled() {
    CveFixActivities activities = mock(CveFixActivities.class);
    LinearActivities linear = mock(LinearActivities.class);

    assertThatThrownBy(() -> run(activities, linear, false))
        .isInstanceOf(WorkflowFailedException.class);
    verify(activities, never()).fetchFindings();
  }

  private static CveFixResult run(
      final CveFixActivities activities,
      final LinearActivities linear,
      final boolean linearEnabled) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker workflowWorker = environment.newWorker(CveFixTaskQueues.CVE_FIX);
      workflowWorker.registerWorkflowImplementationTypes(CveFixWorkflowImpl.class);
      workflowWorker.registerActivitiesImplementations(new CveActivitiesAdapter(activities));
      Worker linearWorker = environment.newWorker(LinearTaskQueues.LINEAR);
      linearWorker.registerActivitiesImplementations(new LinearActivitiesAdapter(linear));
      environment.start();
      CveFixWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
          CveFixWorkflow.class,
          WorkflowOptions.newBuilder()
              .setTaskQueue(CveFixTaskQueues.CVE_FIX)
              .setWorkflowId("test-cve-" + linearEnabled)
              .build());
      return workflow.run(new CveFixRequest(false, null, 0, null, linearEnabled));
    }
  }

  private record CveActivitiesAdapter(CveFixActivities delegate) implements CveFixActivities {

    @Override
    public List<ComponentFindings> fetchFindings() {
      return delegate.fetchFindings();
    }

    @Override
    public void recordRun(
        final com.simonrowe.factory.cvefix.persistence.CveFixRunRecord record) {
      delegate.recordRun(record);
    }
  }

  private record LinearActivitiesAdapter(LinearActivities delegate) implements LinearActivities {

    @Override
    public FiledIssue fileIssue(final IssueFiling filing) {
      return delegate.fileIssue(filing);
    }

    @Override
    public void attachUrl(final String issueId, final String url, final String title) {
      delegate.attachUrl(issueId, url, title);
    }
  }
}
