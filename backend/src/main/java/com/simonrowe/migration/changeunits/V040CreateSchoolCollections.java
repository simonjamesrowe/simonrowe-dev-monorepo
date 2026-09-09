package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the indexes for the three Term Time collections.
 *
 * <p>Indexes only, no data. Spring Data auto-index-creation is disabled in this application, so
 * the annotations on the model records create nothing on their own and this is the only thing
 * standing between the approval-queue read and a full collection scan.
 *
 * <p>{@link #createIndexes} is public and static because {@code RestoreService} has to call it
 * directly: a restore drops each collection along with its indexes, and Mongock will not re-run a
 * change unit it has already recorded as applied.
 */
@ChangeUnit(id = "create-school-collections", order = "040", author = "simonrowe")
public class V040CreateSchoolCollections {

  static final String DOCUMENTS = "school_documents";
  static final String EVENTS = "school_events";
  static final String SYNC_STATE = "school_sync_state";
  static final String USAGE = "school_usage";
  static final String LINKS = "school_links";

  static final String DOC_VISIBILITY_INDEX = "idx_school_doc_visibility_published";
  static final String DOC_SOURCE_INDEX = "idx_school_doc_source";
  static final String DOC_APPROVAL_INDEX = "idx_school_doc_approval_queue";
  static final String EVENT_RANGE_INDEX = "idx_school_event_range";
  static final String EVENT_TYPE_INDEX = "idx_school_event_year_type";
  static final String USAGE_AT_INDEX = "idx_school_usage_at";
  static final String LINK_STATUS_INDEX = "idx_school_link_status";
  static final String LINK_DOCUMENT_INDEX = "idx_school_link_document";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(DOCUMENTS).dropIndex(DOC_VISIBILITY_INDEX);
    mongoTemplate.indexOps(DOCUMENTS).dropIndex(DOC_SOURCE_INDEX);
    mongoTemplate.indexOps(DOCUMENTS).dropIndex(DOC_APPROVAL_INDEX);
    mongoTemplate.indexOps(EVENTS).dropIndex(EVENT_RANGE_INDEX);
    mongoTemplate.indexOps(EVENTS).dropIndex(EVENT_TYPE_INDEX);
    mongoTemplate.indexOps(USAGE).dropIndex(USAGE_AT_INDEX);
    mongoTemplate.indexOps(LINKS).dropIndex(LINK_STATUS_INDEX);
    mongoTemplate.indexOps(LINKS).dropIndex(LINK_DOCUMENT_INDEX);
  }

  /**
   * Creates every Term Time index. Idempotent, so safe to re-run after a restore.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    // Public-tier retrieval: filter on visibility, order by recency.
    mongoTemplate.indexOps(DOCUMENTS).createIndex(new Index()
        .named(DOC_VISIBILITY_INDEX)
        .on("visibility", Sort.Direction.ASC)
        .on("publishedAt", Sort.Direction.DESC));

    // Re-ingest dedup. Unique, so a repeated ingest of the same source updates rather than
    // inserting a second copy that would then be retrieved twice and cited twice.
    mongoTemplate.indexOps(DOCUMENTS).createIndex(new Index()
        .named(DOC_SOURCE_INDEX)
        .on("sourceType", Sort.Direction.ASC)
        .on("sourceRef", Sort.Direction.ASC)
        .unique());

    // The approval queue read in SchoolDocumentRepository.findAwaitingApproval.
    mongoTemplate.indexOps(DOCUMENTS).createIndex(new Index()
        .named(DOC_APPROVAL_INDEX)
        .on("proposedVisibility", Sort.Direction.ASC)
        .on("visibility", Sort.Direction.ASC)
        .on("nameGateBlocked", Sort.Direction.ASC));

    // "What is on this week": an overlap query over startDate/endDate, tier-filtered.
    mongoTemplate.indexOps(EVENTS).createIndex(new Index()
        .named(EVENT_RANGE_INDEX)
        .on("startDate", Sort.Direction.ASC)
        .on("endDate", Sort.Direction.ASC)
        .on("visibility", Sort.Direction.ASC));

    // "When are the INSET days": academic year plus type.
    mongoTemplate.indexOps(EVENTS).createIndex(new Index()
        .named(EVENT_TYPE_INDEX)
        .on("academicYear", Sort.Direction.ASC)
        .on("eventType", Sort.Direction.ASC)
        .on("startDate", Sort.Direction.ASC));

    // Spend reporting reads a time window, newest first.
    mongoTemplate.indexOps(USAGE).createIndex(new Index()
        .named(USAGE_AT_INDEX)
        .on("at", Sort.Direction.DESC));

    // The pending-link queue, and the per-document lookup the documents page does.
    mongoTemplate.indexOps(LINKS).createIndex(new Index()
        .named(LINK_STATUS_INDEX)
        .on("status", Sort.Direction.ASC));
    mongoTemplate.indexOps(LINKS).createIndex(new Index()
        .named(LINK_DOCUMENT_INDEX)
        .on("sourceDocumentId", Sort.Direction.ASC));

    // school_sync_state is read only by _id, which Mongo always indexes. No index declared, and
    // adding one would be dead weight on a collection with one document per source. Created
    // eagerly so a restore finds it present; guarded because createCollection throws on an
    // existing collection, which would make this change unit fail its second run.
    if (!mongoTemplate.collectionExists(SYNC_STATE)) {
      mongoTemplate.createCollection(SYNC_STATE);
    }
  }
}
