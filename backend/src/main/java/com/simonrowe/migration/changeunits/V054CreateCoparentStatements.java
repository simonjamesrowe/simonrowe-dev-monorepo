package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.model.StatementTransaction;
import com.simonrowe.coparent.model.StatementUpload;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the CoParent statement collections and their indexes. {@code RestoreService} calls
 * {@link #createIndexes} directly, because a restore drops the collections and Mongock never
 * re-runs a recorded unit.
 */
@ChangeUnit(id = "create-coparent-statements", order = "054", author = "simonrowe")
public class V054CreateCoparentStatements {

  public static final String TRANSACTIONS = StatementTransaction.COLLECTION;
  public static final String UPLOADS = StatementUpload.COLLECTION;

  private static final Logger log = LoggerFactory.getLogger(V054CreateCoparentStatements.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured CoParent statement collections and indexes");
  }

  /** Creates both collections when missing and every statement index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    for (final String collection : new String[] {TRANSACTIONS, UPLOADS}) {
      if (!mongoTemplate.collectionExists(collection)) {
        mongoTemplate.createCollection(collection);
      }
    }
    // One record per statement row per parent. This index, not a read before the write, is
    // what makes uploading the same statement twice add nothing, and what stops two tabs or a
    // double click logging one transaction twice.
    mongoTemplate.indexOps(TRANSACTIONS).createIndex(new Index()
        .named("idx_coparent_statement_owner_fingerprint")
        .on("ownerParentId", Sort.Direction.ASC)
        .on("fingerprint", Sort.Direction.ASC)
        .unique());
    // The review tabs and their counts.
    mongoTemplate.indexOps(TRANSACTIONS).createIndex(new Index()
        .named("idx_coparent_statement_owner_status")
        .on("ownerParentId", Sort.Direction.ASC)
        .on("familyId", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("decidedAt", Sort.Direction.DESC));
    mongoTemplate.indexOps(UPLOADS).createIndex(new Index()
        .named("idx_coparent_statement_upload_owner_recent")
        .on("ownerParentId", Sort.Direction.ASC)
        .on("familyId", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC));
  }

  @RollbackExecution
  public void rollback() {
    // The fingerprints are what stop a re-upload being logged twice; dropping them on rollback
    // would let it happen.
  }
}
