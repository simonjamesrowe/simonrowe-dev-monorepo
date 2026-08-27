package com.simonrowe.factory.linear.persistence;

import com.simonrowe.factory.linear.domain.FilingDecision;
import java.time.Instant;

/**
 * One entry in a problem's decision log.
 *
 * @param at when the decision was taken
 * @param decision what the sink did
 * @param occurrenceId the producing workflow's run id, used to recognise an activity replay
 * @param workflowId the producing workflow's id, for tracing back to the run
 * @param detail one line of human context, e.g. the commit that tripped it
 * @param dryRun whether this decision was hypothetical — Linear was read but never written to.
 *     {@link LinearIssueRecord#hasOccurrence} must skip dry-run entries: they audit what
 *     <em>would</em> have happened, not what did, so they must never satisfy the replay guard
 *     for a real retry of the same occurrence.
 */
public record LinearIssueDecision(
    Instant at,
    FilingDecision decision,
    String occurrenceId,
    String workflowId,
    String detail,
    boolean dryRun) {
}
