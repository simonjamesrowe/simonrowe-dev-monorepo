package com.simonrowe.factory.admin;

import static com.simonrowe.factory.admin.ModulePrerequisites.CODE_REVIEW;
import static com.simonrowe.factory.admin.ModulePrerequisites.CVEFIX;
import static com.simonrowe.factory.admin.ModulePrerequisites.DEPLOY;
import static com.simonrowe.factory.admin.ModulePrerequisites.FEEDBACK;
import static com.simonrowe.factory.admin.ModulePrerequisites.LINEAR;
import static com.simonrowe.factory.admin.ModulePrerequisites.PLATFORM_BACKUP;

import com.simonrowe.factory.admin.FactoryStatusResponse.ModuleStatus;
import com.simonrowe.factory.admin.FactoryStatusResponse.ScheduleStatus;
import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.schedule.CveFixScheduleInitializer;
import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import com.simonrowe.factory.platformbackup.schedule.PlatformBackupScheduleInitializer;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleInfo;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads effective module flags and the Temporal facts that prove work can execute.
 *
 * <p>Configuration, a live worker poller and a schedule's pause state are three independent
 * statuses, and a module needs all three to do anything. Reporting only the flag is what made a
 * healthy-looking container with no poller so hard to diagnose in the past, so all three are
 * reported separately and never collapsed into a single boolean by this class.
 */
@Service
public class FactoryStatusService {

  /** The linear queue is activity-only by design; asserting a workflow poller on it is wrong. */
  private static final boolean ACTIVITY_ONLY = false;

  private final WorkflowClient workflowClient;
  private final ScheduleClient scheduleClient;
  private final ModulePrerequisites prerequisites;
  private final String role;

  public FactoryStatusService(
      final WorkflowClient workflowClient,
      final ScheduleClient scheduleClient,
      final ModulePrerequisites prerequisites,
      @Value("${factory.runtime-role:software-factory}") final String role) {
    this.workflowClient = workflowClient;
    this.scheduleClient = scheduleClient;
    this.prerequisites = prerequisites;
    this.role = role;
  }

  /**
   * Describes every module from this container's point of view.
   *
   * @return the status of all six modules as this JVM sees them
   */
  public FactoryStatusResponse status() {
    return new FactoryStatusResponse(
        role,
        Instant.now(),
        List.of(
            module(CODE_REVIEW, "Code review", CodeReviewTaskQueues.REVIEWS,
                "pull_request webhook", null, true),
            module(FEEDBACK, "Feedback", FeedbackTaskQueues.REVIEW_FEEDBACK,
                "pull-request close and manual", null, true),
            module(CVEFIX, "Vulnerability scan", CveFixTaskQueues.CVE_FIX,
                "daily schedule and manual", CveFixScheduleInitializer.SCHEDULE_ID, true),
            module(DEPLOY, "Deploy", DeployTaskQueues.DEPLOY,
                "Publish webhook and guarded manual", null, true),
            module(LINEAR, "Linear filing", LinearTaskQueues.LINEAR,
                "upstream workflow", null, ACTIVITY_ONLY),
            module(PLATFORM_BACKUP, "Platform backup",
                PlatformBackupTaskQueues.PLATFORM_BACKUP, "nightly schedule and manual",
                PlatformBackupScheduleInitializer.SCHEDULE_ID, true)));
  }

  private ModuleStatus module(
      final String key,
      final String displayName,
      final String queue,
      final String trigger,
      final String scheduleId,
      final boolean needsWorkflowPoller) {
    boolean configured = prerequisites.configured(key);
    Integer workflowPollers = pollers(queue, TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW);
    Integer activityPollers = pollers(queue, TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);
    ScheduleStatus schedule = scheduleId == null ? null : schedule(scheduleId);
    List<String> missing = prerequisites.missingFor(key, configured);
    boolean pollersReady = activityPollers != null && activityPollers > 0
        && (!needsWorkflowPoller || workflowPollers != null && workflowPollers > 0);
    boolean ready = configured && pollersReady && missing.isEmpty();
    String diagnostic = null;
    if (!configured) {
      diagnostic = "Disabled by configuration";
    } else if (workflowPollers == null || activityPollers == null) {
      diagnostic = "Temporal task queue status is unavailable";
    } else if (!pollersReady) {
      diagnostic = "Required Temporal poller is missing";
    } else if (!missing.isEmpty()) {
      diagnostic = "Enabled but not usable: " + String.join("; ", missing);
    }
    return new ModuleStatus(
        key, displayName, configured, queue, workflowPollers, activityPollers, trigger,
        schedule, missing, ready, diagnostic);
  }

  /**
   * Returns null rather than zero when Temporal cannot be reached, because "no poller" and "we do
   * not know" lead an operator to different actions.
   */
  private Integer pollers(final String queue, final TaskQueueType type) {
    try {
      DescribeTaskQueueRequest request =
          DescribeTaskQueueRequest.newBuilder()
              .setNamespace(workflowClient.getOptions().getNamespace())
              .setTaskQueue(TaskQueue.newBuilder().setName(queue).build())
              .setTaskQueueType(type)
              .build();
      return workflowClient
          .getWorkflowServiceStubs()
          .blockingStub()
          .describeTaskQueue(request)
          .getPollersCount();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private ScheduleStatus schedule(final String scheduleId) {
    try {
      ScheduleDescription description = scheduleClient.getHandle(scheduleId).describe();
      ScheduleInfo info = description.getInfo();
      Instant previous = info.getRecentActions().isEmpty()
          ? null
          : info.getRecentActions().get(0).getStartedAt();
      Instant next = info.getNextActionTimes().isEmpty() ? null : info.getNextActionTimes().get(0);
      return new ScheduleStatus(
          scheduleId,
          true,
          description.getSchedule().getState().isPaused(),
          description.getSchedule().getPolicy().getOverlap().name(),
          previous,
          next,
          info.getRunningActions().size(),
          null);
    } catch (RuntimeException exception) {
      return new ScheduleStatus(
          scheduleId, false, null, null, null, null, 0, "Schedule is absent or unavailable");
    }
  }
}
