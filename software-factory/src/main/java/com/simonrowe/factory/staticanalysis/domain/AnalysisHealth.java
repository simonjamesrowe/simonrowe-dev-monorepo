package com.simonrowe.factory.staticanalysis.domain;

import java.time.Instant;

/**
 * Whether the latest analysis can be believed, decided before any finding is read.
 *
 * <p>The module's founding rule, borrowed from log watch: <strong>an empty read is not a clean
 * read.</strong> SonarCloud keeps answering for the last analysis it has, so when the
 * {@code Static Analysis} CI job stops running on {@code main} - it is skipped whenever a build
 * job fails, and it has failed in at least five different ways since it was added - every read
 * still succeeds and still says the same thing, and a module without this check would report the
 * same findings, or the same clean result, for ever.
 *
 * @param status the verdict
 * @param evidence one or two sentences justifying it, shown on the run and in any ticket
 * @param analysedRevision the commit the latest analysis is of, or null when there is none
 * @param analysedAt when that analysis ran, or null when there is none
 * @param headRevision the branch's head commit, or null when GitHub could not be read
 */
public record AnalysisHealth(
    Status status,
    String evidence,
    String analysedRevision,
    Instant analysedAt,
    String headRevision) {

  /** The verdicts. Only {@link #FRESH} lets a run file findings or close tickets. */
  public enum Status {
    /** The analysis is of the branch head, or the head is too new to have been analysed yet. */
    FRESH,
    /** The branch head has gone unanalysed for longer than the configured grace. */
    STALE,
    /** SonarCloud holds no analysis of the branch at all. */
    NO_ANALYSIS,
    /** SonarCloud could not be read. */
    UNREACHABLE
  }

  /**
   * Whether findings read from this analysis may be filed and absences acted on.
   *
   * @return true only when fresh
   */
  public boolean usable() {
    return status == Status.FRESH;
  }
}
