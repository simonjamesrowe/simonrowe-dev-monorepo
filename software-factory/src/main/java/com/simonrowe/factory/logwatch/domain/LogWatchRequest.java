package com.simonrowe.factory.logwatch.domain;

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
 */
public record LogWatchRequest(
    Instant windowStart,
    Instant windowEnd,
    Trigger trigger,
    boolean dryRun,
    boolean linearFilingEnabled) {
}
