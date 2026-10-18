package com.simonrowe.migration.changeunits;

import com.simonrowe.snippet.Snippet;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the snippets collection (spec 054) and its indexes:
 * <ul>
 *   <li>{@code contentId, createdAt}: one article's snippets, for the summary drawer;</li>
 *   <li>{@code createdAt} descending: the console's default order;</li>
 *   <li>{@code tags}: multikey, for the tag filter and the tag counts.</li>
 * </ul>
 *
 * <p>No data is written. {@code RestoreService} calls {@link #createIndexes} directly, because a
 * restore drops the collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-snippets", order = "061", author = "simonrowe")
public class V061CreateSnippets {

  public static final String CONTENT_INDEX = "idx_snippets_content_created";
  public static final String CREATED_INDEX = "idx_snippets_created";
  public static final String TAGS_INDEX = "idx_snippets_tags";
  public static final List<String> INDEXES = List.of(CONTENT_INDEX, CREATED_INDEX, TAGS_INDEX);

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  /** Creates the collection if missing, and its indexes. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(Snippet.COLLECTION)) {
      mongoTemplate.createCollection(Snippet.COLLECTION);
    }
    var indexOps = mongoTemplate.indexOps(Snippet.COLLECTION);
    indexOps.createIndex(new Index().named(CONTENT_INDEX)
        .on("contentId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC));
    indexOps.createIndex(new Index().named(CREATED_INDEX).on("createdAt", Sort.Direction.DESC));
    indexOps.createIndex(new Index().named(TAGS_INDEX).on("tags", Sort.Direction.ASC));
  }

  /** Drops the indexes only: the collection holds hand-written notes. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    var indexOps = mongoTemplate.indexOps(Snippet.COLLECTION);
    INDEXES.forEach(indexOps::dropIndex);
  }
}
