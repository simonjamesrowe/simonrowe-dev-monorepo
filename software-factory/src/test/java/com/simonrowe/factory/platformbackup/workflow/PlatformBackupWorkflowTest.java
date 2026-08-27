package com.simonrowe.factory.platformbackup.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The workflow is one activity and a retry policy, so the retry policy is what is worth testing:
 * it is the reason this is a durable workflow rather than a cron entry. A capture uploads a
 * potentially multi-GB archive over a residential uplink, where a transient failure is expected
 * rather than exceptional, and a plain cron job that fails at 02:00 simply loses the night.
 */
class PlatformBackupWorkflowTest {

  private TestWorkflowEnvironment environment;
  private Worker worker;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    worker = environment.newWorker(PlatformBackupTaskQueues.PLATFORM_BACKUP);
    worker.registerWorkflowImplementationTypes(PlatformBackupWorkflowImpl.class);
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  private PlatformBackupWorkflow start() {
    environment.start();
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            PlatformBackupWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(PlatformBackupTaskQueues.PLATFORM_BACKUP)
                .build());
  }

  @Test
  void returnsTheActivityOutput() {
    worker.registerActivitiesImplementations(
        (PlatformBackupActivities) dryRun -> "[backup-platform] Done.");

    assertThat(start().backup(false)).isEqualTo("[backup-platform] Done.");
  }

  @Test
  void passesDryRunThrough() {
    worker.registerActivitiesImplementations(
        (PlatformBackupActivities) dryRun -> dryRun ? "dry" : "real");

    assertThat(start().backup(true)).isEqualTo("dry");
  }

  /**
   * A transient failure must not lose the night. Two failures then a success should still produce
   * a backup, which is precisely what a cron job could not do.
   */
  @Test
  void retriesTransientFailures() {
    AtomicInteger attempts = new AtomicInteger();
    worker.registerActivitiesImplementations(
        (PlatformBackupActivities) dryRun -> {
          if (attempts.incrementAndGet() < 3) {
            throw new IllegalStateException("upload interrupted");
          }
          return "recovered on attempt " + attempts.get();
        });

    assertThat(start().backup(false)).isEqualTo("recovered on attempt 3");
    assertThat(attempts).hasValue(3);
  }

  /**
   * Retry is bounded. An endlessly retrying backup would hold the schedule's overlap slot and
   * silently prevent every subsequent night from running.
   */
  @Test
  void givesUpAfterThreeAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    worker.registerActivitiesImplementations(
        (PlatformBackupActivities) dryRun -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("pg_dump failed");
        });

    assertThatThrownBy(() -> start().backup(false)).isInstanceOf(WorkflowFailedException.class);
    assertThat(attempts).hasValue(3);
  }
}
