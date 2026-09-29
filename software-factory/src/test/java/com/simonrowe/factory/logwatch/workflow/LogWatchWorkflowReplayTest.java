package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A capped scan that was in flight when this build was deployed must still replay.
 *
 * <p>Both fixtures are real histories, recorded by running the <em>previous</em> build's
 * {@code LogWatchWorkflowImpl} in Temporal's test environment against a capped observation
 * ({@code signaturesDropped = 1}). That build never scheduled {@code SweepResolved} on a capped
 * run, while this one does whenever it can name what the cap dropped; replaying either history
 * onto code that issued the sweep would fail with a non-determinism error.
 *
 * <ul>
 *   <li>{@code capped-scan-previous-build.json} is exactly as recorded: the observation carries no
 *       {@code droppedKeyParts}, so the shortfall fallback alone would keep the old veto.
 *   <li>{@code capped-scan-mixed-builds.json} is the same history with {@code droppedKeyParts}
 *       added to the {@code Observe} result — what happens when the new activity worker ran
 *       {@code observe} but the previous build's workflow code consumed its result, as a
 *       {@code deployer} older than #193 still can. Only the
 *       {@link LogWatchWorkflowImpl#SWEEP_DESPITE_CAP_CHANGE} version gate keeps that one
 *       replaying.
 * </ul>
 *
 * <p>Mutation-checked when written: deleting the version gate fails the mixed-builds replay, and
 * deleting the size check as well fails both.
 */
class LogWatchWorkflowReplayTest {

  private static final String PREVIOUS_BUILD =
      "com/simonrowe/factory/logwatch/workflow/capped-scan-previous-build.json";
  private static final String MIXED_BUILDS =
      "com/simonrowe/factory/logwatch/workflow/capped-scan-mixed-builds.json";

  @Test
  @DisplayName("a capped run recorded by the previous build replays without a sweep")
  void replaysTheHistoryOfThePreviousBuild() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    PREVIOUS_BUILD, LogWatchWorkflowImpl.class))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a capped run whose named result the previous build consumed still replays")
  void replaysNamedObservationConsumedByThePreviousBuild() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    MIXED_BUILDS, LogWatchWorkflowImpl.class))
        .doesNotThrowAnyException();
  }

  /**
   * Guards the fixtures themselves: a history that never reached the capped branch, or one that
   * already contained a sweep, would replay cleanly and prove nothing.
   */
  @Test
  @DisplayName("the fixtures are capped runs with no sweep, one naming what it dropped")
  void fixturesHaveTheShapeTheReplaysDependOn() throws IOException {
    for (String resource : List.of(PREVIOUS_BUILD, MIXED_BUILDS)) {
      WorkflowExecutionHistory history = history(resource);
      assertThat(activityTypes(history)).containsExactly("Observe", "FileIssue", "RecordRun");
      assertThat(observation(history).signaturesDropped()).isEqualTo(1);
    }
    assertThat(observation(history(PREVIOUS_BUILD)).droppedKeyParts()).isEmpty();
    assertThat(observation(history(MIXED_BUILDS)).droppedKeyParts()).hasSize(1);
  }

  private static WorkflowExecutionHistory history(final String resource) throws IOException {
    try (InputStream in =
        LogWatchWorkflowReplayTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return WorkflowExecutionHistory.fromJson(
          new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static List<String> activityTypes(final WorkflowExecutionHistory history) {
    return history.getEvents().stream()
        .filter(event -> event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
        .map(event -> event.getActivityTaskScheduledEventAttributes().getActivityType().getName())
        .toList();
  }

  private static ScanObservation observation(final WorkflowExecutionHistory history) {
    HistoryEvent completed =
        history.getEvents().stream()
            .filter(
                event -> event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED)
            .findFirst()
            .orElseThrow();
    return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
        completed.getActivityTaskCompletedEventAttributes().getResult().getPayloads(0),
        ScanObservation.class,
        ScanObservation.class);
  }
}
