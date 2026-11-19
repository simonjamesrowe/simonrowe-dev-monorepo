package com.simonrowe.factory.deploy.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deploy in flight when the deployer is recreated onto this build must still replay.
 *
 * <p>Both fixtures are real histories, recorded by running the previous build's {@code
 * DeployWorkflowImpl} (origin/main at 2426faac) in Temporal's test environment. That build had no
 * {@code plan} activity, and this one schedules one before {@code maintenance-on}; without the
 * {@link DeployWorkflowImpl#DEPLOY_PLAN_CHANGE} version gate, replaying either history fails with
 * a non-determinism error.
 *
 * <ul>
 *   <li>{@code deploy-previous-build.json}: a successful deploy.
 *   <li>{@code deploy-rolled-back-previous-build.json}: {@code verify} failed once, so it also
 *       covers the rollback path's maintenance phases.
 * </ul>
 */
class DeployWorkflowReplayTest {

  private static final String DEPLOYED =
      "com/simonrowe/factory/deploy/workflow/deploy-previous-build.json";
  private static final String ROLLED_BACK =
      "com/simonrowe/factory/deploy/workflow/deploy-rolled-back-previous-build.json";

  @Test
  @DisplayName("a deploy recorded by the previous build replays without a plan")
  void replaysSuccessfulDeployOfThePreviousBuild() {
    assertThatCode(
            () -> WorkflowReplayer.replayWorkflowExecutionFromResource(
                DEPLOYED, DeployWorkflowImpl.class))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a rollback recorded by the previous build replays without a plan")
  void replaysRolledBackDeployOfThePreviousBuild() {
    assertThatCode(
            () -> WorkflowReplayer.replayWorkflowExecutionFromResource(
                ROLLED_BACK, DeployWorkflowImpl.class))
        .doesNotThrowAnyException();
  }

  /** A fixture that already held a plan, or never ran the phases, would prove nothing. */
  @Test
  @DisplayName("the fixtures are pre-plan histories that ran the shell phases")
  void fixturesHaveTheShapeTheReplaysDependOn() throws IOException {
    assertThat(activityTypes(DEPLOYED))
        .containsExactly(
            "SyncConfig", "RunPhase", "RunPhase", "RunPhase", "RunPhase", "RunPhase",
            "RunPhase", "RecordRun");
    assertThat(activityTypes(ROLLED_BACK)).contains("RollbackConfig").doesNotContain("Plan");
  }

  private static List<String> activityTypes(final String resource) throws IOException {
    try (InputStream stream =
        DeployWorkflowReplayTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      WorkflowExecutionHistory history =
          WorkflowExecutionHistory.fromJson(
              new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return history.getEvents().stream()
          .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
          .map(event -> event.getActivityTaskScheduledEventAttributes().getActivityType().getName())
          .toList();
    }
  }
}
