package com.simonrowe.factory.platformbackup.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflow;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleInfo;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.ScheduleActionExecutionStartWorkflow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Collision detection for manual captures. Retention keeps only the newest seven archives in the
 * Drive folder, so a duplicate capture does not merely waste time — it shortens the recovery
 * window by one night.
 */
class PlatformBackupWorkflowServiceTest {

  private final WorkflowClient workflows = mock(WorkflowClient.class);

  @Test
  void refusesWhileScheduledCaptureIsRunning() {
    assertThatThrownBy(() -> service(schedules(true)).start(false))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    verify(workflows, never()).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  @Test
  void treatsAnAbsentScheduleAsNoCollision() {
    // This service runs in software-factory while the schedule is created by whichever container
    // has platform backup enabled. On a cold stack `describe` throws for a schedule that does not
    // exist yet, and calling that a collision would refuse every capture with a conflict no
    // operator could clear.
    PlatformBackupWorkflowService service = service(noSchedule());

    assertThatCode(() -> attemptStart(service)).doesNotThrowAnyException();

    verify(workflows).newWorkflowStub(any(Class.class), any(WorkflowOptions.class));
  }

  /**
   * Starting is a Temporal static call this test cannot intercept, so it asserts only that the
   * collision check let the request through to stub creation; the accepted response shape is
   * covered by {@code PlatformBackupControllerTest}.
   */
  private static void attemptStart(final PlatformBackupWorkflowService service) {
    try {
      service.start(true);
    } catch (RuntimeException exception) {
      if (exception instanceof ResponseStatusException) {
        throw exception;
      }
      // Any other fault is the un-mockable Temporal start call, not the guard under test.
    }
  }

  private PlatformBackupWorkflowService service(final ScheduleClient schedules) {
    when(workflows.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
        .thenReturn(mock(PlatformBackupWorkflow.class));
    return new PlatformBackupWorkflowService(workflows, schedules);
  }

  private static ScheduleClient schedules(final boolean running) {
    ScheduleClient client = mock(ScheduleClient.class);
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle(any())).thenReturn(handle);
    when(handle.describe()).thenReturn(description(running));
    return client;
  }

  private static ScheduleClient noSchedule() {
    ScheduleClient client = mock(ScheduleClient.class);
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle(any())).thenReturn(handle);
    when(handle.describe()).thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
    return client;
  }

  private static ScheduleDescription description(final boolean running) {
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("PlatformBackupWorkflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("platform-backup")
                            .setTaskQueue("platform-backup")
                            .build())
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(List.of(new ScheduleIntervalSpec(Duration.ofHours(24))))
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(false).build())
            .build();
    return new ScheduleDescription(
        "platform-backup-nightly",
        new ScheduleInfo(
            0L,
            0L,
            0L,
            running
                ? List.of(new ScheduleActionExecutionStartWorkflow("platform-backup", "run-1"))
                : List.of(),
            List.of(),
            List.of(),
            null,
            null),
        schedule,
        Map.of(),
        null,
        Map.of(),
        null);
  }
}
