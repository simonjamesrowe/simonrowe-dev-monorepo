package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.RepairPlan;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.FilingMode;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
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

  /** OSV is asked once per backend component, so this gets longer than the other reads. */
  private final CveFixActivities planning =
      Workflow.newActivityStub(
          CveFixActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
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
        current = new CveFixProgress(CveFixPhase.FETCHING,
            """
            Dependency-Track reported no findings; checking whether the repository was \
            previously dirty""",
            0);
        String detail = "Dependency-Track reported no findings";
        // Only on the transition. Commenting on every clean run would add a comment a night,
        // forever, and the sink's replay guard cannot help: it keys on the occurrence id, which
        // is this run's id and differs every night.
        if (activities.previousScanFoundFindings(workflowId)) {
          current = new CveFixProgress(CveFixPhase.FILING,
              "Commenting on the existing report now that the repository is clean", 0);
          outcomes.add(
              linear.fileIssue(
                  new IssueFiling(
                      "cvefix",
                      List.of(
                          "simonjamesrowe/simonrowe-dev-monorepo", "current-vulnerabilities"),
                      "Current vulnerabilities in simonrowe-dev-monorepo",
                      "",
                      """
                      No current vulnerabilities as of scan %s. \
                      Dependency-Track reports no findings."""
                          .formatted(runId),
                      runId,
                      workflowId,
                      FilingMode.STATUS_UPDATE)));
          detail = "Dependency-Track reported no findings; commented on the existing report";
        }
        current = new CveFixProgress(CveFixPhase.COMPLETED, detail, 0);
        return finish(workflowId, runId, startedAt, CveFixStatus.NO_FINDINGS,
            findingsSeen, componentsSeen, outcomes, detail);
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
                  """
                  scan %s found %s finding(s) across %s component(s)

                  %s"""
                      .formatted(runId, findingsSeen, componentsSeen, report),
                  runId,
                  workflowId,
                  FilingMode.ROLLING)));
      String detail =
          "Filed or updated one consolidated report for " + findingsSeen + " finding(s)";
      // Versioned, and asked only when the request wants repairs, so a run scheduled by an older
      // build (whose request has no such flag) replays the report-only command sequence.
      if (request.autoRepairEnabled()
          && Workflow.getVersion("repair-tickets", Workflow.DEFAULT_VERSION, 1) == 1) {
        current = new CveFixProgress(CveFixPhase.FILING,
            "Filing repair tickets for the ticket agent", componentsSeen);
        detail += fileRepairs(request, components, runId, workflowId, outcomes);
      }
      current = new CveFixProgress(CveFixPhase.COMPLETED,
          "Linear filing complete", componentsSeen);
      return finish(workflowId, runId, startedAt, CveFixStatus.COMPLETED,
          findingsSeen, componentsSeen, outcomes, detail);
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

  /**
   * Files one repair ticket per component the agent can fix alone, labelled for it.
   *
   * <p>Planning failing does not fail the scan: the report is already filed, and every component
   * is still in it for a person. Each ticket is keyed on the component at its current version and
   * filed under {@link FilingMode#REFRESH}, so a nightly re-scan rewrites an open ticket without a
   * comment, and the agent label goes on a brand-new ticket only, never on a regression.
   *
   * @return a clause for the run's detail
   */
  private String fileRepairs(
      final CveFixRequest request,
      final List<ComponentFindings> components,
      final String runId,
      final String workflowId,
      final List<FiledIssue> outcomes) {
    List<RepairPlan> plans;
    try {
      plans = planning.planRepairs(components);
    } catch (ActivityFailure failure) {
      Workflow.getLogger(CveFixWorkflowImpl.class)
          .warn("Could not plan repair tickets", failure);
      return "; repair planning failed, so no repair tickets were filed";
    }
    for (RepairPlan plan : plans) {
      outcomes.add(
          linear.fileIssue(
              new IssueFiling(
                  "cvefix",
                  CveRepairTicket.keyParts(plan),
                  CveRepairTicket.title(plan),
                  CveRepairTicket.body(plan),
                  "scan %s: %s %s still needs %s"
                      .formatted(runId, plan.coordinate(), plan.currentVersion(),
                          plan.targetVersion()),
                  runId,
                  workflowId,
                  FilingMode.REFRESH,
                  List.of(request.agentLabel()))));
    }
    return "; %d repair ticket(s) for the ticket agent".formatted(plans.size());
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
    CveFixCounts counts = countsOf(outcomes);
    List<String> urls = outcomes.stream()
        .map(FiledIssue::issueUrl)
        .filter(url -> url != null && !url.isBlank())
        .distinct()
        .toList();
    activities.recordRun(
        new CveFixRunRecord(
            CveFixRunRecord.idFor(workflowId), workflowId, startedAt, status, findingsSeen,
            List.of(), null, 0, detail, runId, componentsSeen, counts.filed(), counts.updated(),
            counts.suppressed(), counts.regressed(), urls));
    return new CveFixResult(
        workflowId, status, null, 0, 0, detail, counts.filed(), counts.updated(),
        counts.suppressed(), counts.regressed(), urls);
  }

  /**
   * Tallies what the sink did across one run's filings.
   *
   * <p>{@code UPDATED_EXISTING} is what {@link FilingMode#ROLLING} returns where {@link
   * FilingMode#OCCURRENCE} would have returned {@code COMMENTED_EXISTING}, and {@code
   * REOPENED_EXISTING} is what it returns where {@code OCCURRENCE} would have returned {@code
   * FILED_REGRESSION}. Each new decision is counted alongside the old one it replaced, so both
   * fields keep meaning what their names say regardless of which mode a producer used.
   *
   * @param outcomes what the sink decided for each occurrence filed this run
   * @return the tally of filed, updated, suppressed and regressed occurrences
   */
  static CveFixCounts countsOf(final List<FiledIssue> outcomes) {
    int filed = count(outcomes, FilingDecision.FILED_NEW);
    int updated =
        count(outcomes, FilingDecision.COMMENTED_EXISTING)
            + count(outcomes, FilingDecision.UPDATED_EXISTING);
    int suppressed = count(outcomes, FilingDecision.SUPPRESSED);
    int regressed =
        count(outcomes, FilingDecision.FILED_REGRESSION)
            + count(outcomes, FilingDecision.REOPENED_EXISTING);
    return new CveFixCounts(filed, updated, suppressed, regressed);
  }

  private static int count(final List<FiledIssue> outcomes, final FilingDecision decision) {
    return (int) outcomes.stream().filter(outcome -> outcome.decision() == decision).count();
  }

  /**
   * One run's tally of filing outcomes.
   *
   * @param filed occurrences that created a new issue
   * @param updated occurrences that changed an existing issue, by comment or by rewrite
   * @param suppressed occurrences a human had declined
   * @param regressed occurrences that reopened or replaced a previously completed issue
   */
  record CveFixCounts(int filed, int updated, int suppressed, int regressed) {

  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
