package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingMode;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.SweptIssue;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisTaskQueues;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisPhase;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisProgress;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisResult;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisStatus;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRecord;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One deterministic scan: judge the analysis, file each finding group, sweep what has gone.
 *
 * <p>The shape is log watch's, on purpose, including its most important line: the analysis is
 * judged <strong>before</strong> anything is read, and an analysis that cannot be trusted files
 * one ticket saying so and never sweeps. SonarCloud goes on answering for its last analysis
 * whatever has happened since, so a stale read and a current one look the same to everything
 * except that check.
 */
@WorkflowImpl(taskQueues = StaticAnalysisTaskQueues.STATIC_ANALYSIS)
public class StaticAnalysisWorkflowImpl implements StaticAnalysisWorkflow {

  /** The producer key: the Linear label and the sweep's scope. */
  static final String PRODUCER = "staticanalysis";

  /** Key part marking the analysis-health finding, so it dedupes apart from every rule. */
  private static final String SOURCE_HEALTH_KEY = "source-health";

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final StaticAnalysisActivities activities =
      Workflow.newActivityStub(
          StaticAnalysisActivities.class,
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

  private StaticAnalysisProgress current = StaticAnalysisProgress.accepted();

  @Override
  public StaticAnalysisResult run(final StaticAnalysisRequest request) {
    String workflowId = Workflow.getInfo().getWorkflowId();
    String runId = Workflow.getInfo().getRunId();
    Instant startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    List<String> issueUrls = new ArrayList<>();
    List<String> resolvedUrls = new ArrayList<>();
    AnalysisObservation observation = null;
    try {
      current = new StaticAnalysisProgress(StaticAnalysisPhase.CHECKING_ANALYSIS,
          "Checking SonarCloud's latest analysis is current", null);
      observation = activities.observe();

      if (!observation.health().usable()) {
        return handleUnusableAnalysis(request, observation, workflowId, runId, startedAt,
            issueUrls, resolvedUrls);
      }

      current = new StaticAnalysisProgress(StaticAnalysisPhase.GROUPING,
          "Read %s open new-code issue(s)".formatted(observation.issuesSeen()),
          observation.groupsFound());

      if (observation.groups().isEmpty()) {
        // A clean analysis is when the sweep matters most: it is the run that closes the last
        // open ticket.
        String swept = sweepResolved(request, observation, runId, workflowId, resolvedUrls);
        String detail = "Nothing to file. %s%s"
            .formatted(observation.health().evidence(), swept);
        current = new StaticAnalysisProgress(StaticAnalysisPhase.DONE, detail, 0);
        return finish(request, observation, StaticAnalysisStatus.NO_FINDINGS, workflowId, runId,
            startedAt, issueUrls, detail, resolvedUrls);
      }

      current = new StaticAnalysisProgress(StaticAnalysisPhase.FILING,
          request.dryRun()
              ? "Dry run: reporting what would be filed, creating nothing"
              : "Filing " + observation.groups().size() + " finding group(s) in Linear",
          observation.groupsFound());
      for (FindingGroup group : observation.groups()) {
        fileGroup(request, observation, group, runId, workflowId, issueUrls);
      }

      // AFTER filing, never before: every filing advances its fingerprint's lastSeenAt, and the
      // sweep's only input is which fingerprints are stale.
      String swept = sweepResolved(request, observation, runId, workflowId, resolvedUrls);
      String detail = describeOutcome(request, observation) + swept;
      current = new StaticAnalysisProgress(StaticAnalysisPhase.DONE, detail,
          observation.groupsFound());
      return finish(request, observation, StaticAnalysisStatus.COMPLETED, workflowId, runId,
          startedAt, issueUrls, detail, resolvedUrls);

    } catch (RuntimeException exception) {
      String detail = safeMessage(exception);
      current = new StaticAnalysisProgress(StaticAnalysisPhase.DONE, detail, null);
      AnalysisObservation failed =
          AnalysisObservation.unusable(
              new AnalysisHealth(AnalysisHealth.Status.UNREACHABLE,
                  "The scan failed before reaching a verdict.", null, null, null),
              observation == null ? null : observation.project());
      try {
        finish(request, failed, StaticAnalysisStatus.FAILED, workflowId, runId, startedAt,
            issueUrls, detail, resolvedUrls);
      } catch (RuntimeException recordFailure) {
        Workflow.getLogger(StaticAnalysisWorkflowImpl.class)
            .warn("Could not record failed static-analysis scan", recordFailure);
      }
      throw exception;
    }
  }

  @Override
  public StaticAnalysisProgress progress() {
    return current;
  }

  /**
   * Files the fact that the analysis cannot be trusted, and stops.
   *
   * <p>Through the same sink as any finding, so it inherits dedup and cancel-to-suppress, and it
   * is closed by the sweep of the first scan that finds a current analysis again. Key parts carry
   * the status and not the evidence, which names commits and times that differ every night.
   */
  private StaticAnalysisResult handleUnusableAnalysis(
      final StaticAnalysisRequest request,
      final AnalysisObservation observation,
      final String workflowId,
      final String runId,
      final Instant startedAt,
      final List<String> issueUrls,
      final List<String> resolvedUrls) {
    AnalysisHealth health = observation.health();
    String detail = "Analysis is not usable (" + health.status() + "): " + health.evidence();
    current = new StaticAnalysisProgress(StaticAnalysisPhase.FILING, detail, 0);
    if (!request.dryRun() && request.linearFilingEnabled()) {
      FiledIssue filed =
          linear.fileIssue(
              new IssueFiling(
                  PRODUCER,
                  List.of(SOURCE_HEALTH_KEY, health.status().name()),
                  StaticAnalysisReportRenderer.sourceHealthTitle(health),
                  StaticAnalysisReportRenderer.sourceHealthBody(health, observation.project()),
                  "scan " + runId + ": " + health.evidence(),
                  runId,
                  workflowId,
                  FilingMode.REFRESH));
      addUrl(issueUrls, filed);
    }
    // Deliberately no sweep. A stale analysis would have every ticket filed since it ran look
    // unreported, and a sweep here would close them - including the one just filed to say the
    // module cannot see.
    current = new StaticAnalysisProgress(StaticAnalysisPhase.DONE, detail, 0);
    return finish(request, observation, StaticAnalysisStatus.SOURCE_UNHEALTHY, workflowId, runId,
        startedAt, issueUrls, detail, resolvedUrls);
  }

  private void fileGroup(
      final StaticAnalysisRequest request,
      final AnalysisObservation observation,
      final FindingGroup group,
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
                group.keyParts(),
                StaticAnalysisReportRenderer.title(group),
                StaticAnalysisReportRenderer.body(group, observation.project(),
                    observation.health()),
                StaticAnalysisReportRenderer.occurrenceDetail(group, runId),
                runId,
                workflowId,
                // REFRESH, as log watch files: rewrite the description each night while the
                // finding persists, without a comment a night, and file a linked replacement if
                // it comes back after being closed.
                FilingMode.REFRESH));
    addUrl(issueUrls, filed);
  }

  /**
   * Closes the tickets for findings the analysis no longer reports.
   *
   * <p>Three conditions, each a way to close a ticket about a finding that is still there:
   *
   * <ol>
   *   <li><b>The analysis is current.</b> Enforced by never calling this on the unusable path.
   *   <li><b>The issue read was complete.</b> A truncated read has findings on pages it never
   *       fetched.
   *   <li><b>Every finding seen but not filed is named.</b> The per-run cap drops groups, and
   *       nothing advanced their {@code lastSeenAt}, so they travel as
   *       {@link AbsenceSweep#presentKeyParts}. On a dry run nothing was filed at all, so every
   *       group is named: the preview must not report the whole backlog as closable.
   * </ol>
   *
   * @return a sentence to append to the run detail, empty when nothing was swept
   */
  private String sweepResolved(
      final StaticAnalysisRequest request,
      final AnalysisObservation observation,
      final String runId,
      final String workflowId,
      final List<String> resolvedUrls) {
    if (!request.resolveWhenClear() || !request.linearFilingEnabled()) {
      return "";
    }
    if (observation.truncated()) {
      return "; the issue read was truncated, so no ticket was closed as resolved";
    }
    List<List<String>> present = new ArrayList<>(observation.droppedKeyParts());
    if (request.dryRun()) {
      observation.groups().forEach(group -> present.add(group.keyParts()));
    }
    SweepReport report =
        linear.sweepResolved(
            new AbsenceSweep(
                PRODUCER,
                request.resolveAfter(),
                runId,
                workflowId,
                StaticAnalysisReportRenderer.resolutionComment(
                    request.resolveAfter(), runId, observation.health()),
                request.dryRun(),
                present));
    for (SweptIssue swept : report.resolved()) {
      if (swept.issueUrl() != null) {
        resolvedUrls.add(swept.issueUrl());
      }
    }
    if (report.unavailable()) {
      return "; %s ticket(s) look resolved but the Linear team has no Done state to close them into"
          .formatted(report.considered());
    }
    if (report.resolved().isEmpty()) {
      return report.skippedStarted() > 0
          ? "; %s ticket(s) look resolved but someone is working on them"
              .formatted(report.skippedStarted())
          : "";
    }
    String identifiers =
        String.join(", ", report.resolved().stream().map(SweptIssue::issueIdentifier).toList());
    return "; %s %s ticket(s) no longer reported (%s)"
        .formatted(request.dryRun() ? "would close" : "closed", report.resolved().size(),
            identifiers);
  }

  private static String describeOutcome(
      final StaticAnalysisRequest request, final AnalysisObservation observation) {
    StringBuilder detail = new StringBuilder()
        .append(request.dryRun() ? "Dry run: would have filed " : "Filed ")
        .append(observation.groups().size())
        .append(" finding group(s) from ")
        .append(observation.issuesSeen())
        .append(" open new-code issue(s)");
    if (observation.groupsDropped() > 0) {
      detail.append("; ").append(observation.groupsDropped())
          .append(" more were held back by the per-run cap");
    }
    if (observation.issuesBelowFloor() > 0) {
      detail.append("; ").append(observation.issuesBelowFloor())
          .append(" code smell(s) were below the severity floor");
    }
    return detail.toString();
  }

  private StaticAnalysisResult finish(
      final StaticAnalysisRequest request,
      final AnalysisObservation observation,
      final StaticAnalysisStatus status,
      final String workflowId,
      final String runId,
      final Instant startedAt,
      final List<String> issueUrls,
      final String detail,
      final List<String> resolvedUrls) {
    AnalysisHealth health = observation.health();
    activities.recordRun(
        new StaticAnalysisRunRecord(
            runId,
            workflowId,
            startedAt,
            Instant.ofEpochMilli(Workflow.currentTimeMillis()),
            status,
            request.trigger(),
            health.status(),
            health.evidence(),
            health.analysedRevision(),
            health.analysedAt(),
            observation.issuesSeen(),
            observation.issuesBelowFloor(),
            observation.groupsFound(),
            observation.groupsDropped(),
            issueUrls,
            resolvedUrls,
            detail));
    return new StaticAnalysisResult(status, health, observation.issuesSeen(),
        observation.groupsFound(), observation.groupsDropped(), issueUrls, detail, resolvedUrls);
  }

  private static void addUrl(final List<String> urls, final FiledIssue filed) {
    if (filed != null && filed.issueUrl() != null) {
      urls.add(filed.issueUrl());
    }
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
