package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A school letter, or a "report is ready" prompt, offered to a family under "From school", and
 * what the family decided (spec 057 §4).
 *
 * <p>Also the ledger: a unique index on {@code (familyId, schoolDocumentId)} keeps every school
 * document in at most one offer per family, whatever was decided, which is what stops a
 * dismissed letter being offered again.
 *
 * <p><b>A {@link Status#DISMISSED} row with no {@code decidedBy} was never shown to anyone.</b>
 * It records that the letter classifier judged a broadcast letter not worth keeping (a menu, a
 * booking guide), or that a report email did not come from the reporting system, so the same
 * letter is not sent to the model again on every sync. {@code reason} says why.
 *
 * @param id the offer's id
 * @param familyId the family it is for
 * @param schoolDocumentId the Term Time {@code SchoolDocument} it stands for
 * @param kind a letter to keep, or a prompt to upload a report from elsewhere
 * @param childIds the children it was matched to
 * @param section the section it would be kept in
 * @param title its title
 * @param sentAt when the school sent it
 * @param personal whether it was addressed to this family rather than broadcast
 * @param reportLink for {@link Kind#REPORT_READY}, where the report can be read; null otherwise
 * @param status where it is
 * @param documentId the document it became, once kept
 * @param decidedBy the parent who kept or dismissed it
 * @param decidedAt when
 * @param createdAt when it was first offered
 * @param updatedAt when it last changed
 * @param sender who sent it, as the family would name them
 * @param reason why it was matched to these children, or, for a row nobody was shown, why it
 *     was not offered
 * @param attachmentName for {@link Kind#LETTER}, the attachment's file name; null otherwise
 * @param suggestedTitle for {@link Kind#REPORT_READY}, the title the add drawer is prefilled with
 * @param suggestedDatedOn for {@link Kind#REPORT_READY}, the date the add drawer is prefilled with
 */
@Document(DocumentSchoolOffer.COLLECTION)
public record DocumentSchoolOffer(
    @Id ObjectId id,
    ObjectId familyId,
    String schoolDocumentId,
    Kind kind,
    List<ObjectId> childIds,
    DocumentSection section,
    String title,
    Instant sentAt,
    boolean personal,
    String reportLink,
    Status status,
    ObjectId documentId,
    ObjectId decidedBy,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt,
    String sender,
    String reason,
    String attachmentName,
    String suggestedTitle,
    LocalDate suggestedDatedOn
) {
  public static final String COLLECTION = "document_school_offers";

  public DocumentSchoolOffer {
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
  }

  /** What is being offered. */
  public enum Kind {
    /** A letter whose attachment can be kept as a document. */
    LETTER,
    /** A report is ready elsewhere; a parent downloads it and adds it here. */
    REPORT_READY
  }

  /** Where an offer is. */
  public enum Status {
    /** Waiting for a parent. */
    PENDING,
    /** Kept as a document. */
    KEPT,
    /** A parent said it is not needed; never offered again. */
    DISMISSED
  }
}
