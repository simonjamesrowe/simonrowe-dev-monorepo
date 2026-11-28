package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.model.DocumentNotice;
import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the CoParent document library's three collections and their indexes (spec 057):
 * {@code documents}, {@code document_school_offers} and {@code document_notices}.
 * {@code auto-index-creation} is off, so these indexes exist only here, and
 * {@code RestoreService} calls {@link #createIndexes} directly because a restore drops the
 * collections and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-coparent-documents", order = "069", author = "simonrowe")
public class V069CreateCoparentDocuments {

  public static final String DOCUMENTS = FamilyDocument.COLLECTION;
  public static final String OFFERS = DocumentSchoolOffer.COLLECTION;
  public static final String NOTICES = DocumentNotice.COLLECTION;

  private static final Logger log = LoggerFactory.getLogger(V069CreateCoparentDocuments.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured CoParent document collections and indexes");
  }

  /** Creates the collections when missing and every index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    for (final String collection : List.of(DOCUMENTS, OFFERS, NOTICES)) {
      if (!mongoTemplate.collectionExists(collection)) {
        mongoTemplate.createCollection(collection);
      }
    }
    // The library page: a family's archived or unarchived documents, by section.
    mongoTemplate.indexOps(DOCUMENTS).createIndex(new Index()
        .named("idx_coparent_document_family_archived_section")
        .on("familyId", Sort.Direction.ASC)
        .on("archivedAt", Sort.Direction.ASC)
        .on("section", Sort.Direction.ASC));
    // What is expiring or due for review.
    mongoTemplate.indexOps(DOCUMENTS).createIndex(new Index()
        .named("idx_coparent_document_family_due")
        .on("familyId", Sort.Direction.ASC)
        .on("dueOn", Sort.Direction.ASC));
    // The ledger: one school document is offered to a family at most once, whatever was
    // decided, which is what stops a dismissed letter coming back on the next sync.
    mongoTemplate.indexOps(OFFERS).createIndex(new Index()
        .named("idx_coparent_document_offer_source")
        .on("familyId", Sort.Direction.ASC)
        .on("schoolDocumentId", Sort.Direction.ASC)
        .unique());
    mongoTemplate.indexOps(OFFERS).createIndex(new Index()
        .named("idx_coparent_document_offer_family_status")
        .on("familyId", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("sentAt", Sort.Direction.DESC));
    // One waiting email per recipient: every change upserts into it.
    mongoTemplate.indexOps(NOTICES).createIndex(new Index()
        .named("idx_coparent_document_notice_recipient")
        .on("familyId", Sort.Direction.ASC)
        .on("recipientParentId", Sort.Direction.ASC)
        .unique());
    // The minute-by-minute job's read: notices whose time has come.
    mongoTemplate.indexOps(NOTICES).createIndex(new Index()
        .named("idx_coparent_document_notice_send_after")
        .on("sendAfter", Sort.Direction.ASC));
  }

  @RollbackExecution
  public void rollback() {
    // Documents are family records pointing at files that exist nowhere else, and a dismissed
    // offer is the only record a parent said no; dropping any of them would lose that.
  }
}
