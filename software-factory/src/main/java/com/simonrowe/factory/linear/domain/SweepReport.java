package com.simonrowe.factory.linear.domain;

import java.util.List;

/**
 * What an {@link AbsenceSweep} did.
 *
 * <p>The four counts are reported separately rather than collapsed into "closed N" because they
 * answer different questions and a sweep that closes nothing has several very different meanings.
 * "Nothing was quiet long enough" is a working sweep; "everything quiet is already closed" is a
 * working sweep; "everything quiet is being worked on by a human" is a working sweep; and "the
 * team has no completed state so nothing can ever be closed" is a broken one that would otherwise
 * present identically to the first three.
 *
 * @param considered how many of this producer's tracked fingerprints were quiet long enough to be
 *     candidates
 * @param resolved the issues actually closed
 * @param skippedStarted candidates left alone because a human had moved the issue into a started
 *     state — see {@code IssueResolver} for why that outranks the sweep
 * @param alreadyClosed candidates whose issue Linear already reports as completed, cancelled or
 *     duplicate; nothing to do, and not an error
 * @param untracked candidates with no issue recorded against them at all, e.g. a fingerprint only
 *     ever seen on a dry run
 * @param unavailable true when the sweep could not run because the Linear team has no
 *     {@code completed}-type workflow state to close issues into
 */
public record SweepReport(
    int considered,
    List<SweptIssue> resolved,
    int skippedStarted,
    int alreadyClosed,
    int untracked,
    boolean unavailable) {

  public SweepReport {
    resolved = resolved == null ? List.of() : List.copyOf(resolved);
  }

  /** An empty report, for a sweep that was never attempted. */
  public static SweepReport none() {
    return new SweepReport(0, List.of(), 0, 0, 0, false);
  }

  /** A report for a team with nowhere to close an issue to. */
  public static SweepReport unavailable(final int considered) {
    return new SweepReport(considered, List.of(), 0, 0, 0, true);
  }
}
