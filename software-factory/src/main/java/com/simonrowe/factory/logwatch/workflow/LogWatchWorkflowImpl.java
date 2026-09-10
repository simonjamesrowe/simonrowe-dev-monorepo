package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingMode;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.SweptIssue;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.LogWatchPhase;
import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.LogWatchResult;
import com.simonrowe.factory.logwatch.domain.LogWatchStatus;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One deterministic scan: check the source, read, group, file.
 *
 * <p>The ordering is load-bearing. Source health is established <strong>before</strong> anything
 * is interpreted, because a scan that cannot confirm it can see must not report zero findings as a
 * clean result. That is not a hypothetical: for three weeks in August 2026 Grafana Cloud accepted
 * no logs at all while every health signal stayed green, and a module without this check would
 * have filed nothing and been right by its own lights every night.
 */
@WorkflowImpl(taskQueues = LogWatchTaskQueues.LOG_WATCH)
public class LogWatchWorkflowImpl implements LogWatchWorkflow {

  private static final String PRODUCER = "logwatch";

  /** Key part marking the source-health finding, so it dedupes independently of any signature. */
  private static final String SOURCE_HEALTH_KEY = "source-health";

  private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

  /**
   * The shortest window a scan may draw a conclusion about <em>absence</em> from.
   *
   * <p>One hour, matching the floor the source-health check already applies to container
   * coverage, and for the same reason: over a short window an idle stack and a healthy one are
   * indistinguishable. The post-deploy trigger scans about five minutes, in which almost every
   * known problem is absent purely because five minutes is short — sweeping there would close
   * most of the backlog after every single deploy.
   *
   * <p>Structural rather than left to callers. The post-deploy path also passes
   * {@code resolveWhenClear = false} explicitly, but a future trigger added by someone who has
   * not read that comment gets the safe behaviour without having to ask for it.
   */
  private static final Duration MINIMUM_SWEEP_WINDOW = Duration.ofHours(1);

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final LogWatchActivities activities =
      Workflow.newActivityStub(
          LogWatchActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private final LinearActivities linear =
      Workflow.newActivityStub(
          LinearActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(LinearTaskQueues.LINEAR)
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setScheduleToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private LogWatchProgress current = LogWatchProgress.accepted();

  @Override
  public LogWatchResult run(final LogWatchRequest request) {
    String workflowId = Workflow.getInfo().getWorkflowId();
    String runId = Workflow.getInfo().getRunId();
    Instant startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());

    Instant to = request.windowEnd() == null ? startedAt : request.windowEnd();
    Instant from =
        request.windowStart() == null ? to.minus(DEFAULT_WINDOW) : request.windowStart();

    List<String> issueUrls = new ArrayList<>();
    List<String> resolvedUrls = new ArrayList<>();
    try {
      current =
          new LogWatchProgress(
              LogWatchPhase.CHECKING_SOURCE,
              "Checking the log source is alive over "
                  + LogWatchReportRenderer.describeWindow(from, to),
              null);

      ScanObservation observation = activities.observe(from, to);

      if (!observation.sourceHealth().usable()) {
        return handleUnusableSource(request, observation, workflowId, runId, startedAt, from, to,
            issueUrls, resolvedUrls);
      }

      current =
          new LogWatchProgress(
              LogWatchPhase.GROUPING,
              "Read " + observation.linesRead() + " line(s) from "
                  + observation.containersSeen() + " container(s)",
              observation.signatures().size());

      if (observation.signatures().isEmpty()) {
        // A clean scan is exactly when the sweep is most useful, so it runs on this path too and
        // not only after filing. This is the shape of the run that closes the last open ticket.
        String swept =
            sweepResolved(request, observation, from, to, runId, workflowId, resolvedUrls);
        String detail =
            "No signature met the minimum occurrence threshold. Source health: "
                + observation.sourceHealth().evidence()
                + swept;
        current = new LogWatchProgress(LogWatchPhase.DONE, detail, 0);
        return finish(request, observation, LogWatchStatus.NO_FINDINGS, workflowId, runId,
            startedAt, from, to, issueUrls, detail, resolvedUrls);
      }

      current =
          new LogWatchProgress(
              LogWatchPhase.FILING,
              request.dryRun()
                  ? "Dry run: reporting what would be filed, creating nothing"
                  : "Filing " + observation.signatures().size() + " problem(s) in Linear",
              observation.signatures().size());

      for (LogSignature signature : observation.signatures()) {
        fileSignature(request, signature, from, to, runId, workflowId, issueUrls);
      }

      // AFTER filing, never before. Every filing above advances that fingerprint's lastSeenAt,
      // and the sweep's entire input is which fingerprints are stale — running it first would
      // consider this scan's own findings absent and close the tickets it was about to update.
      String swept =
          sweepResolved(request, observation, from, to, runId, workflowId, resolvedUrls);
      String detail = describeOutcome(request, observation) + swept;
      current =
          new LogWatchProgress(LogWatchPhase.DONE, detail, observation.signatures().size());
      return finish(request, observation, LogWatchStatus.COMPLETED, workflowId, runId, startedAt,
          from, to, issueUrls, detail, resolvedUrls);

    } catch (RuntimeException exception) {
      String detail = safeMessage(exception);
      current = new LogWatchProgress(LogWatchPhase.DONE, detail, null);
      ScanObservation empty =
          new ScanObservation(
              new SourceHealth(
                  SourceHealth.Status.UNREACHABLE,
                  SourceHealth.Tier.CONTAINER_COVERAGE,
                  "The scan failed before reaching a verdict."),
              List.of(), 0, false, 0, 0);
      try {
        finish(request, empty, LogWatchStatus.FAILED, workflowId, runId, startedAt, from, to,
            issueUrls, detail, resolvedUrls);
      } catch (RuntimeException recordFailure) {
        Workflow.getLogger(LogWatchWorkflowImpl.class)
            .warn("Could not record failed log-watch scan", recordFailure);
      }
      throw exception;
    }
  }

  @Override
  public LogWatchProgress progress() {
    return current;
  }

  /**
   * Files the fact that the module cannot see, and stops.
   *
   * <p>It goes through the same sink with the same fingerprint shape as any other finding, so it
   * inherits dedup, cancel-to-suppress and reopen-to-re-arm for free. The alternative — a bespoke
   * alert path — would be a second way to file things, with its own suppression semantics to get
   * wrong, for a category of one.
   *
   * <p>The key parts carry the status but deliberately <strong>not</strong> the evidence string: a
   * {@code 429} whose byte counts differ on every scan must stay one recurring ticket, while a
   * quota problem and a rejected credential remain separate ones.
   */
  private LogWatchResult handleUnusableSource(
      final LogWatchRequest request,
      final ScanObservation observation,
      final String workflowId,
      final String runId,
      final Instant startedAt,
      final Instant from,
      final Instant to,
      final List<String> issueUrls,
      final List<String> resolvedUrls) {

    SourceHealth health = observation.sourceHealth();
    String detail = "Source is not usable (" + health.status() + "): " + health.evidence();
    current = new LogWatchProgress(LogWatchPhase.FILING, detail, 0);

    if (!request.dryRun() && request.linearFilingEnabled()) {
      FiledIssue filed =
          linear.fileIssue(
              new IssueFiling(
                  PRODUCER,
                  List.of(SOURCE_HEALTH_KEY, health.status().name()),
                  LogWatchReportRenderer.sourceHealthTitle(health),
                  LogWatchReportRenderer.sourceHealthBody(health, from, to),
                  "scan " + runId + ": " + health.evidence(),
                  runId,
                  workflowId,
                  FilingMode.REFRESH));
      if (filed.issueUrl() != null) {
        issueUrls.add(filed.issueUrl());
      }
    }

    // Deliberately NO sweep on this path, and this is the single most important line in the
    // feature. An unusable source produces zero signatures for the same reason a fixed
    // production does: nothing came back. Sweeping here would read "Grafana Cloud stopped
    // accepting logs" as "every problem is fixed" and close the entire backlog — including the
    // ticket this method just filed to say the module cannot see. Same reasoning as the module's
    // founding rule that an empty read is not a clean read.
    current = new LogWatchProgress(LogWatchPhase.DONE, detail, 0);
    return finish(request, observation, LogWatchStatus.SOURCE_UNHEALTHY, workflowId, runId,
        startedAt, from, to, issueUrls, detail, resolvedUrls);
  }

  private void fileSignature(
      final LogWatchRequest request,
      final LogSignature signature,
      final Instant from,
      final Instant to,
      final String runId,
      final String workflowId,
      final List<String> issueUrls) {

    if (request.dryRun() || !request.linearFilingEnabled()) {
      return;
    }
    FiledIssue filed =
        linear.fileIssue(
            new IssueFiling(
                PRODUCER,
                // The source key, never the generated title and — since 046 — never the whole
                // normalised line either. Both are phrasings of the problem, and a phrasing that
                // varies files a second ticket: three phrasings from one Embabel logger became
                // SIM-13, SIM-24 and SIM-25 for one startup failure. Severity is explicit here
                // rather than left implicit inside the message text.
                List.of(
                    signature.container(),
                    signature.severity().name(),
                    signature.sourceKey()),
                LogWatchReportRenderer.title(signature),
                LogWatchReportRenderer.body(signature, from, to),
                LogWatchReportRenderer.occurrenceDetail(signature, runId),
                runId,
                workflowId,
                FilingMode.REFRESH));
    if (filed.issueUrl() != null) {
      issueUrls.add(filed.issueUrl());
    }
  }

  /**
   * Closes the tickets this module filed for problems it no longer sees.
   *
   * <p>An automated factory that only ever opens tickets is half an automation: the backlog grows
   * monotonically and a human has to close each entry by hand once they notice the problem is
   * gone. This is the other half.
   *
   * <p><strong>Four conditions must hold, and each of them is a way this could close a ticket
   * about a problem that is still happening.</strong> They are checked here, in the producer,
   * rather than in the sink, because only the producer knows whether its own observation was
   * complete — the sink cannot tell a quiet stack from a blind one.
   *
   * <ol>
   *   <li><b>The source was healthy.</b> Enforced by where this is called from: never on the
   *       {@code SOURCE_UNHEALTHY} path. Without it, an ingest outage reads as universal success.
   *   <li><b>The read was not truncated.</b> A read that hit its line budget examined an unknown
   *       part of the window, so any signature it missed is missing for want of looking.
   *   <li><b>The window is long enough to mean anything.</b> See
   *       {@link #MINIMUM_SWEEP_WINDOW}.
   *   <li><b>Nothing was dropped by the per-run cap.</b> This is the subtle one. The cap
   *       (default five) limits how many signatures are <em>filed</em>, not how many were
   *       <em>seen</em> — so with six live problems the sixth never reaches the sink, its
   *       {@code lastSeenAt} never advances, and it would look exactly like a problem that had
   *       stopped. That makes a busy stack close tickets about its own busiest failures. It also
   *       means the sweep is inert while the backlog is over the cap and comes into effect as the
   *       backlog shrinks, which is the right way round.
   * </ol>
   *
   * <p>A dry run still calls through: {@code factory.linear.dry-run} makes the sink report what it
   * would close without writing, and a preview that silently skips half the run is not a preview.
   *
   * @return a sentence to append to the run detail, empty when nothing was swept
   */
  private String sweepResolved(
      final LogWatchRequest request,
      final ScanObservation observation,
      final Instant from,
      final Instant to,
      final String runId,
      final String workflowId,
      final List<String> resolvedUrls) {

    if (!request.resolveWhenClear() || !request.linearFilingEnabled()) {
      return "";
    }
    if (Duration.between(from, to).compareTo(MINIMUM_SWEEP_WINDOW) < 0) {
      return "";
    }
    if (observation.truncated()) {
      return "; the read was truncated, so no ticket was closed as resolved";
    }
    if (observation.signaturesDropped() > 0) {
      return "; the per-run cap dropped "
          + observation.signaturesDropped()
          + " signature(s), so no ticket was closed as resolved";
    }

    SweepReport report =
        linear.sweepResolved(
            new AbsenceSweep(
                PRODUCER,
                request.resolveAfter(),
                runId,
                workflowId,
                LogWatchReportRenderer.resolutionComment(request.resolveAfter(), runId),
                // A dry run still calls through rather than short-circuiting here: the sink
                // reports what it WOULD close and writes nothing, and a preview that silently
                // skips half the run is not a preview. It must be the request's own flag, not
                // the sink's configured one — the console's "Dry run scan" answers "nothing will
                // be filed" and has to mean it on a stack where the sink is configured to write.
                request.dryRun()));

    for (SweptIssue swept : report.resolved()) {
      if (swept.issueUrl() != null) {
        resolvedUrls.add(swept.issueUrl());
      }
    }

    if (report.unavailable()) {
      return "; "
          + report.considered()
          + " ticket(s) look resolved but the Linear team has no Done state to close them into";
    }
    if (report.resolved().isEmpty()) {
      return report.skippedStarted() > 0
          ? "; " + report.skippedStarted()
              + " ticket(s) look resolved but someone is working on them"
          : "";
    }
    return "; closed "
        + report.resolved().size()
        + " ticket(s) no longer reported ("
        + String.join(", ", report.resolved().stream().map(SweptIssue::issueIdentifier).toList())
        + ")";
  }

  private String describeOutcome(
      final LogWatchRequest request, final ScanObservation observation) {
    StringBuilder detail = new StringBuilder();
    detail
        .append(request.dryRun() ? "Dry run: would have filed " : "Filed ")
        .append(observation.signatures().size())
        .append(" problem(s)");
    if (observation.signaturesDropped() > 0) {
      detail
          .append("; ")
          .append(observation.signaturesDropped())
          .append(" more were dropped by the per-run cap");
    }
    if (observation.truncated()) {
      detail.append("; the read hit its line budget, so part of the window was not examined");
    }
    return detail.toString();
  }

  private LogWatchResult finish(
      final LogWatchRequest request,
      final ScanObservation observation,
      final LogWatchStatus status,
      final String workflowId,
      final String runId,
      final Instant startedAt,
      final Instant from,
      final Instant to,
      final List<String> issueUrls,
      final String detail,
      final List<String> resolvedUrls) {

    activities.recordRun(
        new LogWatchRunRecord(
            runId,
            workflowId,
            startedAt,
            Instant.ofEpochMilli(Workflow.currentTimeMillis()),
            status,
            request.trigger(),
            from,
            to,
            observation.linesRead(),
            observation.truncated(),
            observation.containersSeen(),
            observation.signatures().size(),
            observation.signaturesDropped(),
            observation.sourceHealth().status(),
            observation.sourceHealth().evidence(),
            issueUrls,
            detail,
            resolvedUrls));

    return new LogWatchResult(
        status,
        observation.sourceHealth(),
        observation.linesRead(),
        observation.containersSeen(),
        observation.signatures().size(),
        observation.signaturesDropped(),
        observation.truncated(),
        issueUrls,
        detail,
        resolvedUrls);
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }
}
