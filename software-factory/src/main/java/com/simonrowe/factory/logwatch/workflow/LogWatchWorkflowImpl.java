package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
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
            issueUrls);
      }

      current =
          new LogWatchProgress(
              LogWatchPhase.GROUPING,
              "Read " + observation.linesRead() + " line(s) from "
                  + observation.containersSeen() + " container(s)",
              observation.signatures().size());

      if (observation.signatures().isEmpty()) {
        String detail =
            "No signature met the minimum occurrence threshold. Source health: "
                + observation.sourceHealth().evidence();
        current = new LogWatchProgress(LogWatchPhase.DONE, detail, 0);
        return finish(request, observation, LogWatchStatus.NO_FINDINGS, workflowId, runId,
            startedAt, from, to, issueUrls, detail);
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

      String detail = describeOutcome(request, observation);
      current =
          new LogWatchProgress(LogWatchPhase.DONE, detail, observation.signatures().size());
      return finish(request, observation, LogWatchStatus.COMPLETED, workflowId, runId, startedAt,
          from, to, issueUrls, detail);

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
            issueUrls, detail);
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
      final List<String> issueUrls) {

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
                  workflowId));
      if (filed.issueUrl() != null) {
        issueUrls.add(filed.issueUrl());
      }
    }

    current = new LogWatchProgress(LogWatchPhase.DONE, detail, 0);
    return finish(request, observation, LogWatchStatus.SOURCE_UNHEALTHY, workflowId, runId,
        startedAt, from, to, issueUrls, detail);
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
                workflowId));
    if (filed.issueUrl() != null) {
      issueUrls.add(filed.issueUrl());
    }
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
      final String detail) {

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
            detail));

    return new LogWatchResult(
        status,
        observation.sourceHealth(),
        observation.linesRead(),
        observation.containersSeen(),
        observation.signatures().size(),
        observation.signaturesDropped(),
        observation.truncated(),
        issueUrls,
        detail);
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }
}
