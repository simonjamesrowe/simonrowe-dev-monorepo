package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;

/**
 * One upload of a document. A renewed passport is a new version of the same document, so the
 * old one stays downloadable and the reminders follow the new dates.
 *
 * @param versionId the version's id
 * @param files its files in order; several photos of one document are its pages
 * @param datedOn the issue or letter date of this version
 * @param dueOn its expiry or review date, or null
 * @param dueKind what {@code dueOn} means, set exactly when it is
 * @param uploadedBy the parent who uploaded it
 * @param uploadedAt when
 */
public record DocumentVersion(
    ObjectId versionId,
    List<DocumentFile> files,
    LocalDate datedOn,
    LocalDate dueOn,
    DueKind dueKind,
    ObjectId uploadedBy,
    Instant uploadedAt
) {
  public DocumentVersion {
    files = files == null ? List.of() : List.copyOf(files);
  }
}
