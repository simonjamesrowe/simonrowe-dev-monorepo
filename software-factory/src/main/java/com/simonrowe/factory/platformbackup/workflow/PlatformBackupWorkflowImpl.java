package com.simonrowe.factory.platformbackup.workflow;

import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * One activity and a retry policy. Everything the backup actually does lives in
 * {@code scripts/backup-platform.sh}; this exists for durability and scheduling.
 *
 * <p>The retry policy is the reason this is a workflow rather than a cron entry. The capture
 * uploads a potentially multi-GB archive over a residential uplink, so a transient network failure
 * is expected rather than exceptional — and a plain cron job that fails at 02:00 simply loses the
 * night. Three attempts with backoff turn most of those into a successful backup.
 *
 * <p>The script is idempotent by construction: it sweeps orphaned staging files on entry, builds
 * into a fresh temporary directory, and prunes only after a successful upload. So a retry after a
 * partial run is safe.
 */
@WorkflowImpl(taskQueues = PlatformBackupTaskQueues.PLATFORM_BACKUP)
public class PlatformBackupWorkflowImpl implements PlatformBackupWorkflow {

  /**
   * Generous, and deliberately so. The capture dumps four databases and a ClickHouse database of
   * unbounded size on a four-core Pi, then uploads the result. A timeout that fires on a slow but
   * healthy run is worse than no timeout: it looks identical to a real failure.
   */
  private static final Duration START_TO_CLOSE = Duration.ofHours(6);

  /**
   * Heartbeats keep a long capture alive. The activity forwards script output as heartbeat
   * details, so a wedged run is distinguishable from a slow one in the Temporal UI.
   */
  private static final Duration HEARTBEAT = Duration.ofMinutes(5);

  private final PlatformBackupActivities activities =
      Workflow.newActivityStub(
          PlatformBackupActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(START_TO_CLOSE)
              .setHeartbeatTimeout(HEARTBEAT)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofMinutes(5))
                      .setBackoffCoefficient(2.0)
                      .setMaximumAttempts(3)
                      .build())
              .build());

  @Override
  public String backup(final boolean dryRun) {
    return activities.capture(dryRun);
  }
}
