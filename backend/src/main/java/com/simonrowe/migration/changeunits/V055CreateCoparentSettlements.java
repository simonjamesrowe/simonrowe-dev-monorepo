package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.model.ExpenseSettlement;
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
 * Creates the CoParent settle-up collection and its index. {@code RestoreService} calls
 * {@link #createIndexes} directly, because a restore drops the collection and Mongock never
 * re-runs a recorded unit.
 */
@ChangeUnit(id = "create-coparent-settlements", order = "055", author = "simonrowe")
public class V055CreateCoparentSettlements {

  public static final String SETTLEMENTS = ExpenseSettlement.COLLECTION;

  private static final Logger log = LoggerFactory.getLogger(V055CreateCoparentSettlements.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured CoParent settlements collection and index");
  }

  /** Creates the collection when missing and its index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(SETTLEMENTS)) {
      mongoTemplate.createCollection(SETTLEMENTS);
    }
    // The Transfers list: a family's settle-ups, newest first.
    mongoTemplate.indexOps(SETTLEMENTS).createIndex(new Index()
        .named("idx_coparent_settlement_family_recent")
        .on("familyId", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC));
  }

  @RollbackExecution
  public void rollback() {
    // Settlements record money that changed hands; dropping them on rollback would lose that.
  }
}
