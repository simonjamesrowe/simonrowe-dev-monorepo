package com.simonrowe.factory.codereview.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What this reviewer already said on a pull request: every conversation on it, and the findings
 * among them that the next review must take into account.
 *
 * @param threads every review conversation on the pull request, this reviewer's or not
 * @param findings this reviewer's findings that are still open or that a person declined
 */
public record PriorReview(List<ExistingThread> threads, List<PriorFinding> findings) {

  public PriorReview {
    threads = threads == null ? List.of() : List.copyOf(threads);
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /** A pull request nobody has reviewed yet. */
  public static PriorReview none() {
    return new PriorReview(List.of(), List.of());
  }

  /** Fingerprints a person with write access declined by resolving their thread. */
  public Set<String> declinedFingerprints() {
    Set<String> declined = new LinkedHashSet<>();
    for (PriorFinding finding : findings) {
      if (finding.state() == PriorFinding.State.DECLINED) {
        declined.add(finding.fingerprint());
      }
    }
    return declined;
  }
}
