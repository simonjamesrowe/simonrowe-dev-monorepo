package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the indexes on {@code platform_releases}. Spring Data auto-index-creation is
 * disabled, so annotations on {@code PlatformRelease} would be decorative. Index creation is
 * idempotent, so this is safe to re-run.
 *
 * <p><b>This change unit creates indexes only.</b> The release documents themselves are
 * written by {@code ReleaseRecorder} on startup, deliberately not by a change unit: they are
 * derived, self-healing data that a restore has to re-establish anyway, and putting LLM-fed
 * seeding in a change unit would run live I/O against the shared Testcontainers Mongo. Same
 * reasoning as {@code NarrationRestoreValidator.ensureIndexes()}.
 *
 * <p>No index on {@code _id} is declared: it is the commit SHA and Mongo always indexes
 * {@code _id}, which is what the seeding dedup relies on.
 */
@ChangeUnit(id = "create-platform-release-indexes", order = "022", author = "simonrowe")
public class V022CreatePlatformReleaseIndexes {

  static final String COLLECTION = "platform_releases";
  static final String COMMIT_TIME_INDEX = "idx_platform_release_commit_time";
  static final String SUMMARY_STATUS_INDEX = "idx_platform_release_summary_status";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(COMMIT_TIME_INDEX);
    mongoTemplate.indexOps(COLLECTION).dropIndex(SUMMARY_STATUS_INDEX);
  }

  /**
   * Also called after a restore, which drops collections and their indexes with them.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    // The changelog read: sort by commitTime descending, no filter.
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(COMMIT_TIME_INDEX)
        .on("commitTime", Sort.Direction.DESC));
    // The sweep's claim query: filter on summaryStatus, sort by commitTime descending.
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(SUMMARY_STATUS_INDEX)
        .on("summaryStatus", Sort.Direction.ASC)
        .on("commitTime", Sort.Direction.DESC));
  }
}
