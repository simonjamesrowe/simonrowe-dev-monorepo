package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.DocumentSchoolOfferRepository;
import com.simonrowe.coparent.school.SchoolCalendarProperties;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "From school": the letters and report prompts waiting for a family's decision (spec 057 §4).
 *
 * <p>Keeping a letter copies its bytes out of Term Time's attachment store into the family's own
 * document store, so Term Time's retention, re-ingest or a lost volume never touches the family's
 * copy. It only reads Term Time; it never changes a school document's tier or approval.
 */
@Service
public class DocumentSchoolOfferService {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final String GONE = "The school's copy of this letter is no longer available";

  private final DocumentSchoolOfferRepository offers;
  private final DocumentService documents;
  private final CoparentAccessPolicy access;
  private final SchoolDocumentRepository schoolDocuments;
  private final SchoolAttachmentStore attachments;
  private final SchoolCalendarProperties schoolCalendar;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  /** What a parent chose when keeping a letter. */
  public record KeepValues(List<ObjectId> childIds, DocumentSection section, String title) {
  }

  @Autowired
  public DocumentSchoolOfferService(
      final DocumentSchoolOfferRepository offers,
      final DocumentService documents,
      final CoparentAccessPolicy access,
      final SchoolDocumentRepository schoolDocuments,
      final SchoolAttachmentStore attachments,
      final SchoolCalendarProperties schoolCalendar,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(offers, documents, access, schoolDocuments, attachments, schoolCalendar, mongoTemplate,
        Clock.systemUTC());
  }

  DocumentSchoolOfferService(
      final DocumentSchoolOfferRepository offers,
      final DocumentService documents,
      final CoparentAccessPolicy access,
      final SchoolDocumentRepository schoolDocuments,
      final SchoolAttachmentStore attachments,
      final SchoolCalendarProperties schoolCalendar,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.offers = offers;
    this.documents = documents;
    this.access = access;
    this.schoolDocuments = schoolDocuments;
    this.attachments = attachments;
    this.schoolCalendar = schoolCalendar;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /** The family's offers waiting for a decision, newest first. */
  public List<DocumentSchoolOffer> pending(final ObjectId familyId) {
    access.requireMember(familyId);
    return offers.findByFamilyIdAndStatusOrderBySentAtDesc(familyId,
        DocumentSchoolOffer.Status.PENDING);
  }

  /**
   * Keeps a letter as one document, for every child the parent chose. A report prompt cannot be
   * kept here (409): there is nothing to copy, the parent uploads the report instead.
   */
  public FamilyDocument keep(final ObjectId familyId, final ObjectId offerId,
      final KeepValues values) {
    access.requireMember(familyId);
    final DocumentSchoolOffer offer = find(familyId, offerId);
    if (offer.kind() != DocumentSchoolOffer.Kind.LETTER) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Add this report from the report prompt instead");
    }
    if (offer.status() != DocumentSchoolOffer.Status.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This school offer has already been dealt with");
    }
    final SchoolDocument letter = schoolDocuments.findById(offer.schoolDocumentId())
        .filter(document -> stillVisibleTo(familyId, offer, document))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GONE));
    final byte[] bytes = attachments.read(letter.id())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GONE));
    final KeepValues chosen = values == null ? new KeepValues(null, null, null) : values;
    final LocalDate datedOn = offer.sentAt() == null
        ? LocalDate.now(clock.withZone(LONDON)) : LocalDate.ofInstant(offer.sentAt(), LONDON);
    return documents.keepSchoolLetter(familyId, offerId,
        new DocumentService.DocumentValues(chosen.childIds(), chosen.section(), chosen.title(),
            datedOn, null, null, null),
        List.of(new DocumentUploads.Upload(null, bytes)));
  }

  /** Marks an offer not needed. It is never offered again: the ledger row stays. */
  public void dismiss(final ObjectId familyId, final ObjectId offerId) {
    final Parent actor = access.requireMember(familyId);
    final Instant now = clock.instant();
    final DocumentSchoolOffer dismissed = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(offerId).and("familyId").is(familyId)
            .and("status").is(DocumentSchoolOffer.Status.PENDING)),
        new Update().set("status", DocumentSchoolOffer.Status.DISMISSED)
            .set("decidedBy", actor.id()).set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), DocumentSchoolOffer.class);
    if (dismissed == null) {
      find(familyId, offerId);
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This school offer has already been dealt with");
    }
  }

  /**
   * Whether the family may still have this letter. A broadcast must still be in Term Time's
   * public tier (a person can revoke an approval after it was offered), and a personal letter is
   * only ever the mailbox family's.
   */
  private boolean stillVisibleTo(final ObjectId familyId, final DocumentSchoolOffer offer,
      final SchoolDocument document) {
    if (offer.personal()) {
      return familyId.toHexString().equalsIgnoreCase(schoolCalendar.mailboxFamilyId());
    }
    return document.visibility() == Visibility.PUBLIC;
  }

  private DocumentSchoolOffer find(final ObjectId familyId, final ObjectId offerId) {
    return offers.findByIdAndFamilyId(offerId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "School offer not found"));
  }
}
