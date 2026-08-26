package com.simonrowe.factory.deploy.workflow;

import com.simonrowe.factory.deploy.agent.TriageEngine;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.github.DeployReportGateway;
import com.simonrowe.factory.deploy.github.DeployReportRenderer;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.deploy.persistence.DeployRunRepository;
import com.simonrowe.factory.deploy.shell.PhaseRunner;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The deployer's half of the feature.
 *
 * <h2>Why the {@code @ConditionalOnProperty} is load-bearing</h2>
 *
 * <p>This bean's existence is the <strong>only</strong> thing that keeps the Docker socket out of
 * the JVM that terminates untrusted internet traffic.
 *
 * <p>{@code software-factory} and {@code deployer} run the same image. Temporal's Spring Boot
 * auto-discovery scans {@code workers-auto-discovery.workflow-packages} for {@code @WorkflowImpl}
 * classes and creates a worker for every task queue it finds — unconditionally, because those
 * classes are not Spring beans and no condition can gate them. So both containers poll the
 * {@code deploy} queue for <em>workflow</em> tasks, which is harmless: a workflow implementation
 * only schedules activities.
 *
 * <p>Activity implementations are different. They are discovered as Spring beans, so this
 * condition genuinely removes the bean — and with it every implementation of every side-effecting
 * step. {@code factory.deploy.enabled} is true only on {@code deployer}, which is the container
 * with no ingress, no published port and no webhook secret.
 *
 * <p>Delete the condition and the failure is not a clean error: whichever JVM happens to win an
 * activity task runs it, so a deploy fails intermittently on a missing docker binary. {@code
 * DeployWorkerRegistrationTest} exists to stop that.
 */
@Component
@ActivityImpl(taskQueues = DeployTaskQueues.DEPLOY)
@ConditionalOnProperty(name = "factory.deploy.enabled", havingValue = "true")
public class DeployActivitiesImpl implements DeployActivities {

  private static final Logger LOG = LoggerFactory.getLogger(DeployActivitiesImpl.class);

  /** Bytes of any single evidence file. A crash-looping container must not fill the volume. */
  private static final int MAX_EVIDENCE_BYTES = 256 * 1024;

  private final DeployProperties properties;
  private final PhaseRunner phaseRunner;
  private final DeployRunRepository runs;
  private final TriageEngine triageEngine;
  private final DeployReportGateway reportGateway;
  private final DeployReportRenderer renderer;

  public DeployActivitiesImpl(
      final DeployProperties properties,
      final PhaseRunner phaseRunner,
      final DeployRunRepository runs,
      final TriageEngine triageEngine,
      final DeployReportGateway reportGateway,
      final DeployReportRenderer renderer) {
    this.properties = properties;
    this.phaseRunner = phaseRunner;
    this.runs = runs;
    this.triageEngine = triageEngine;
    this.reportGateway = reportGateway;
    this.renderer = renderer;
  }

  @Override
  public PhaseOutcome runPhase(
      final DeployPhase phase, final String imageTag, final boolean dryRun) {
    PhaseRunner.PhaseExecution execution =
        phaseRunner.run(phase, null, imageTag, dryRun, heartbeat());
    // Returns the outcome rather than throwing on a non-zero exit, deliberately. A phase that
    // legitimately fails is the signal to roll back, not an activity error — throwing here would
    // put it through the activity retry policy and turn one failed verification into three,
    // tripling the outage before the rollback even starts.
    return new PhaseOutcome(
        phase,
        execution.succeeded(),
        execution.exitCode(),
        execution.output(),
        execution.durationMillis());
  }

  @Override
  public SyncOutcome syncConfig(final String targetSha, final boolean dryRun) {
    PhaseRunner.PhaseExecution execution =
        phaseRunner.run(DeployPhase.SYNC_CONFIG, targetSha, null, dryRun, heartbeat());

    // previous-sha is emitted before anything moves, so it is available even on a decline.
    String previousSha = execution.value("previous-sha");
    SyncDecision decision = decisionFrom(execution);
    return new SyncOutcome(
        decision,
        // Only APPLIED gets a non-null previousSha, so "should the rollback restore the commit?"
        // is one null check rather than a second flag that could disagree with the decision.
        decision.movedHead() ? previousSha : null,
        targetSha,
        splitList(execution.value("affected")),
        splitList(execution.value("held-back")),
        execution.value("missing-variable"),
        execution.value("manual-command"),
        detailFor(decision, execution));
  }

  @Override
  public PhaseOutcome rollbackConfig(final String previousSha, final boolean dryRun) {
    PhaseRunner.PhaseExecution execution =
        phaseRunner.run(DeployPhase.ROLLBACK_CONFIG, previousSha, null, dryRun, heartbeat());
    return new PhaseOutcome(
        DeployPhase.ROLLBACK_CONFIG,
        execution.succeeded(),
        execution.exitCode(),
        execution.output(),
        execution.durationMillis());
  }

  @Override
  public String captureEvidence(
      final DeployPhase failedPhase,
      final String phaseOutput,
      final String previousSha,
      final String targetSha) {
    try {
      Path directory =
          Files.createTempDirectory(Path.of(properties.stateDir()), "deploy-evidence-");
      write(directory.resolve("phase-output.txt"), header(failedPhase) + orEmpty(phaseOutput));
      write(directory.resolve("compose-ps.txt"), capture("compose-ps"));
      write(directory.resolve("container-logs.txt"), capture("container-logs"));
      write(
          directory.resolve("commit-range.txt"),
          previousSha == null
              ? "The previously deployed commit is not known, so no commit range is available.\n"
              : capture("commit-range", previousSha, targetSha));
      return directory.toString();
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to capture deploy evidence", exception);
    }
  }

  @Override
  public Triage triage(final String evidenceDirectory) {
    try {
      return triageEngine.diagnose(Path.of(evidenceDirectory), heartbeat());
    } catch (RuntimeException exception) {
      // A failed diagnosis must not swallow the report: knowing the deploy failed and being told
      // the diagnosis is missing is far better than no issue at all.
      LOG.warn("Deploy triage agent failed", exception);
      return Triage.unavailable(exception.getMessage());
    }
  }

  @Override
  public Report report(
      final DeployRunRecord record, final Triage triage, final Long installationId) {
    String issueUrl = null;
    String commentUrl = null;
    try {
      issueUrl =
          reportGateway.openIssue(
              renderer.issueTitle(record, triage),
              renderer.issueBody(record, triage),
              List.of("deploy-failure"),
              installationId);
    } catch (RuntimeException exception) {
      LOG.warn("Could not open the deploy-failure issue", exception);
    }
    try {
      commentUrl =
          reportGateway.commentOnCommit(
              record.sha(), renderer.commitComment(record, triage, issueUrl), installationId);
    } catch (RuntimeException exception) {
      LOG.warn("Could not comment on the deployed commit", exception);
    }
    return new Report(issueUrl, commentUrl);
  }

  @Override
  public void recordRun(final DeployRunRecord record) {
    runs.save(record);
  }

  @Override
  public void discardEvidence(final String evidenceDirectory) {
    if (evidenceDirectory == null || evidenceDirectory.isBlank()) {
      return;
    }
    Path directory = Path.of(evidenceDirectory);
    // Refuse anything outside the state directory. The only caller passes a path this class
    // created, but this method takes a string across an activity boundary and deletes a tree, so
    // the guard is worth the three lines.
    if (!directory.toAbsolutePath().startsWith(Path.of(properties.stateDir()).toAbsolutePath())) {
      LOG.warn("Refusing to delete {} - it is outside the state directory", directory);
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException exception) {
                  LOG.warn("Could not delete {}", path, exception);
                }
              });
    } catch (IOException exception) {
      LOG.warn("Could not clean up {}", directory, exception);
    }
  }

  /**
   * Runs one of the script's read-only evidence-gathering arguments.
   *
   * <p>Best-effort by definition: this runs because something is already broken, so a file that
   * cannot be captured must never take the whole report down with it.
   */
  private String capture(final String what, final String... arguments) {
    try {
      return phaseRunner.capture(what, heartbeat(), arguments).output();
    } catch (RuntimeException exception) {
      return "Could not capture " + what + ": " + exception.getMessage() + "\n";
    }
  }

  private static SyncDecision decisionFrom(final PhaseRunner.PhaseExecution execution) {
    String decision = execution.value("decision");
    if (decision == null) {
      return execution.succeeded() ? SyncDecision.APPLIED : SyncDecision.FAILED;
    }
    return switch (decision) {
      case "applied" -> SyncDecision.APPLIED;
      case "already-current" -> SyncDecision.ALREADY_CURRENT;
      case "dirty-tree" -> SyncDecision.DIRTY_TREE;
      case "not-an-ancestor" -> SyncDecision.NOT_AN_ANCESTOR;
      case "held-back" -> SyncDecision.HELD_BACK;
      case "missing-variable" -> SyncDecision.MISSING_VARIABLE;
      case "disabled" -> SyncDecision.DISABLED;
      default -> SyncDecision.FAILED;
    };
  }

  private static String detailFor(
      final SyncDecision decision, final PhaseRunner.PhaseExecution execution) {
    return switch (decision) {
      case APPLIED -> "fast-forwarded the deploy directory to the deployed commit";
      case ALREADY_CURRENT -> "the deploy directory was already at the deployed commit";
      case DIRTY_TREE ->
          "a tracked file in the deploy directory is modified, so nothing was moved; "
              + "deploying images only";
      case NOT_AN_ANCESTOR ->
          "the target commit is not on origin/main, so nothing was moved";
      case HELD_BACK ->
          "a configuration change affects a service outside the recreate allowlist, so nothing "
              + "was moved; deploying images only";
      case MISSING_VARIABLE ->
          "the new compose file needs an environment variable the host does not define, so "
              + "nothing was moved; deploying images only";
      case DISABLED -> "configuration sync is disabled; images only";
      case FAILED -> lastLine(execution.output());
    };
  }

  private static List<String> splitList(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : value.split("[,\\s]+")) {
      if (!item.isBlank()) {
        items.add(item);
      }
    }
    return items;
  }

  private static String lastLine(final String output) {
    if (output == null || output.isBlank()) {
      return "configuration sync failed with no output";
    }
    String[] lines = output.strip().split("\n");
    return lines[lines.length - 1].strip();
  }

  private static String header(final DeployPhase failedPhase) {
    return "Failed phase: " + failedPhase.argument() + "\n\n";
  }

  private static String orEmpty(final String value) {
    return value == null ? "" : value;
  }

  private static void write(final Path path, final String content) throws IOException {
    String bounded =
        content.length() <= MAX_EVIDENCE_BYTES
            ? content
            // The tail: the reason something failed is at the end of its output.
            : "[truncated to the last "
                + MAX_EVIDENCE_BYTES
                + " characters]\n"
                + content.substring(content.length() - MAX_EVIDENCE_BYTES);
    Files.writeString(path, bounded, StandardCharsets.UTF_8);
  }

  /**
   * Heartbeats to Temporal so a long phase does not trip the activity's heartbeat timeout.
   *
   * <p>Tolerates being called outside an activity context, so the same helper works in a unit
   * test.
   */
  private static Consumer<String> heartbeat() {
    return message -> {
      try {
        Activity.getExecutionContext().heartbeat(message);
      } catch (IllegalStateException exception) {
        LOG.debug("Not in an activity context; skipping heartbeat");
      }
    };
  }
}
