package com.simonrowe.school.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One ingested item of school content, from any source.
 *
 * <p>Two fields carry the whole tiering design and must not be collapsed into one.
 * {@code visibility} is what access control reads; {@code proposedVisibility} is what a classifier
 * suggested. A classifier writes only the latter. Nothing but an explicit human approval ever
 * writes {@link Visibility#PUBLIC} into the former — see {@link #withApproval}.
 *
 * <p>{@code body} is retained even for restricted items, which is persistence beyond any hot read
 * path. It is justified by reclassification: changing the classifier or the staff-name list
 * otherwise requires re-reading the mailbox, and Gmail guarantees no retention window for its
 * incremental cursor, so that re-read is not reliably available.
 *
 * @param id {@code sha256(sourceType + ':' + sourceRef)}, so re-ingesting updates in place
 * @param sourceType where this came from; also decides precedence in a conflict
 * @param sourceRef the URL, or the mail message id
 * @param title human-readable title, used in citations
 * @param body extracted plain text
 * @param publishedAt when the source published it — never the ingest time, because citations and
 *     recency both need the real date
 * @param ingestedAt when this system first saw it
 * @param visibility the only value access control reads
 * @param proposedVisibility what a classifier suggested, or null. Never read by access control
 * @param proposalReason why the classifier suggested it, shown in the approval queue
 * @param approvedBy who approved promotion to public, or null
 * @param approvedAt when promotion was approved, or null
 * @param nameGateBlocked true when a non-staff personal name forced this back to restricted
 * @param yearGroups inferred year groups; a soft retrieval hint only, never a hard filter
 * @param contentHash lets an unchanged re-ingest skip re-embedding
 */
@Document(collection = "school_documents")
public record SchoolDocument(
    @Id String id,
    SchoolSourceType sourceType,
    String sourceRef,
    String title,
    String body,
    Instant publishedAt,
    Instant ingestedAt,
    Visibility visibility,
    Visibility proposedVisibility,
    String proposalReason,
    String approvedBy,
    Instant approvedAt,
    boolean nameGateBlocked,
    List<String> yearGroups,
    String contentHash,
    Instant declinedAt
) {

  /**
   * Normalises the two things that must never be left to a caller: a null {@code visibility}
   * becomes {@link Visibility#RESTRICTED}, and a null {@code yearGroups} becomes an empty list.
   *
   * <p>The visibility default is the fail-closed guarantee in FR-009. A constructor that let null
   * through would make "we forgot to set it" indistinguishable from "nobody has decided yet", and
   * any reader treating null as "not restricted" would publish silently.
   */
  public SchoolDocument {
    visibility = visibility == null ? Visibility.RESTRICTED : visibility;
    yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
  }

  /**
   * Returns a copy carrying a corrected publication date.
   *
   * <p>The date is derived separately from the body — from a letterhead, a CMS feed or the fetch
   * time — so it can be wrong while the text is right, and it improves as the derivation does.
   * Every human decision is carried forward untouched: re-dating a document is a correction to
   * metadata, never a reason to re-open an approval.
   *
   * @param corrected the date to record
   * @return the updated copy
   */
  public SchoolDocument withPublishedAt(final java.time.Instant corrected) {
    return new SchoolDocument(id, sourceType, sourceRef, title, body, corrected, ingestedAt,
        visibility, proposedVisibility, proposalReason, approvedBy, approvedAt, nameGateBlocked,
        yearGroups, contentHash, declinedAt);
  }

  /**
   * Returns a copy carrying a classifier's proposal. Deliberately cannot alter {@code visibility}.
   *
   * @param proposed what the classifier suggests this could be
   * @param reason why, for the approval queue
   * @return a copy with the proposal recorded and the effective visibility untouched
   */
  public SchoolDocument withProposal(final Visibility proposed, final String reason) {
    return new SchoolDocument(id, sourceType, sourceRef, title, body, publishedAt, ingestedAt,
        visibility, proposed, reason, approvedBy, approvedAt, nameGateBlocked, yearGroups,
        contentHash, declinedAt);
  }

  /**
   * Returns a copy promoted to public by a named human.
   *
   * <p>The name gate blocks this <b>unless {@code force} is set</b>. That is a deliberate change
   * from the original design, where the gate could not be overridden at all. The reasoning then
   * was that approval might be a rubber stamp and the gate was the last line before publication.
   * In practice the gate's precision is far too low for that to work: it cannot tell a caterer
   * ("Taylor Shaw") or an office administrator from a pupil, and it blocked four fifths of the
   * school's general-purpose broadcasts. A gate that blocks everything is not consulted, it is
   * worked around.
   *
   * <p>So it is now advice with teeth: blocked items are surfaced with the reason and require a
   * separate, explicit action to publish. The unattended paths — the classifier, and the
   * output-side check on anonymous answers — still treat it as absolute, because those have no
   * human reading the text.
   *
   * @param approver who approved it
   * @param at when
   * @param force true to publish despite a name-gate block
   * @return a copy that is public, or an unchanged copy if blocked and not forced
   */
  public SchoolDocument withApproval(
      final String approver, final Instant at, final boolean force) {
    if (nameGateBlocked && !force) {
      return this;
    }
    return new SchoolDocument(id, sourceType, sourceRef, title, body, publishedAt, ingestedAt,
        Visibility.PUBLIC, proposedVisibility, proposalReason, approver, at, false, yearGroups,
        contentHash, declinedAt);
  }

  /**
   * Whether this document still needs a human decision.
   *
   * <p>Anything restricted that nobody has ruled on — <b>whatever the classifier proposed</b>.
   * Keying the queue on the proposal instead left it permanently empty and the approval step
   * invisible: the classifier proposes RESTRICTED for very nearly everything, which is correct
   * behaviour for a conservative classifier and useless as a queue filter. This method carried
   * that reasoning in its javadoc for some time while its body still tested the proposal, and
   * nothing called it at all.
   *
   * <p>"Ruled on" means approved or declined, which is why {@code declinedAt} exists as a field
   * rather than being inferred from the proposal: declining used to be recorded by writing a
   * RESTRICTED proposal, and once the proposal no longer decides the queue there is nothing left
   * to distinguish "someone said no" from "nobody has looked".
   *
   * @return true when restricted, unapproved and not yet declined
   */
  public boolean needsDecision() {
    return visibility == Visibility.RESTRICTED
        && approvedAt == null
        && declinedAt == null;
  }

  /**
   * Returns a copy recorded as declined, so it leaves the queue for good.
   *
   * @param at when the decision was taken
   * @return the updated copy
   */
  public SchoolDocument withDeclined(final Instant at) {
    return new SchoolDocument(id, sourceType, sourceRef, title, body, publishedAt, ingestedAt,
        Visibility.RESTRICTED, proposedVisibility, "declined", approvedBy, approvedAt,
        nameGateBlocked, yearGroups, contentHash, at);
  }

  /**
   * Returns a copy demoted back to restricted, clearing the approval.
   *
   * @return a restricted copy with no approver recorded
   */
  public SchoolDocument withApprovalRevoked() {
    return new SchoolDocument(id, sourceType, sourceRef, title, body, publishedAt, ingestedAt,
        Visibility.RESTRICTED, proposedVisibility, proposalReason, null, null, nameGateBlocked,
        yearGroups, contentHash, declinedAt);
  }

}
