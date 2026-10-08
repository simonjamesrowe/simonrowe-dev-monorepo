package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.model.Expense;
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
 * Creates the CoParent shared-expenses collection and its indexes. Index creation is idempotent,
 * and {@code RestoreService} calls {@link #createIndexes} directly because a restore drops the
 * collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-coparent-expenses", order = "052", author = "simonrowe")
public class V052CreateCoparentExpenses {

  public static final String EXPENSES = Expense.COLLECTION;

  private static final Logger log = LoggerFactory.getLogger(V052CreateCoparentExpenses.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured CoParent expenses collection and indexes");
  }

  /** Creates the collection when missing and every expense index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(EXPENSES)) {
      mongoTemplate.createCollection(EXPENSES);
    }
    // The list and summary read: a family's live expenses, newest first.
    mongoTemplate.indexOps(EXPENSES).createIndex(new Index()
        .named("idx_coparent_expense_family_date")
        .on("familyId", Sort.Direction.ASC)
        .on("deletedAt", Sort.Direction.ASC)
        .on("date", Sort.Direction.DESC));
    mongoTemplate.indexOps(EXPENSES).createIndex(new Index()
        .named("idx_coparent_expense_family_agreement")
        .on("familyId", Sort.Direction.ASC)
        .on("agreement.status", Sort.Direction.ASC)
        .on("deletedAt", Sort.Direction.ASC));
    // An assistant retry finds the expense its first attempt created, so it adds nothing.
    mongoTemplate.indexOps(EXPENSES).createIndex(new Index()
        .named("idx_coparent_expense_assistant_action")
        .on("assistantActionId", Sort.Direction.ASC)
        .unique()
        .sparse());
  }

  @RollbackExecution
  public void rollback() {
    // Expenses are family records; dropping them on rollback would lose money owed.
  }
}
