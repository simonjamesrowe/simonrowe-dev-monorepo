package com.simonrowe.factory.codereview.domain;

import java.util.List;
import java.util.Locale;

/**
 * The two conclusions the {@code Code Review} check run is ever allowed to report.
 *
 * <p><b>Only success and failure.</b> GitHub offers {@code neutral}, {@code skipped},
 * {@code cancelled} and {@code action_required} as well, but whether a {@code neutral} conclusion
 * satisfies a ruleset's required status check is version-dependent behaviour — and this check is
 * the thing standing between a critical finding and {@code main}. A gate must not rest on a
 * behaviour that can change underneath it, so the type has exactly two constants and a test asserts
 * that it still does.
 *
 * <p>There is a third outcome, deliberately not modelled here: <b>no check run at all</b>.
 * A review that dies before the head SHA is known cannot create one, and a required status check
 * that is absent blocks the merge. That is the fix for the old failure mode in which silence was
 * the normal presentation of a failed review — the signal that most needed to block was the one
 * that could not.
 */
public enum CheckRunConclusion {
  SUCCESS,
  FAILURE;

  /**
   * Maps a review outcome to a conclusion.
   *
   * <p>Both conditions are evaluated, not just the verdict. The engine grades the summary and the
   * individual findings in the same pass but not necessarily consistently, so it can return
   * {@code APPROVE} while reporting a {@code CRITICAL} finding. When it does, the finding wins.
   */
  public static CheckRunConclusion from(
      final Verdict verdict, final List<ReviewFinding> findings) {
    boolean critical =
        findings != null
            && findings.stream().anyMatch(finding -> finding.severity() == Severity.CRITICAL);
    return verdict == Verdict.REQUEST_CHANGES || critical ? FAILURE : SUCCESS;
  }

  /** The value GitHub's check-runs API expects. */
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
