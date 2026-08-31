package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import java.util.List;

/**
 * Everything one read of the log source produced, as a single activity result.
 *
 * <p>Read, grouping and the source-health verdict travel together because they are one
 * observation: splitting them across activities would let a retry re-read the window and reach a
 * different verdict than the one the signatures came from.
 *
 * @param sourceHealth whether these results mean anything
 * @param signatures the grouped problems, already filtered and capped
 * @param linesRead how many lines were read
 * @param truncated whether the read hit its line budget, so an unknown number of further lines
 *     exist that this scan never examined. A count is deliberately not reported: Loki's response
 *     is capped at the limit and carries no total, so any number here would be invented. What
 *     FR-006 requires is that a truncated read is never presented as a complete one.
 * @param containersSeen how many distinct containers produced lines
 * @param signaturesDropped how many signatures the per-run cap discarded
 */
public record ScanObservation(
    SourceHealth sourceHealth,
    List<LogSignature> signatures,
    int linesRead,
    boolean truncated,
    int containersSeen,
    int signaturesDropped) {

  public ScanObservation {
    signatures = signatures == null ? List.of() : List.copyOf(signatures);
  }
}
