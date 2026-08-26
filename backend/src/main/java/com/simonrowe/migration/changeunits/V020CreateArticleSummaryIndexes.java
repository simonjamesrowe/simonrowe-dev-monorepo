package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the indexes on the {@code article_summaries} collection. Spring Data
 * auto-index-creation is disabled, so the {@code @Indexed} / {@code @CompoundIndex}
 * annotations on {@code ArticleSummary} are not applied automatically. Index creation is
 * idempotent, making this change unit safe to re-run.
 *
 * <p>No unique index on {@code _id} is declared: the insert-first dedup guard relies on
 * Mongo's own {@code _id} index, which always exists.
 */
@ChangeUnit(id = "create-article-summary-indexes", order = "020", author = "simonrowe")
public class V020CreateArticleSummaryIndexes {

  static final String COLLECTION = "article_summaries";
  static final String ARTICLE_INDEX = "idx_article_summary_article";
  static final String STATUS_ARTICLE_INDEX = "idx_article_summary_status_article";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(ARTICLE_INDEX);
    mongoTemplate.indexOps(COLLECTION).dropIndex(STATUS_ARTICLE_INDEX);
  }

  /**
   * Also called after a restore, which drops collections and their indexes with them.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(ARTICLE_INDEX)
        .on("articleId", Sort.Direction.ASC));
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(STATUS_ARTICLE_INDEX)
        .on("status", Sort.Direction.ASC)
        .on("articleId", Sort.Direction.ASC));
  }
}
