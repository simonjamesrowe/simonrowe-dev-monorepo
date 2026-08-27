package com.simonrowe.factory.platformbackup.workflow;

import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.platformbackup.config.PlatformBackupProperties;
import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs {@code scripts/backup-platform.sh}. This is the whole of the Java side's relationship with
 * the backup: it never invokes {@code docker}, {@code pg_dump} or {@code clickhouse-client}
 * itself. The script is the single capture mechanism, shared with the operator who runs it by
 * hand, so there is exactly one place where the backup behaviour lives — the same arrangement
 * {@code PhaseRunner} has with {@code restart-prod.sh}.
 *
 * <h2>Why the {@code @ConditionalOnProperty} is load-bearing</h2>
 *
 * <p>Same reason as {@code DeployActivitiesImpl}, and worth restating rather than cross-referencing
 * because deleting it fails in a confusing way rather than an obvious one.
 *
 * <p>{@code software-factory} and {@code deployer} run the same image. Temporal's Spring Boot
 * auto-discovery creates a worker for every {@code @WorkflowImpl} task queue it finds,
 * unconditionally — so both containers poll this queue for <em>workflow</em> tasks. That is
 * harmless: a workflow implementation only schedules activities.
 *
 * <p>Activity implementations are Spring beans, so this condition genuinely removes the bean.
 * {@code factory.platform-backup.enabled} is true only on {@code deployer}, the container with the
 * Docker socket and no ingress. Without the condition, whichever JVM won the activity task would
 * run it, and the capture would fail intermittently on a missing docker binary inside
 * {@code software-factory}.
 */
@Component
@ActivityImpl(taskQueues = PlatformBackupTaskQueues.PLATFORM_BACKUP)
@ConditionalOnProperty(name = "factory.platform-backup.enabled", havingValue = "true")
public class PlatformBackupActivitiesImpl implements PlatformBackupActivities {

  private static final Logger LOG =
      LoggerFactory.getLogger(PlatformBackupActivitiesImpl.class);

  /** Tail of the script output kept as the activity result, so the UI shows the outcome. */
  private static final int RESULT_TAIL_CHARACTERS = 4000;

  private final ProcessRunner processRunner;
  private final PlatformBackupProperties properties;

  public PlatformBackupActivitiesImpl(
      final ProcessRunner processRunner, final PlatformBackupProperties properties) {
    this.processRunner = processRunner;
    this.properties = properties;
  }

  @Override
  public String capture(final boolean dryRun) {
    List<String> command = command(dryRun);
    LOG.info("Starting platform backup: {}", String.join(" ", command));

    ProcessRunner.ProcessResult result =
        processRunner.run(
            command,
            Path.of(properties.workingDirectory()),
            null,
            Map.of(),
            // Nothing stripped: this child process is the backup itself and legitimately needs the
            // environment it was given, including the Docker socket path. The allowlist that
            // ClaudeCliRunner applies exists because the agent reads attacker-authored branches;
            // this script reads only the datastores and .env.
            Set.of(),
            properties.timeout(),
            heartbeat());

    // The script narrates on stderr and is otherwise quiet, so both streams are kept.
    String output = result.standardOutput() + result.standardError();

    if (result.exitCode() != 0) {
      // Fail the activity rather than returning the output. A non-zero exit means no archive was
      // uploaded and nothing was pruned; swallowing it here would report a successful backup on a
      // night that produced none - the exact failure this whole feature exists to prevent.
      throw Activity.wrap(
          new IllegalStateException(
              "backup-platform.sh exited with " + result.exitCode() + ": " + tail(output)));
    }

    LOG.info("Platform backup completed");
    return tail(output);
  }

  private List<String> command(final boolean dryRun) {
    List<String> command = new java.util.ArrayList<>(List.of("bash", properties.script()));
    if (dryRun) {
      command.add("--dry-run");
    }
    return command;
  }

  /**
   * Forwards script output as heartbeat details.
   *
   * <p>Without this a capture that legitimately takes hours would be killed on the heartbeat
   * timeout. It also makes a wedged run distinguishable from a slow one: the Temporal UI shows the
   * last line the script printed.
   */
  private Consumer<String> heartbeat() {
    ActivityExecutionContext context = Activity.getExecutionContext();
    return line -> {
      try {
        context.heartbeat(line);
      } catch (RuntimeException cancellation) {
        // A cancellation request arrives as an exception from heartbeat(). Let the process finish
        // its current write rather than tearing it down mid-archive; the timeout is the backstop.
        LOG.warn("Heartbeat rejected, backup may have been cancelled: {}",
            cancellation.getMessage());
      }
    };
  }

  private static String tail(final String output) {
    if (output.length() <= RESULT_TAIL_CHARACTERS) {
      return output;
    }
    return output.substring(output.length() - RESULT_TAIL_CHARACTERS);
  }
}
