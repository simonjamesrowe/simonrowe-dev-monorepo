package com.simonrowe.factory.linear.service;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides what to do about one occurrence, given every issue carrying its fingerprint.
 *
 * <p>Pure and I/O-free: this is the feature, so it is exhaustively testable without a tracker.
 *
 * <p><strong>Precedence is open &gt; canceled &gt; completed.</strong> It has to be defined rather
 * than assumed, because the regression path deliberately leaves two issues sharing one
 * fingerprint. Two consequences are load-bearing:
 *
 * <ul>
 *   <li>Reopening a cancelled issue un-suppresses it, because open outranks canceled. That is the
 *       reversal gesture, and it needs no configuration.
 *   <li>Canceled outranks completed, because "never tell me again" is a more deliberate statement
 *       than "this was once fixed".
 * </ul>
 */
@Component
public class FilingDecider {

  /**
   * Applies the precedence rules.
   *
   * @param carryingFingerprint every issue found carrying the occurrence's fingerprint, in any
   *     order; empty when none was found
   * @return the decision, and the issue it concerns — null for {@link FilingDecision#FILED_NEW}
   */
  public Outcome decide(final List<TrackedIssue> carryingFingerprint) {
    Optional<TrackedIssue> open =
        newest(carryingFingerprint.stream().filter(i -> i.stateType().open()).toList());
    if (open.isPresent()) {
      return new Outcome(FilingDecision.COMMENTED_EXISTING, open.get());
    }
    Optional<TrackedIssue> cancelled =
        newest(
            carryingFingerprint.stream()
                .filter(i -> i.stateType() == IssueStateType.CANCELED)
                .toList());
    if (cancelled.isPresent()) {
      return new Outcome(FilingDecision.SUPPRESSED, cancelled.get());
    }
    Optional<TrackedIssue> completed = newest(carryingFingerprint);
    return completed
        .map(issue -> new Outcome(FilingDecision.FILED_REGRESSION, issue))
        .orElseGet(() -> new Outcome(FilingDecision.FILED_NEW, null));
  }

  private static Optional<TrackedIssue> newest(final List<TrackedIssue> issues) {
    return issues.stream().max(Comparator.comparing(TrackedIssue::createdAt));
  }

  /**
   * The decision and its subject.
   *
   * @param decision what to do
   * @param subject the issue the decision concerns; null only for {@link FilingDecision#FILED_NEW}
   */
  public record Outcome(FilingDecision decision, TrackedIssue subject) {
  }
}
