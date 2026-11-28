package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One of the children's important papers, shared by the family (spec 057). Named
 * {@code FamilyDocument} because {@code Document} is Spring Data's mapping annotation.
 *
 * <p>The document's own {@link #datedOn}, {@link #dueOn} and {@link #dueKind} always mirror the
 * newest of its {@link #versions}, so the reminders follow a renewal without anyone editing the
 * old version. {@link #version} increases on every write and every change is guarded on it,
 * exactly as expenses are, so a concurrent edit loses with a 409 instead of overwriting.
 *
 * <p><b>No identifying numbers.</b> There is deliberately no field for a passport, NHS or any
 * other number; they stay inside the file.
 *
 * @param id the document's id
 * @param familyId the family it belongs to; every query is scoped to it
 * @param childIds the children it is about; empty means the whole family
 * @param section which shelf it is on
 * @param title its title, 1 to 120 characters
 * @param datedOn the newest version's issue or letter date
 * @param dueOn the newest version's expiry or review date, or null
 * @param dueKind what {@code dueOn} means, set exactly when it is
 * @param note an optional note, up to 1,000 characters
 * @param versions every upload, oldest first, never empty
 * @param source where it came from
 * @param archivedAt when it was archived, or null; archiving hides it and deletes nothing
 * @param archivedBy the parent who archived it
 * @param remindersSent {@code <dueOn>:<threshold>} keys of the reminders already sent, so a
 *     reminder is never sent twice for one date
 * @param createdBy the parent who added it
 * @param createdAt when
 * @param updatedAt when it last changed
 * @param version the optimistic concurrency guard
 */
@Document(FamilyDocument.COLLECTION)
public record FamilyDocument(
    @Id ObjectId id,
    ObjectId familyId,
    List<ObjectId> childIds,
    DocumentSection section,
    String title,
    LocalDate datedOn,
    LocalDate dueOn,
    DueKind dueKind,
    String note,
    List<DocumentVersion> versions,
    DocumentSource source,
    Instant archivedAt,
    ObjectId archivedBy,
    Set<String> remindersSent,
    ObjectId createdBy,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
  public static final String COLLECTION = "documents";

  public FamilyDocument {
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
    versions = versions == null ? List.of() : List.copyOf(versions);
    remindersSent = remindersSent == null ? Set.of() : Set.copyOf(remindersSent);
    source = source == null ? DocumentSource.upload() : source;
  }

  /** The newest version, which the document's dates mirror. */
  public DocumentVersion currentVersion() {
    return versions.isEmpty() ? null : versions.getLast();
  }
}
