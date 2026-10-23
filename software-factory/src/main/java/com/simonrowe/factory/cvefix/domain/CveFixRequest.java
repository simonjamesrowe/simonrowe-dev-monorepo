package com.simonrowe.factory.cvefix.domain;

import java.time.Duration;

/**
 * Input to one CVE-fix run.
 *
 * <p>{@code dryRun} stops before the pull request is opened, so the first manual trigger cannot
 * create one.
 *
 * <p>The three CI settings are carried here rather than read from {@code CveFixProperties},
 * because workflow code cannot reach the properties bean: {@code @WorkflowImpl} classes are
 * instantiated by the Temporal SDK, not by Spring, so they have no injected dependencies at all —
 * which is why both existing implementations hold only constants. {@code CveFixScheduleInitializer}
 * holds the bean and copies the values into the request it schedules; the compact constructor
 * below supplies the same defaults so a hand-started workflow with a sparse JSON input still
 * behaves sensibly.
 *
 * @param dryRun whether to stop before opening the pull request
 * @param pollInterval how long to wait between CI polls
 * @param repairBudget how many repair attempts are allowed after the first push
 * @param maxWait the wall-clock cap on the whole CI loop
 * @param linearFilingEnabled whether to file each newly-recorded unfixable component into Linear.
 *     Carried on the request for the same reason as the CI settings above, plus a second one: with
 *     the sink disabled nothing polls the {@code linear} queue, so scheduling the activity would
 *     stall the run until its schedule-to-close timeout.
 * @param autoRepairEnabled whether to file a repair ticket, labelled for the ticket agent, for
 *     each backend component with a fixed release in the same major version. Absent from a request
 *     scheduled by an older build, which reads as false: such a run files only the report
 * @param agentLabel the ticket agent's hand-over label put on each repair ticket
 */
public record CveFixRequest(
    boolean dryRun,
    Duration pollInterval,
    int repairBudget,
    Duration maxWait,
    boolean linearFilingEnabled,
    boolean autoRepairEnabled,
    String agentLabel) {

  /** Fills in the production defaults so a sparse hand-written JSON input still behaves. */
  public CveFixRequest {
    pollInterval = pollInterval == null ? Duration.ofMinutes(3) : pollInterval;
    repairBudget = repairBudget == 0 ? 3 : repairBudget;
    maxWait = maxWait == null ? Duration.ofHours(3) : maxWait;
    agentLabel = agentLabel == null || agentLabel.isBlank() ? "factory:agent" : agentLabel;
  }

  /** A request that files only the consolidated report. */
  public CveFixRequest(
      final boolean dryRun,
      final Duration pollInterval,
      final int repairBudget,
      final Duration maxWait,
      final boolean linearFilingEnabled) {
    this(dryRun, pollInterval, repairBudget, maxWait, linearFilingEnabled, false, null);
  }
}
