package com.simonrowe.factory.linear.domain;

import java.time.Duration;
import java.util.List;

/**
 * A request to close the issues a producer has stopped reporting.
 *
 * <p>The counterpart to {@link IssueFiling}: filing says "this problem is happening", a sweep says
 * "these problems have stopped". Without one, an automated factory files tickets forever and
 * relies on a human to notice each one has gone away — which is the manual step the rest of this
 * module exists to remove.
 *
 * <p><strong>Recently seen is the default answer to "still present", not a second one.</strong>
 * A sweep is always run <em>after</em> the producer has finished filing, and every filing
 * advances that fingerprint's {@code lastSeenAt}. So for everything that was filed, "still
 * happening" and "recently seen" are the same fact and {@link #quietFor} is the only input needed.
 *
 * <p>{@link #presentKeyParts} covers the one case filing cannot: a problem the producer
 * <em>saw</em> but did not file, because its own per-run cap left no room for it. Nothing
 * advanced that fingerprint, so without this list it would look exactly like a problem that had
 * stopped. It is deliberately narrow — the producer names what it saw and withheld, not
 * everything it believes is live — so it never becomes an independent answer to the question
 * {@code lastSeenAt} already answers for everything else. A producer that excludes findings
 * <em>on purpose</em>, such as log watch's muted third-party noise, must not list them here: those
 * tickets are meant to close.
 *
 * <p><strong>The caller owns the decision to sweep at all.</strong> This record carries no
 * "is it safe" flag because safety is not a property of the sweep — it is a property of the
 * observation that preceded it, which only the producer can judge. See
 * {@code LogWatchWorkflowImpl.sweepResolved} for the conditions that must hold.
 *
 * @param producer the producer key; only this producer's fingerprints are considered, so a
 *     log-watch sweep can never close a {@code cvefix} or {@code deploy} ticket
 * @param quietFor how long a fingerprint must have gone unreported before its issue is closed
 * @param occurrenceId the producing run id, recorded in the audit trail
 * @param workflowId the producing workflow id, recorded in the audit trail
 * @param comment posted on each issue as it is closed, so the close is never unexplained
 * @param dryRun when true, report what would be closed and write nothing. <b>Request-level</b>,
 *     and separate from the sink's own {@code factory.linear.dry-run} configuration: a producer
 *     can be asked for a preview on one run while the sink is configured to write on every other,
 *     which is exactly what the console's "Dry run scan" button does. The sink honours whichever
 *     of the two is set. Omitting it was a real bug caught in review — a manual dry-run scan
 *     answered "nothing will be filed" and then closed real tickets, because only the global flag
 *     was consulted
 * @param presentKeyParts key parts of problems seen this run but not filed, whose fingerprints
 *     are therefore treated as recently seen and never closed by this sweep. Fingerprinted by the
 *     sink with {@link Fingerprint#of} and this sweep's own {@link #producer}, exactly as an
 *     {@link IssueFiling}'s key parts are, so the producer never computes a fingerprint itself.
 *     Absent (an older caller) means empty, which excludes nothing
 */
public record AbsenceSweep(
    String producer,
    Duration quietFor,
    String occurrenceId,
    String workflowId,
    String comment,
    boolean dryRun,
    List<List<String>> presentKeyParts) {

  public AbsenceSweep {
    presentKeyParts =
        presentKeyParts == null
            ? List.of()
            : presentKeyParts.stream().map(List::copyOf).toList();
  }
}
