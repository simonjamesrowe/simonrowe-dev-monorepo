package com.simonrowe.migration.changeunits;

import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Generalises {@code narrations} from a blog-only {@code blogId} to a
 * {@code contentType} + {@code contentId} pair, so one pipeline can narrate blogs and
 * article summaries alike.
 *
 * <p>Every existing narration is a blog narration, so the backfill is unconditional:
 * {@code contentType: "BLOG"}, {@code contentId} copied from {@code blogId}, and
 * {@code blogId} unset.
 *
 * <p>The narration {@code _id} is a content-addressed fingerprint over the script text and
 * voice settings, and the stored MP3 lives in a directory named after it. Nothing here
 * touches that, so no audio file is orphaned and no narration is invalidated.
 *
 * <p>Works at the raw {@link Document} level rather than through the {@code Narration}
 * class: mapping documents that still carry the old field through the new type would drop
 * {@code blogId} before this migration could read it.
 *
 * <p>Idempotent: the filter matches only documents that still have a {@code blogId}, so a
 * re-run is a no-op, and index create/drop tolerate the already-done state. Performs no
 * external I/O, so the standard change-unit test pattern applies.
 */
@ChangeUnit(id = "generalise-narration-content-type", order = "021", author = "simonrowe")
public class V021GeneraliseNarrationContentType {

  private static final Logger LOG =
      LoggerFactory.getLogger(V021GeneraliseNarrationContentType.class);

  static final String COLLECTION = "narrations";
  static final String OLD_COMPOUND_INDEX = "idx_narration_blog_updated";
  static final String OLD_FIELD_INDEX = "blogId";
  static final String NEW_COMPOUND_INDEX = "idx_narration_content_updated";
  static final String NEW_FIELD_INDEX = "contentId";

  private static final String BLOG_ID = "blogId";
  private static final String CONTENT_TYPE = "contentType";
  private static final String CONTENT_ID = "contentId";
  private static final String BLOG = "BLOG";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    final MongoCollection<Document> narrations =
        mongoTemplate.getCollection(COLLECTION);

    int migrated = 0;
    for (final Document narration
        : narrations.find(new Document(BLOG_ID, new Document("$exists", true)))) {
      narrations.updateOne(
          new Document("_id", narration.get("_id")),
          new Document("$set", new Document(CONTENT_TYPE, BLOG)
              .append(CONTENT_ID, narration.get(BLOG_ID)))
              .append("$unset", new Document(BLOG_ID, "")));
      migrated++;
    }
    LOG.info("Migrated {} narrations from blogId to contentType/contentId", migrated);

    dropIndexIfPresent(mongoTemplate, OLD_COMPOUND_INDEX);
    dropIndexIfPresent(mongoTemplate, OLD_FIELD_INDEX);
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    final MongoCollection<Document> narrations =
        mongoTemplate.getCollection(COLLECTION);
    for (final Document narration
        : narrations.find(new Document(CONTENT_ID, new Document("$exists", true)))) {
      narrations.updateOne(
          new Document("_id", narration.get("_id")),
          new Document("$set", new Document(BLOG_ID, narration.get(CONTENT_ID)))
              .append("$unset", new Document(CONTENT_ID, "").append(CONTENT_TYPE, "")));
    }

    dropIndexIfPresent(mongoTemplate, NEW_COMPOUND_INDEX);
    dropIndexIfPresent(mongoTemplate, NEW_FIELD_INDEX);
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(OLD_FIELD_INDEX)
        .on(BLOG_ID, Sort.Direction.ASC));
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(OLD_COMPOUND_INDEX)
        .on(BLOG_ID, Sort.Direction.ASC)
        .on("updatedAt", Sort.Direction.DESC));
  }

  /**
   * Creates the content-keyed indexes.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(NEW_FIELD_INDEX)
        .on(CONTENT_ID, Sort.Direction.ASC));
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(NEW_COMPOUND_INDEX)
        .on(CONTENT_TYPE, Sort.Direction.ASC)
        .on(CONTENT_ID, Sort.Direction.ASC)
        .on("updatedAt", Sort.Direction.DESC));
  }

  private static void dropIndexIfPresent(
      final MongoTemplate mongoTemplate, final String indexName) {
    try {
      mongoTemplate.indexOps(COLLECTION).dropIndex(indexName);
    } catch (RuntimeException ex) {
      // Already absent — a re-run, or a database that never had it.
      LOG.debug("Index {} not present on {}, nothing to drop", indexName, COLLECTION);
    }
  }
}
