package com.simonrowe.factory.staticanalysis.domain;

import java.time.Duration;

/**
 * One scan's settings.
 *
 * <p>The Linear flag and the sweep settings travel on the request rather than being read from
 * configuration because a {@code @WorkflowImpl} is instantiated by the Temporal SDK and cannot
 * inject Spring properties. The Linear flag is also the primary guard against scheduling a filing
 * nothing will execute: with the sink disabled nothing polls the {@code linear} queue, so an
 * unguarded schedule would stall the run until schedule-to-close. Same pattern as
 * {@code LogWatchRequest}.
 *
 * @param trigger what started this scan
 * @param dryRun when true, nothing is created, commented on or closed in Linear
 * @param linearFilingEnabled whether the Linear sink is switched on at all
 * @param resolveWhenClear whether a fresh analysis may close the tickets for findings it no longer
 *     reports
 * @param resolveAfter how long a finding must go unreported before its ticket is closed
 */
public record StaticAnalysisRequest(
    Trigger trigger,
    boolean dryRun,
    boolean linearFilingEnabled,
    boolean resolveWhenClear,
    Duration resolveAfter) {

  public StaticAnalysisRequest {
    trigger = trigger == null ? Trigger.SCHEDULED : trigger;
    // Defaulted rather than rejected: a hand-started workflow with a sparse JSON input should
    // still scan, and losing a sweep is not worth losing the scan.
    resolveAfter = resolveAfter == null ? Duration.ofHours(12) : resolveAfter;
  }
}
