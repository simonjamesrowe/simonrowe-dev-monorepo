package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import com.simonrowe.coparent.school.SchoolEventSuggestion;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the CoParent collection of school events offered to families, and its indexes.
 * {@code RestoreService} calls {@link #createIndexes} directly, because a restore drops the
 * collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-coparent-school-suggestions", order = "067", author = "simonrowe")
public class V067CreateCoparentSchoolSuggestions {

  public static final String SUGGESTIONS = SchoolEventSuggestion.COLLECTION;

  private static final Logger log =
      LoggerFactory.getLogger(V067CreateCoparentSchoolSuggestions.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured CoParent school suggestions collection and indexes");
  }

  /** Creates the collection when missing and its indexes. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(SUGGESTIONS)) {
      mongoTemplate.createCollection(SUGGESTIONS);
    }
    // One school event row is in at most one suggestion per family, whatever was decided. A
    // unique index over an array field is unique per element across documents, so a sync that
    // raced another, or a merge that reached for a row already suggested, fails to insert rather
    // than offering a dismissed event again.
    mongoTemplate.indexOps(SUGGESTIONS).createIndex(new Index()
        .named("idx_coparent_school_suggestion_source")
        .on("familyId", Sort.Direction.ASC)
        .on("sourceEventIds", Sort.Direction.ASC)
        .unique());
    // The family's list: what is waiting, by date.
    mongoTemplate.indexOps(SUGGESTIONS).createIndex(new Index()
        .named("idx_coparent_school_suggestion_family_status")
        .on("familyId", Sort.Direction.ASC)
        .on("status", Sort.Direction.ASC)
        .on("details.startDate", Sort.Direction.ASC));
  }

  @RollbackExecution
  public void rollback() {
    // A dismissed suggestion is the only record that a parent said no; dropping the collection
    // would offer every dismissed event again.
  }
}
