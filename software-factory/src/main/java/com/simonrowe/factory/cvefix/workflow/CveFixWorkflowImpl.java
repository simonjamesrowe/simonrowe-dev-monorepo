package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Deterministic issue-only vulnerability scan: Dependency-Track to Linear, with no git path. */
@WorkflowImpl(taskQueues = CveFixTaskQueues.CVE_FIX)
public class CveFixWorkflowImpl implements CveFixWorkflow {

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final CveFixActivities activities =
      Workflow.newActivityStub(
          CveFixActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
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

  private CveFixProgress current = CveFixProgress.accepted();

  @Override
  public CveFixResult run(final CveFixRequest request) {
    String workflowId = Workflow.getInfo().getWorkflowId();
    String runId = Workflow.getInfo().getRunId();
    Instant startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    int findingsSeen = 0;
    int componentsSeen = 0;
    List<FiledIssue> outcomes = new ArrayList<>();
    try {
      if (!request.linearFilingEnabled()) {
        throw ApplicationFailure.newNonRetryableFailure(
            "Linear filing is disabled", "LINEAR_DISABLED");
      }
      current = new CveFixProgress(CveFixPhase.FETCHING,
          "Reading Dependency-Track findings", null);
      List<ComponentFindings> components = activities.fetchFindings();
      componentsSeen = components.size();
      findingsSeen = components.stream().mapToInt(component -> component.findings().size()).sum();
      if (components.isEmpty()) {
        current = new CveFixProgress(CveFixPhase.COMPLETED, "No findings", 0);
        return finish(workflowId, runId, startedAt, CveFixStatus.NO_FINDINGS,
            findingsSeen, componentsSeen, outcomes, "Dependency-Track reported no findings");
      }

      current = new CveFixProgress(CveFixPhase.FILING,
          "Filing consolidated vulnerability report in Linear", componentsSeen);
      String report = CveReportRenderer.report(components, findingsSeen);
      outcomes.add(
          linear.fileIssue(
              new IssueFiling(
                  "cvefix",
                  List.of("simonjamesrowe/simonrowe-dev-monorepo", "current-vulnerabilities"),
                  "Current vulnerabilities in simonrowe-dev-monorepo",
                  report,
                  "scan " + runId + " found " + findingsSeen + " finding(s) across "
                      + componentsSeen + " component(s)\n\n" + report,
                  runId,
                  workflowId)));
      current = new CveFixProgress(CveFixPhase.COMPLETED,
          "Linear filing complete", componentsSeen);
      return finish(workflowId, runId, startedAt, CveFixStatus.COMPLETED,
          findingsSeen, componentsSeen, outcomes,
          "Filed or updated one consolidated report for " + findingsSeen + " finding(s)");
    } catch (RuntimeException exception) {
      String detail = safeMessage(exception);
      current = new CveFixProgress(CveFixPhase.FAILED, detail, componentsSeen);
      try {
        finish(workflowId, runId, startedAt, CveFixStatus.FAILED,
            findingsSeen, componentsSeen, outcomes, detail);
      } catch (RuntimeException recordFailure) {
        Workflow.getLogger(CveFixWorkflowImpl.class)
            .warn("Could not record failed vulnerability scan", recordFailure);
      }
      throw exception;
    }
  }

  @Override
  public CveFixProgress progress() {
    return current;
  }

  private CveFixResult finish(
      final String workflowId,
      final String runId,
      final Instant startedAt,
      final CveFixStatus status,
      final int findingsSeen,
      final int componentsSeen,
      final List<FiledIssue> outcomes,
      final String detail) {
    int filed = count(outcomes, FilingDecision.FILED_NEW);
    int updated = count(outcomes, FilingDecision.COMMENTED_EXISTING);
    int suppressed = count(outcomes, FilingDecision.SUPPRESSED);
    int regressed = count(outcomes, FilingDecision.FILED_REGRESSION);
    List<String> urls = outcomes.stream()
        .map(FiledIssue::issueUrl)
        .filter(url -> url != null && !url.isBlank())
        .distinct()
        .toList();
    activities.recordRun(
        new CveFixRunRecord(
            CveFixRunRecord.idFor(workflowId), workflowId, startedAt, status, findingsSeen,
            List.of(), null, 0, detail, runId, componentsSeen, filed, updated, suppressed,
            regressed, urls));
    return new CveFixResult(
        workflowId, status, null, 0, 0, detail, filed, updated, suppressed, regressed, urls);
  }

  private static int count(final List<FiledIssue> outcomes, final FilingDecision decision) {
    return (int) outcomes.stream().filter(outcome -> outcome.decision() == decision).count();
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
