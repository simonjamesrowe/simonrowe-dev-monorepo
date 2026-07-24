package com.simonrowe.migration.changeunits;

import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.HashSet;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Migrates the {@code favourites} collection from per-user storage to a globally shared set.
 * Drops the per-user unique index, deduplicates rows so a piece of content appears once
 * regardless of who favourited it (keeping the earliest favourite), removes the now-unused
 * {@code userId} field, and creates the global indexes: a unique {@code (type, contentId)}
 * index for idempotency and a {@code (type, createdAt)} index that covers the sorted listing
 * query. Spring Data auto-index-creation is disabled, so indexes must be created explicitly.
 * Every step is idempotent, making this change unit safe to re-run.
 */
@ChangeUnit(id = "make-favourites-global", order = "014", author = "simonrowe")
public class V014MakeFavouritesGlobal {

  private static final String COLLECTION = "favourites";
  private static final String OLD_UNIQUE_INDEX = "idx_user_type_content";
  private static final String UNIQUE_INDEX = "idx_type_content";
  private static final String LIST_INDEX = "idx_type_created";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    dropIndexIfExists(mongoTemplate, OLD_UNIQUE_INDEX);
    deduplicateByTypeAndContent(mongoTemplate);
    mongoTemplate.getCollection(COLLECTION)
        .updateMany(new Document(), new Document("$unset", new Document("userId", "")));
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(UNIQUE_INDEX)
        .on("type", Sort.Direction.ASC)
        .on("contentId", Sort.Direction.ASC)
        .unique());
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(LIST_INDEX)
        .on("type", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC));
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    dropIndexIfExists(mongoTemplate, UNIQUE_INDEX);
    dropIndexIfExists(mongoTemplate, LIST_INDEX);
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(OLD_UNIQUE_INDEX)
        .on("userId", Sort.Direction.ASC)
        .on("type", Sort.Direction.ASC)
        .on("contentId", Sort.Direction.ASC)
        .unique());
  }

  /** Keeps the earliest favourite per {@code (type, contentId)} and deletes the rest. */
  private void deduplicateByTypeAndContent(final MongoTemplate mongoTemplate) {
    final MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
    final Set<String> seen = new HashSet<>();
    for (final Document doc : collection.find().sort(new Document("createdAt", 1))) {
      final String key = doc.getString("type") + "|" + doc.getString("contentId");
      if (!seen.add(key)) {
        collection.deleteOne(new Document("_id", doc.get("_id")));
      }
    }
  }

  private void dropIndexIfExists(final MongoTemplate mongoTemplate, final String indexName) {
    final boolean exists = mongoTemplate.indexOps(COLLECTION).getIndexInfo().stream()
        .anyMatch(info -> indexName.equals(info.getName()));
    if (exists) {
      mongoTemplate.indexOps(COLLECTION).dropIndex(indexName);
    }
  }
}
