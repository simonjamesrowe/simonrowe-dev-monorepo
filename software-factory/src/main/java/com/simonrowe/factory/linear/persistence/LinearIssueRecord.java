package com.simonrowe.factory.linear.persistence;

import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The audit trail for one problem, keyed by its fingerprint.
 *
 * <p><strong>Linear is truth; this is the trail.</strong> Identity and state are always read back
 * from Linear, so closing or deleting a ticket by hand cannot leave the factory believing it is
 * still open. This document exists to answer "what has the sink filed, and did it dedup
 * correctly?" outside Temporal's retention window.
 *
 * <p>{@code attachmentPending} closes <strong>one</strong> of this sink's duplicate-ticket
 * risks, not all of them: the window where an {@code issueCreate} succeeds and the
 * {@code attachmentCreate} after it fails. Without the flag a retry finds no attachment and files
 * a second ticket; with it, a retry landing in that gap repairs by attaching. The other two are
 * closed elsewhere, and this Javadoc is the wrong place to look for them — a retry after a
 * <em>fully</em> successful filing is caught by {@link #hasOccurrence} against the
 * caller-supplied occurrence id, and a filing attempt outliving its activity
 * {@code startToCloseTimeout} while Temporal starts a second one is held off by keeping
 * {@code factory.linear.request-timeout} small enough that the worst-case four sequential Linear
 * calls still fit inside that timeout.
 */
@Document(collection = "linear_issues")
public record LinearIssueRecord(
    @Id String id,
    String producer,
    String fingerprintVersion,
    List<String> keyParts,
    String issueId,
    String issueIdentifier,
    String issueUrl,
    boolean attachmentPending,
    Instant firstFiledAt,
    Instant lastSeenAt,
    int occurrences,
    IssueStateType lastKnownStateType,
    List<LinearIssueDecision> decisions) {

  /**
   * How many decisions are retained.
   *
   * <p>Capped because an unbounded array in a Mongo document grows without limit. The accepted
   * cost is that a replay older than this window could post one duplicate comment — far wider
   * than any activity retry, and a benign failure.
   */
  public static final int MAX_DECISIONS = 20;

  public LinearIssueRecord {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
    decisions = decisions == null ? List.of() : List.copyOf(decisions);
  }

  /**
   * The record for a problem seen for the first time.
   *
   * @param fingerprint the problem's fingerprint, which is also the document id
   * @param producer the producer key
   * @param keyParts the structured parts the fingerprint was computed from, kept for readability
   * @param seenAt when this occurrence arrived
   * @return a record with no issue yet and no occurrence counted — {@link #withDecision} counts
   *     the first one
   */
  public static LinearIssueRecord first(
      final String fingerprint,
      final String producer,
      final List<String> keyParts,
      final Instant seenAt) {
    return new LinearIssueRecord(
        fingerprint,
        producer,
        Fingerprint.VERSION,
        keyParts,
        null,
        null,
        null,
        false,
        seenAt,
        seenAt,
        0,
        null,
        List.of());
  }

  /**
   * Whether this occurrence has already been handled — an activity replay rather than a new
   * event.
   *
   * @param occurrenceId the producing run id, may be null
   * @return true when the id appears in the retained decision log against a real, non-dry-run
   *     decision — a dry run audits what would have happened, not what did, so it must never
   *     stand in for a real retry of the same occurrence
   */
  public boolean hasOccurrence(final String occurrenceId) {
    if (occurrenceId == null) {
      return false;
    }
    return decisions.stream()
        .anyMatch(d -> occurrenceId.equals(d.occurrenceId()) && !d.dryRun());
  }

  /**
   * Appends a decision, advancing the counters and truncating the log to {@link #MAX_DECISIONS}.
   *
   * @param decision the decision taken
   * @param seenAt when this occurrence arrived
   * @param stateType the state Linear reported, or null when nothing was found
   * @return a new record; this type is immutable
   */
  public LinearIssueRecord withDecision(
      final LinearIssueDecision decision, final Instant seenAt, final IssueStateType stateType) {
    List<LinearIssueDecision> appended = new ArrayList<>(decisions);
    appended.add(decision);
    if (appended.size() > MAX_DECISIONS) {
      appended =
          new ArrayList<>(appended.subList(appended.size() - MAX_DECISIONS, appended.size()));
    }
    return new LinearIssueRecord(
        id,
        producer,
        fingerprintVersion,
        keyParts,
        issueId,
        issueIdentifier,
        issueUrl,
        attachmentPending,
        firstFiledAt,
        seenAt,
        occurrences + 1,
        stateType,
        appended);
  }

  /**
   * Records the issue this problem now points at, with its attachment not yet written.
   *
   * @param newIssueId the Linear issue UUID
   * @param newIssueIdentifier the human identifier, e.g. {@code SIM-42}
   * @param newIssueUrl the issue's web URL
   * @return a new record with {@code attachmentPending} set
   */
  public LinearIssueRecord withPendingAttachment(
      final String newIssueId, final String newIssueIdentifier, final String newIssueUrl) {
    return new LinearIssueRecord(
        id, producer, fingerprintVersion, keyParts,
        newIssueId, newIssueIdentifier, newIssueUrl, true,
        firstFiledAt, lastSeenAt, occurrences, lastKnownStateType, decisions);
  }

  /**
   * Points this record at an issue that is already known to carry the fingerprint — found by
   * lookup rather than just created — leaving {@code attachmentPending} false.
   *
   * @param newIssueId the Linear issue UUID
   * @param newIssueIdentifier the human identifier, e.g. {@code SIM-42}
   * @param newIssueUrl the issue's web URL
   * @return a new record pointing at the issue
   */
  public LinearIssueRecord withIssue(
      final String newIssueId, final String newIssueIdentifier, final String newIssueUrl) {
    return new LinearIssueRecord(
        id, producer, fingerprintVersion, keyParts,
        newIssueId, newIssueIdentifier, newIssueUrl, false,
        firstFiledAt, lastSeenAt, occurrences, lastKnownStateType, decisions);
  }

  /**
   * Clears the pending flag once the fingerprint attachment is written.
   *
   * @return a new record with {@code attachmentPending} cleared
   */
  public LinearIssueRecord withAttachmentWritten() {
    return new LinearIssueRecord(
        id, producer, fingerprintVersion, keyParts,
        issueId, issueIdentifier, issueUrl, false,
        firstFiledAt, lastSeenAt, occurrences, lastKnownStateType, decisions);
  }
}
