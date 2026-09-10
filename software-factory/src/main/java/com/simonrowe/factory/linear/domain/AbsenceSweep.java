package com.simonrowe.factory.linear.domain;

import java.time.Duration;

/**
 * A request to close the issues a producer has stopped reporting.
 *
 * <p>The counterpart to {@link IssueFiling}: filing says "this problem is happening", a sweep says
 * "these problems have stopped". Without one, an automated factory files tickets forever and
 * relies on a human to notice each one has gone away — which is the manual step the rest of this
 * module exists to remove.
 *
 * <p><strong>There is no list of what is still present, and that is the design.</strong> A sweep
 * is always run <em>after</em> the producer has finished filing, and every filing advances that
 * fingerprint's {@code lastSeenAt}. So "still happening" and "recently seen" are the same fact,
 * and {@link #quietFor} is the only input needed. Passing a present-set as well would introduce a
 * second, independently-wrong answer to the same question.
 *
 * <p><strong>The caller owns the decision to sweep at all.</strong> This record carries no
 * "is it safe" flag because safety is not a property of the sweep — it is a property of the
 * observation that preceded it, which only the producer can judge. See
 * {@code LogWatchWorkflowImpl.sweepResolved} for the three conditions that must hold.
 *
 * @param producer the producer key; only this producer's fingerprints are considered, so a
 *     log-watch sweep can never close a {@code cvefix} or {@code deploy} ticket
 * @param quietFor how long a fingerprint must have gone unreported before its issue is closed
 * @param occurrenceId the producing run id, recorded in the audit trail
 * @param workflowId the producing workflow id, recorded in the audit trail
 * @param comment posted on each issue as it is closed, so the close is never unexplained
 */
public record AbsenceSweep(
    String producer,
    Duration quietFor,
    String occurrenceId,
    String workflowId,
    String comment) {
}
