package com.simonrowe.factory.platformbackup.api;

import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import com.simonrowe.factory.platformbackup.schedule.PlatformBackupScheduleInitializer;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupProgress;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.ScheduleClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Starts collision-checked manual platform captures and exposes their progress. */
@Service
public class PlatformBackupWorkflowService {

  static final String MANUAL_WORKFLOW_ID = "platform-backup-manual";

  private final WorkflowClient workflows;
  private final ScheduleClient schedules;

  public PlatformBackupWorkflowService(
      final WorkflowClient workflows, final ScheduleClient schedules) {
    this.workflows = workflows;
    this.schedules = schedules;
  }

  public PlatformBackupAccepted start(final boolean dryRun) {
    if (scheduledActionRunning()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A scheduled platform backup is already running");
    }
    PlatformBackupWorkflow workflow = workflows.newWorkflowStub(
        PlatformBackupWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(MANUAL_WORKFLOW_ID)
            .setTaskQueue(PlatformBackupTaskQueues.PLATFORM_BACKUP)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build());
    try {
      WorkflowExecution execution = WorkflowClient.start(workflow::backup, dryRun);
      return new PlatformBackupAccepted(
          execution.getWorkflowId(), execution.getRunId(),
          dryRun ? "Platform backup dry run accepted" : "Platform backup accepted");
    } catch (WorkflowExecutionAlreadyStarted exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A manual platform backup is already running");
    }
  }

  public PlatformBackupProgress progress() {
    return workflows.newWorkflowStub(
        PlatformBackupWorkflow.class, MANUAL_WORKFLOW_ID).progress();
  }

  /**
   * An absent schedule is not a collision.
   *
   * <p>This service runs in {@code software-factory} while the schedule is created by whichever
   * container has platform backup enabled, so on a cold stack — or before that container has
   * finished starting — {@code describe} throws for a schedule that simply does not exist yet.
   * Treating that as a running action would refuse every manual capture with a conflict that no
   * operator could clear.
   */
  private boolean scheduledActionRunning() {
    try {
      return !schedules.getHandle(PlatformBackupScheduleInitializer.SCHEDULE_ID)
          .describe().getInfo().getRunningActions().isEmpty();
    } catch (RuntimeException exception) {
      return false;
    }
  }
}
