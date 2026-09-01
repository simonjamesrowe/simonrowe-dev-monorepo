package com.simonrowe.factory.deploy.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.github.DeployReportGateway;
import com.simonrowe.factory.deploy.github.DeployReportRenderer;
import com.simonrowe.factory.deploy.persistence.DeployRunRepository;
import com.simonrowe.factory.deploy.shell.PhaseRunner;
import com.simonrowe.factory.deploy.agent.TriageEngine;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.LogWatchResult;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The post-deploy scan trigger, against a real Temporal client rather than a mocked one.
 *
 * <p>Separate from {@code DeployActivitiesImplTest} because this one needs a running test
 * environment: what is worth asserting is the request the scan is actually started with, and a
 * mocked {@link WorkflowClient} would only prove that a stub was called.
 */
class ScheduleLogWatchScanTest {

  private static final Instant WINDOW_START = Instant.parse("2026-09-01T12:00:00Z");
  private static final String DEPLOY_RUN_ID = "deploy-run-1";

  @TempDir private Path stateDir;

  private TestWorkflowEnvironment environment;
  private DeployActivitiesImpl activities;

  /** Captures what the scan workflow was actually started with. */
  private final AtomicReference<LogWatchRequest> started = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(LogWatchTaskQueues.LOG_WATCH);
    worker.registerWorkflowImplementationTypes(CapturingLogWatchWorkflow.class);
    CapturingLogWatchWorkflow.CAPTURED = started;
    environment.start();

    DeployProperties properties =
        new DeployProperties(
            true, false, null, null, null, null, null, null, null, null, null, null, null, null,
            stateDir.toString(), Duration.ofMinutes(30), null, true);
    activities =
        new DeployActivitiesImpl(
            properties,
            mock(PhaseRunner.class),
            mock(DeployRunRepository.class),
            mock(TriageEngine.class),
            mock(DeployReportGateway.class),
            new DeployReportRenderer(),
            environment.getWorkflowClient());
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  @DisplayName("the scan is started on the logwatch queue with the deploy's window and flags")
  void startsTheScanWithTheDeployWindow() {
    String workflowId = activities.scheduleLogWatchScan(WINDOW_START, DEPLOY_RUN_ID, true);

    assertThat(workflowId).isEqualTo("logwatch-postdeploy-" + DEPLOY_RUN_ID);
    environment.sleep(Duration.ofMinutes(6));

    LogWatchRequest request = started.get();
    assertThat(request).isNotNull();
    assertThat(request.windowStart()).isEqualTo(WINDOW_START);
    // Null end on purpose: the scan resolves it from its own clock when it actually runs, so the
    // window reaches the moment of scanning rather than stopping when it was scheduled.
    assertThat(request.windowEnd()).isNull();
    assertThat(request.trigger()).isEqualTo(Trigger.DEPLOY);
    assertThat(request.dryRun()).isFalse();
    assertThat(request.linearFilingEnabled()).isTrue();
  }

  @Test
  @DisplayName("the Linear flag is whatever the deploy passed, not what the deployer knows")
  void passesTheLinearFlagThrough() {
    activities.scheduleLogWatchScan(WINDOW_START, DEPLOY_RUN_ID, false);
    environment.sleep(Duration.ofMinutes(6));

    // The deployer holds no FACTORY_LINEAR_ENABLED by design, so reading it locally would resolve
    // false and every post-deploy scan would run and file nothing, silently.
    assertThat(started.get().linearFilingEnabled()).isFalse();
  }

  @Test
  @DisplayName("a retry that already succeeded cannot schedule a second scan for the same deploy")
  void isIdempotentPerDeployRun() {
    activities.scheduleLogWatchScan(WINDOW_START, DEPLOY_RUN_ID, true);

    // REJECT_DUPLICATE on an id keyed to the deploy run. Activities are retried; without this a
    // retry after a successful start would queue a second scan of the same window.
    assertThatThrownBy(() -> activities.scheduleLogWatchScan(WINDOW_START, DEPLOY_RUN_ID, true))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("a different deploy gets its own scan")
  void schedulesSeparatelyForEachDeploy() {
    assertThat(activities.scheduleLogWatchScan(WINDOW_START, "deploy-a", true))
        .isNotEqualTo(activities.scheduleLogWatchScan(WINDOW_START, "deploy-b", true));
  }

  /** Records the request it was started with, so the test can assert on it. */
  public static class CapturingLogWatchWorkflow implements LogWatchWorkflow {

    private static AtomicReference<LogWatchRequest> CAPTURED;

    @Override
    public LogWatchResult run(final LogWatchRequest request) {
      CAPTURED.set(request);
      return null;
    }

    @Override
    public LogWatchProgress progress() {
      return LogWatchProgress.accepted();
    }
  }
}
