package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the unique compound index on the {@code favourites} collection so a save is
 * idempotent per {@code (userId, type, contentId)}. Spring Data auto-index-creation is
 * disabled, so the {@code @CompoundIndex} annotation on {@code Favourite} is not applied
 * automatically. Index creation is idempotent, making this change unit safe to re-run.
 */
@ChangeUnit(id = "create-favourites-unique-index", order = "013", author = "simonrowe")
public class V013CreateFavouritesUniqueIndex {

  private static final String COLLECTION = "favourites";
  private static final String INDEX_NAME = "idx_user_type_content";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named(INDEX_NAME)
        .on("userId", Sort.Direction.ASC)
        .on("type", Sort.Direction.ASC)
        .on("contentId", Sort.Direction.ASC)
        .unique());
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(COLLECTION).dropIndex(INDEX_NAME);
  }
}
