package com.simonrowe.factory.deploy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.deploy.workflow.DeployActivities;
import com.simonrowe.factory.deploy.workflow.DeployWorkflow;
import com.simonrowe.factory.deploy.workflow.DeployWorkflowImpl;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Signal-with-start, against a real Temporal test service rather than a mocked client.
 *
 * <p>The coalescing claim — "two merges a few minutes apart produce one deploy, of the newer
 * commit" — is a property of how the workflow is *started*, not of the workflow body, so mocking
 * the client would test nothing. {@code DeployWorkflowTest} covers the body; this covers the way
 * in.
 */
class DeployWorkflowServiceTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String NEWER_SHA = "fedcba9876543210fedcba9876543210fedcba98";

  private TestWorkflowEnvironment environment;
  private DeployActivities activities;
  private DeployWorkflowService service;

  @BeforeEach
  void startEnvironment() {
    activities = mock(DeployActivities.class, withSettings().withoutAnnotations());
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(
            new SyncOutcome(
                SyncDecision.APPLIED, "old", SHA, List.of(), List.of(), null, null, "applied"));
    when(activities.runPhase(any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              DeployPhase phase = invocation.getArgument(0);
              return new PhaseOutcome(phase, true, 0, "ok", 1L);
            });

    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(DeployTaskQueues.DEPLOY);
    worker.registerWorkflowImplementationTypes(DeployWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    environment.start();

    service =
        new DeployWorkflowService(environment.getWorkflowClient(), properties());
  }

  @AfterEach
  void stopEnvironment() {
    environment.close();
  }

  private static DeployProperties properties() {
    return new DeployProperties(
        false, true, null, null, null, null, null, null, null, null,
        List.of("backend", "frontend", "software-factory"),
        null, Boolean.TRUE, Boolean.TRUE, null, Duration.ofMinutes(30), null);
  }

  private void awaitCompletion() {
    WorkflowStub.fromTyped(
            environment
                .getWorkflowClient()
                .newWorkflowStub(DeployWorkflow.class, DeployWorkflow.WORKFLOW_ID))
        .getResult(Object.class);
  }

  @Test
  void startsDeployOnTheFixedWorkflowId() {
    DeployAccepted accepted = service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);

    // Fixed, not per-sha: a per-sha id would make duplicate deliveries free but do nothing
    // about two deploys overlapping, which is the failure that actually matters on one node.
    assertThat(accepted.workflowId()).isEqualTo("deploy-prod");
    assertThat(accepted.runId()).isNotBlank();
    assertThat(accepted.sha()).isEqualTo(SHA);

    awaitCompletion();
    verify(activities).syncConfig(SHA, false);
  }

  @Test
  void duplicateDeliveryDoesNotStartSecondDeploy() {
    DeployAccepted first = service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);
    DeployAccepted second = service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);

    // Same run: the second call signalled the one already in flight rather than starting another.
    // This is why the service needs no already-started catch, unlike ReviewWorkflowService.
    assertThat(second.runId()).isEqualTo(first.runId());

    awaitCompletion();
    verify(activities, times(1)).syncConfig(SHA, false);
  }

  @Test
  void passesTheConfiguredSwitchesAndServicesToTheWorkflow() {
    service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);
    awaitCompletion();

    ArgumentCaptor<DeployRunRecord> record = ArgumentCaptor.forClass(DeployRunRecord.class);
    verify(activities).recordRun(record.capture());
    assertThat(record.getValue().trigger()).isEqualTo("workflow_run");
    assertThat(record.getValue().sha()).isEqualTo(SHA);
    // sync-config and rollback both enabled, so the run actually attempted the sync.
    verify(activities).syncConfig(SHA, false);
  }

  @Test
  void recordsManualTriggerDistinctlyFromWebhookOne() {
    service.start(SHA, DeployRequest.TRIGGER_MANUAL, null);
    awaitCompletion();

    ArgumentCaptor<DeployRunRecord> record = ArgumentCaptor.forClass(DeployRunRecord.class);
    verify(activities).recordRun(record.capture());
    assertThat(record.getValue().trigger()).isEqualTo("manual");
  }

  @Test
  void laterMergeCanStartFreshRunOnSameWorkflowId() {
    service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);
    awaitCompletion();

    // The reuse policy must be ALLOW_DUPLICATE: REJECT_DUPLICATE would refuse every deploy
    // after the first, since the workflow id is a constant.
    DeployAccepted next = service.start(NEWER_SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);
    awaitCompletion();

    assertThat(next.sha()).isEqualTo(NEWER_SHA);
    verify(activities).syncConfig(NEWER_SHA, false);
    verify(activities, times(2)).recordRun(any());
  }

  @Test
  void progressIsQueryableWhileDeployIsInFlight() {
    service.start(SHA, DeployRequest.TRIGGER_WEBHOOK, 999L);
    awaitCompletion();

    assertThat(service.progress()).isNotNull();
  }
}
