package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The email waiting to tell one parent about document changes, batched so that adding six
 * documents in a sitting sends one email rather than six (spec 057 §2.2). Unique per
 * {@code (familyId, recipientParentId)}; deleted once sent.
 *
 * @param id the notice's id
 * @param familyId the family
 * @param recipientParentId the parent it will be sent to; never the parent who made the change
 * @param items the changes, in the order they happened
 * @param firstAt when the first change in this batch happened
 * @param sendAfter when it may be sent: fifteen minutes after the latest change, but never later
 *     than two hours after the first
 */
@Document(DocumentNotice.COLLECTION)
public record DocumentNotice(
    @Id ObjectId id,
    ObjectId familyId,
    ObjectId recipientParentId,
    List<Item> items,
    Instant firstAt,
    Instant sendAfter
) {
  public static final String COLLECTION = "document_notices";

  public DocumentNotice {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /** What happened to a document. Edits to the details are not announced. */
  public enum Change {
    ADDED,
    NEW_VERSION,
    ARCHIVED,
    RESTORED
  }

  /**
   * One change.
   *
   * @param documentId the document
   * @param change what happened
   * @param byParentId who did it
   * @param at when
   */
  public record Item(ObjectId documentId, Change change, ObjectId byParentId, Instant at) {
  }
}
