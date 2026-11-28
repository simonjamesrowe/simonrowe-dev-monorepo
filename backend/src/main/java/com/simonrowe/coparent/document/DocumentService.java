package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.AuditRecord;
import com.simonrowe.coparent.model.DocumentFile;
import com.simonrowe.coparent.model.DocumentNotice;
import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DocumentSource;
import com.simonrowe.coparent.model.DocumentVersion;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.AuditRepository;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.DocumentSchoolOfferRepository;
import com.simonrowe.coparent.persistence.FamilyDocumentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The family document library: adding, editing, new versions, archiving and restoring.
 *
 * <p>Every write reads the document, checks the caller was looking at its current
 * {@code version}, and replaces it guarded on that version, exactly as expenses do, so a
 * concurrent change always loses with a 409 rather than overwriting the other parent's. Either
 * active parent may do anything here, and a document can be added before the co-parent has
 * joined: nothing waits for the other parent's agreement.
 *
 * <p>Every change writes an audit record carrying the acting parent's id, and those records are
 * the drawer's history. Files are never deleted, archiving included.
 */
@Service
public class DocumentService {

  public static final int MAX_TITLE = 120;
  public static final int MAX_NOTE = 1000;
  static final String ENTITY = "document";
  private static final String STALE = "This document has changed. Reload it and try again.";

  private final FamilyDocumentRepository documents;
  private final DocumentSchoolOfferRepository offers;
  private final ChildRepository children;
  private final AuditRepository auditRecords;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final CoparentDocumentStore store;
  private final DocumentNoticeService notices;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  /**
   * What a parent sets on a document.
   *
   * @param childIds the children it is about; empty for the whole family
   * @param section its section
   * @param title its title
   * @param datedOn its issue or letter date
   * @param dueOn its expiry or review date, or null
   * @param dueKind what {@code dueOn} means
   * @param note an optional note
   */
  public record DocumentValues(
      List<ObjectId> childIds,
      DocumentSection section,
      String title,
      LocalDate datedOn,
      LocalDate dueOn,
      DueKind dueKind,
      String note
  ) {
  }

  /** The dates of a new version. */
  public record VersionDates(LocalDate datedOn, LocalDate dueOn, DueKind dueKind) {
  }

  /** One line of a document's history, oldest first. */
  public record HistoryItem(String action, ObjectId parentId, Instant at) {
  }

  /** A document with its history, for the drawer. */
  public record Detail(FamilyDocument document, List<HistoryItem> history) {
  }

  /**
   * A stored file and where it sits in its document. Equality reads the bytes and
   * {@code toString} gives only their length, so a document's contents never reach a log.
   */
  public record StoredFile(FamilyDocument document, DocumentFile file, int versionNumber,
      int page, int pages, byte[] bytes) {

    @Override
    public boolean equals(final Object other) {
      return other instanceof StoredFile that
          && versionNumber == that.versionNumber && page == that.page && pages == that.pages
          && Objects.equals(document, that.document) && Objects.equals(file, that.file)
          && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(document, file, versionNumber, page, pages)
          + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "StoredFile[file=%s, version=%d, page=%d/%d, bytes=<%d bytes>]".formatted(
          file, versionNumber, page, pages, bytes == null ? 0 : bytes.length);
    }
  }

  /** The audited actions, as they appear in a document's history. */
  public enum Action {
    CREATED, EDITED, NEW_VERSION, ARCHIVED, RESTORED
  }

  @Autowired
  public DocumentService(
      final FamilyDocumentRepository documents,
      final DocumentSchoolOfferRepository offers,
      final ChildRepository children,
      final AuditRepository auditRecords,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final CoparentDocumentStore store,
      final DocumentNoticeService notices,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(documents, offers, children, auditRecords, access, audits, store, notices,
        mongoTemplate, Clock.systemUTC());
  }

  DocumentService(
      final FamilyDocumentRepository documents,
      final DocumentSchoolOfferRepository offers,
      final ChildRepository children,
      final AuditRepository auditRecords,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final CoparentDocumentStore store,
      final DocumentNoticeService notices,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.documents = documents;
    this.offers = offers;
    this.children = children;
    this.auditRecords = auditRecords;
    this.access = access;
    this.audits = audits;
    this.store = store;
    this.notices = notices;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /** The family's documents, newest first: unarchived, or with {@code archived} only those. */
  public List<FamilyDocument> list(final ObjectId familyId, final boolean archived) {
    access.requireMember(familyId);
    return archived
        ? documents.findByFamilyIdAndArchivedAtIsNotNullOrderByDatedOnDescCreatedAtDesc(familyId)
        : documents.findByFamilyIdAndArchivedAtIsNullOrderByDatedOnDescCreatedAtDesc(familyId);
  }

  /** One document with its history. */
  public Detail get(final ObjectId familyId, final ObjectId documentId) {
    access.requireMember(familyId);
    final FamilyDocument document = find(familyId, documentId);
    return new Detail(document, history(document));
  }

  /** Adds a document a parent uploaded. */
  public FamilyDocument create(final ObjectId familyId, final DocumentValues values,
      final List<DocumentUploads.Upload> uploads, final ObjectId schoolOfferId) {
    return create(familyId, values, uploads, DocumentSource.Kind.UPLOAD, schoolOfferId);
  }

  /**
   * Adds a document with version 1. With {@code schoolOfferId}, the document fulfils a pending
   * "report is ready" offer: the offer is claimed first, atomically, so two parents uploading
   * the same report at once cannot both fulfil it, and released again if the save fails.
   */
  public FamilyDocument create(final ObjectId familyId, final DocumentValues values,
      final List<DocumentUploads.Upload> uploads, final DocumentSource.Kind kind,
      final ObjectId schoolOfferId) {
    return insert(familyId, values, uploads, kind, new ObjectId(), schoolOfferId,
        DocumentSchoolOffer.Kind.REPORT_READY);
  }

  /**
   * Adds a document from an approved Quick add card, under an id the card chose.
   *
   * <p>The id is the action's operation id, so an approval retried after a crash finds the
   * document its first attempt created rather than adding a second one: the caller looks it up
   * first, and an insert racing another one fails on the primary key.
   */
  public FamilyDocument createFromQuickAdd(final ObjectId familyId, final DocumentValues values,
      final List<DocumentUploads.Upload> uploads, final ObjectId documentId) {
    return insert(familyId, values, uploads, DocumentSource.Kind.QUICK_ADD,
        Objects.requireNonNull(documentId, "documentId"), null, null);
  }

  /**
   * Keeps a school letter offered under "From school" as a document. The offer is claimed
   * atomically before anything is written, so a second keep, by either parent, is a 409 and
   * never a second document; it is released again if the save fails.
   *
   * @param familyId the family
   * @param offerId the {@code LETTER} offer being kept
   * @param values what the parent chose: children, section, title, and the letter's date
   * @param uploads the letter's bytes, copied from Term Time's store
   * @return the new document, {@code source.kind = SCHOOL_LETTER}
   */
  public FamilyDocument keepSchoolLetter(final ObjectId familyId, final ObjectId offerId,
      final DocumentValues values, final List<DocumentUploads.Upload> uploads) {
    return insert(familyId, values, uploads, DocumentSource.Kind.SCHOOL_LETTER, new ObjectId(),
        Objects.requireNonNull(offerId, "offerId"), DocumentSchoolOffer.Kind.LETTER);
  }

  private FamilyDocument insert(final ObjectId familyId, final DocumentValues values,
      final List<DocumentUploads.Upload> uploads, final DocumentSource.Kind kind,
      final ObjectId documentId, final ObjectId schoolOfferId,
      final DocumentSchoolOffer.Kind offerKind) {
    final Parent actor = access.requireMember(familyId);
    final DocumentValues clean = validate(familyId, values);
    final List<DocumentUploads.Upload> files = DocumentUploads.checkUploads(uploads);
    final Instant now = clock.instant();
    DocumentSource source = new DocumentSource(kind, null);
    if (schoolOfferId != null) {
      final DocumentSchoolOffer offer = claimOffer(familyId, schoolOfferId, offerKind,
          documentId, actor, now);
      source = new DocumentSource(offerKind == DocumentSchoolOffer.Kind.LETTER
          ? DocumentSource.Kind.SCHOOL_LETTER : DocumentSource.Kind.SCHOOL_REPORT,
          offer.schoolDocumentId());
    }
    final List<DocumentFile> stored = new ArrayList<>();
    final FamilyDocument saved;
    try {
      stored.addAll(storeAll(familyId, files));
      final DocumentVersion first = new DocumentVersion(new ObjectId(), stored,
          clean.datedOn(), clean.dueOn(), clean.dueKind(), actor.id(), now);
      saved = mongoTemplate.insert(new FamilyDocument(documentId, familyId,
          clean.childIds(), clean.section(), clean.title(), clean.datedOn(), clean.dueOn(),
          clean.dueKind(), clean.note(), List.of(first), source, null, null, Set.of(),
          actor.id(), now, now, 1));
    } catch (RuntimeException failure) {
      // Only before the row exists: once it is saved, its files must never be discarded.
      stored.forEach(file -> store.discard(familyId, file.fileId()));
      if (schoolOfferId != null) {
        releaseOffer(schoolOfferId, documentId);
      }
      throw failure;
    }
    audit(saved, actor, Action.CREATED, Map.of("files", stored.size()));
    notices.record(familyId, actor, documentId, DocumentNotice.Change.ADDED);
    return saved;
  }

  /**
   * Changes a document's details. A change to the dates corrects the current version too, so
   * the document's dates go on mirroring its newest version.
   */
  public FamilyDocument edit(final ObjectId familyId, final ObjectId documentId,
      final long version, final DocumentValues values) {
    final Parent actor = access.requireMember(familyId);
    final DocumentValues clean = validate(familyId, values);
    final FamilyDocument current = current(familyId, documentId, version);
    requireNotArchived(current, "Restore this document before editing it");
    final Instant now = clock.instant();
    final List<DocumentVersion> versions = new ArrayList<>(current.versions());
    final DocumentVersion last = versions.removeLast();
    versions.add(new DocumentVersion(last.versionId(), last.files(), clean.datedOn(),
        clean.dueOn(), clean.dueKind(), last.uploadedBy(), last.uploadedAt()));
    final FamilyDocument saved = replace(current, new FamilyDocument(current.id(),
        current.familyId(), clean.childIds(), clean.section(), clean.title(), clean.datedOn(),
        clean.dueOn(), clean.dueKind(), clean.note(), versions, current.source(), null, null,
        current.remindersSent(), current.createdBy(), current.createdAt(), now,
        current.version() + 1));
    audit(saved, actor, Action.EDITED, Map.of());
    return saved;
  }

  /** Uploads a renewed or newer version, which becomes the current one. */
  public FamilyDocument addVersion(final ObjectId familyId, final ObjectId documentId,
      final long version, final VersionDates dates, final List<DocumentUploads.Upload> uploads) {
    return addVersion(familyId, documentId, version, dates, uploads, new ObjectId());
  }

  /**
   * Uploads a newer version under an id the caller chose. Quick add passes its operation id, so
   * a retried approval can see that its version is already there.
   */
  public FamilyDocument addVersion(final ObjectId familyId, final ObjectId documentId,
      final long version, final VersionDates dates, final List<DocumentUploads.Upload> uploads,
      final ObjectId versionId) {
    final Parent actor = access.requireMember(familyId);
    final VersionDates clean = validateDates(dates);
    final List<DocumentUploads.Upload> files = DocumentUploads.checkUploads(uploads);
    final FamilyDocument current = current(familyId, documentId, version);
    requireNotArchived(current, "Restore this document before adding a version");
    final Instant now = clock.instant();
    final List<DocumentFile> stored = storeAll(familyId, files);
    final List<DocumentVersion> versions = new ArrayList<>(current.versions());
    versions.add(new DocumentVersion(Objects.requireNonNull(versionId, "versionId"), stored,
        clean.datedOn(), clean.dueOn(), clean.dueKind(), actor.id(), now));
    final FamilyDocument saved;
    try {
      saved = replace(current, new FamilyDocument(current.id(), current.familyId(),
          current.childIds(), current.section(), current.title(), clean.datedOn(),
          clean.dueOn(), clean.dueKind(), current.note(), versions, current.source(), null, null,
          current.remindersSent(), current.createdBy(), current.createdAt(), now,
          current.version() + 1));
    } catch (RuntimeException failure) {
      stored.forEach(file -> store.discard(familyId, file.fileId()));
      throw failure;
    }
    audit(saved, actor, Action.NEW_VERSION, Map.of("files", stored.size()));
    notices.record(familyId, actor, documentId, DocumentNotice.Change.NEW_VERSION);
    return saved;
  }

  /** Hides a document from the library. Either parent may; nothing is deleted. */
  public FamilyDocument archive(final ObjectId familyId, final ObjectId documentId,
      final long version) {
    final Parent actor = access.requireMember(familyId);
    final FamilyDocument current = current(familyId, documentId, version);
    requireNotArchived(current, "This document is already archived");
    final Instant now = clock.instant();
    final FamilyDocument saved = replace(current, withArchive(current, now, actor.id(), now));
    audit(saved, actor, Action.ARCHIVED, Map.of());
    notices.record(familyId, actor, documentId, DocumentNotice.Change.ARCHIVED);
    return saved;
  }

  /** Puts an archived document back. Either parent may, whoever archived it. */
  public FamilyDocument restore(final ObjectId familyId, final ObjectId documentId,
      final long version) {
    final Parent actor = access.requireMember(familyId);
    final FamilyDocument current = current(familyId, documentId, version);
    if (current.archivedAt() == null) {
      throw conflict("This document is not archived");
    }
    final FamilyDocument saved = replace(current, withArchive(current, null, null,
        clock.instant()));
    audit(saved, actor, Action.RESTORED, Map.of());
    notices.record(familyId, actor, documentId, DocumentNotice.Change.RESTORED);
    return saved;
  }

  /** Reads one of a document's files, from any version, archived documents included. */
  public StoredFile readFile(final ObjectId familyId, final ObjectId documentId,
      final ObjectId fileId) {
    access.requireMember(familyId);
    final FamilyDocument document = find(familyId, documentId);
    for (int v = 0; v < document.versions().size(); v++) {
      final List<DocumentFile> files = document.versions().get(v).files();
      for (int index = 0; index < files.size(); index++) {
        final DocumentFile file = files.get(index);
        if (file.fileId().equals(fileId)) {
          final int versionNumber = v + 1;
          final int page = index + 1;
          return store.read(familyId, fileId)
              .map(bytes -> new StoredFile(document, file, versionNumber, page, files.size(),
                  bytes))
              .orElseThrow(() -> notFound("File not found"));
        }
      }
    }
    throw notFound("File not found");
  }

  // ---- validation --------------------------------------------------------------------------

  private DocumentValues validate(final ObjectId familyId, final DocumentValues values) {
    if (values == null) {
      throw badRequest("The document's details are required");
    }
    final String title = values.title() == null ? "" : values.title().strip();
    if (title.isEmpty() || title.length() > MAX_TITLE) {
      throw badRequest("Give the document a title of up to " + MAX_TITLE + " characters");
    }
    if (values.section() == null) {
      throw badRequest("Choose a section");
    }
    final String note = values.note() == null || values.note().isBlank()
        ? null : values.note().strip();
    if (note != null && note.length() > MAX_NOTE) {
      throw badRequest("A note can be up to " + MAX_NOTE + " characters");
    }
    final List<ObjectId> childIds = values.childIds() == null ? List.of()
        : values.childIds().stream().filter(Objects::nonNull).distinct().toList();
    if (!childIds.isEmpty()
        && children.countByIdInAndFamilyIdAndDeletedAtIsNull(childIds, familyId)
        != childIds.size()) {
      throw badRequest("Choose children from this family");
    }
    final VersionDates dates = validateDates(new VersionDates(values.datedOn(), values.dueOn(),
        values.dueKind()));
    return new DocumentValues(childIds, values.section(), title, dates.datedOn(),
        dates.dueOn(), dates.dueKind(), note);
  }

  private static VersionDates validateDates(final VersionDates dates) {
    if (dates == null || dates.datedOn() == null) {
      throw badRequest("Say when the document was issued or dated");
    }
    if ((dates.dueOn() == null) != (dates.dueKind() == null)) {
      throw badRequest("An expiry or review date needs both the date and what it means");
    }
    return dates;
  }

  // ---- school offers -----------------------------------------------------------------------

  private DocumentSchoolOffer claimOffer(final ObjectId familyId, final ObjectId offerId,
      final DocumentSchoolOffer.Kind expected, final ObjectId documentId, final Parent actor,
      final Instant now) {
    final DocumentSchoolOffer offer = offers.findByIdAndFamilyId(offerId, familyId)
        .orElseThrow(() -> notFound("School offer not found"));
    if (offer.kind() != expected) {
      throw conflict(expected == DocumentSchoolOffer.Kind.REPORT_READY
          ? "Keep a school letter from From school instead"
          : "Add this report from the report prompt instead");
    }
    final DocumentSchoolOffer claimed = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(offerId).and("familyId").is(familyId)
            .and("status").is(DocumentSchoolOffer.Status.PENDING)
            .and("kind").is(expected)),
        new Update().set("status", DocumentSchoolOffer.Status.KEPT)
            .set("documentId", documentId).set("decidedBy", actor.id())
            .set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), DocumentSchoolOffer.class);
    if (claimed == null) {
      throw conflict("This school offer has already been dealt with");
    }
    return claimed;
  }

  private void releaseOffer(final ObjectId offerId, final ObjectId documentId) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(offerId).and("documentId").is(documentId)),
        new Update().set("status", DocumentSchoolOffer.Status.PENDING).unset("documentId")
            .unset("decidedBy").unset("decidedAt"),
        DocumentSchoolOffer.class);
  }

  // ---- persistence -------------------------------------------------------------------------

  private List<DocumentFile> storeAll(final ObjectId familyId,
      final List<DocumentUploads.Upload> uploads) {
    final List<DocumentFile> stored = new ArrayList<>(uploads.size());
    try {
      for (final DocumentUploads.Upload upload : uploads) {
        final ObjectId fileId = new ObjectId();
        store.store(familyId, fileId, upload.bytes());
        stored.add(new DocumentFile(fileId, upload.mimeType(), upload.bytes().length));
      }
    } catch (RuntimeException failure) {
      stored.forEach(file -> store.discard(familyId, file.fileId()));
      throw failure;
    }
    return stored;
  }

  private FamilyDocument find(final ObjectId familyId, final ObjectId documentId) {
    return documents.findByIdAndFamilyId(documentId, familyId)
        .orElseThrow(() -> notFound("Document not found"));
  }

  private FamilyDocument current(final ObjectId familyId, final ObjectId documentId,
      final long version) {
    final FamilyDocument current = find(familyId, documentId);
    if (current.version() != version) {
      throw conflict(STALE);
    }
    return current;
  }

  /** Writes {@code next} only if nobody else has written since {@code current} was read. */
  private FamilyDocument replace(final FamilyDocument current, final FamilyDocument next) {
    final FamilyDocument saved = mongoTemplate.findAndReplace(
        Query.query(Criteria.where("_id").is(current.id())
            .and("familyId").is(current.familyId())
            .and("version").is(current.version())),
        next, FindAndReplaceOptions.options().returnNew());
    if (saved == null) {
      throw conflict(STALE);
    }
    return saved;
  }

  private List<HistoryItem> history(final FamilyDocument document) {
    final Set<String> actions = Arrays.stream(Action.values()).map(Enum::name)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return auditRecords.findByFamilyIdAndEntityTypeAndEntityIdOrderByTimestampAsc(
            document.familyId(), ENTITY, document.id().toHexString()).stream()
        .filter(record -> actions.contains(record.action()))
        .map(DocumentService::historyItem)
        .toList();
  }

  private static HistoryItem historyItem(final AuditRecord record) {
    final Object parent = record.changes().get("parentId");
    final ObjectId parentId = parent instanceof String hex && ObjectId.isValid(hex)
        ? new ObjectId(hex) : null;
    return new HistoryItem(record.action(), parentId, record.timestamp());
  }

  private void audit(final FamilyDocument document, final Parent actor, final Action action,
      final Map<String, Object> extra) {
    // The acting parent and the section only: no title or note, which can be personal. The
    // parent id is what the drawer's history names; the audit's own subject is an Auth0 id.
    final Map<String, Object> changes = new java.util.LinkedHashMap<>(extra);
    changes.put("parentId", actor.id().toHexString());
    changes.put("section", document.section().name());
    audits.record(document.familyId(), ENTITY, document.id(), action.name(), changes);
  }

  private static FamilyDocument withArchive(final FamilyDocument current,
      final Instant archivedAt, final ObjectId archivedBy, final Instant now) {
    return new FamilyDocument(current.id(), current.familyId(), current.childIds(),
        current.section(), current.title(), current.datedOn(), current.dueOn(),
        current.dueKind(), current.note(), current.versions(), current.source(), archivedAt,
        archivedBy, current.remindersSent(), current.createdBy(), current.createdAt(), now,
        current.version() + 1);
  }

  private static void requireNotArchived(final FamilyDocument document, final String message) {
    if (document.archivedAt() != null) {
      throw conflict(message);
    }
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(final String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(final String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
