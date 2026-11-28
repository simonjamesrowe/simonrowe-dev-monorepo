package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hands a family's upload to {@link DocumentReader} with only what it needs to know: the
 * children's ids and first names, and today's date. Stores nothing.
 */
@Service
public class DocumentReadService {

  private final DocumentReader reader;
  private final ChildRepository children;
  private final CoparentAccessPolicy access;
  private final Clock clock;

  @Autowired
  public DocumentReadService(final DocumentReader reader, final ChildRepository children,
      final CoparentAccessPolicy access) {
    this(reader, children, access, Clock.systemUTC());
  }

  DocumentReadService(final DocumentReader reader, final ChildRepository children,
      final CoparentAccessPolicy access, final Clock clock) {
    this.reader = reader;
    this.children = children;
    this.access = access;
    this.clock = clock;
  }

  /** Reads the files into a suggestion for the add form. */
  public DocumentReader.Proposal read(final ObjectId familyId,
      final List<DocumentUploads.Upload> uploads) {
    access.requireMember(familyId);
    if (!reader.available()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Reading documents is not switched on. Fill in the details yourself.");
    }
    final List<DocumentUploads.Upload> files = DocumentUploads.checkUploads(uploads);
    final List<DocumentReader.Child> known = children.findByFamilyIdAndDeletedAtIsNull(familyId)
        .stream().map(child -> new DocumentReader.Child(child.id().toHexString(),
            firstName(child))).toList();
    return reader.read(LocalDate.now(clock.withZone(DocumentReminderService.LONDON)), known,
        files);
  }

  private static String firstName(final Child child) {
    final String name = child.fullName() == null ? "" : child.fullName().trim();
    final int space = name.indexOf(' ');
    return space < 0 ? name : name.substring(0, space);
  }
}
