package com.simonrowe.factory.logwatch.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * One scan's settings.
 *
 * <p>{@code linearFilingEnabled} travels on the request rather than being read from configuration
 * because a {@code @WorkflowImpl} cannot inject Spring properties. It is the primary guard, not
 * the activity timeout: with the sink disabled nothing polls the {@code linear} queue, so an
 * unguarded schedule would stall this run until schedule-to-close instead of failing in
 * milliseconds. Same pattern as {@code CveFixRequest}.
 *
 * @param windowStart window start; null means {@code windowEnd} minus the configured default
 * @param windowEnd window end; null means the workflow's current time
 * @param trigger what started this scan
 * @param dryRun when true, nothing is created or commented on in Linear
 * @param linearFilingEnabled whether the Linear sink is switched on at all
 * @param resolveWhenClear whether a complete, source-healthy scan may close the tickets this
 *     module filed for problems it no longer sees
 * @param resolveAfter how long a problem must go unreported before its ticket is closed. Carried
 *     on the request for the same reason {@code linearFilingEnabled} is: a {@code @WorkflowImpl}
 *     cannot inject Spring properties, and a workflow that read a duration from configuration
 *     would also be non-deterministic across a replay that spans a config change
 */
public record LogWatchRequest(
    Instant windowStart,
    Instant windowEnd,
    Trigger trigger,
    boolean dryRun,
    boolean linearFilingEnabled,
    // Both last in the record on purpose: appended rather than inserted, so adding them did not
    // force an edit into the middle of every positional call site in the tests.
    boolean resolveWhenClear,
    Duration resolveAfter) {

  public LogWatchRequest {
    // A null here would NPE inside the workflow at Instant.minus, after the scan has already run
    // and filed. Defaulted rather than rejected: losing a sweep is not worth losing the scan.
    resolveAfter = resolveAfter == null ? Duration.ofDays(7) : resolveAfter;
  }
}
