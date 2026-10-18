package com.simonrowe.migration.changeunits;

import com.simonrowe.meals.MealFavourite;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the meals favourites collection and its index. A favourite is keyed
 * {@code <ownerId>|<recipeId>}, so one heart per person and recipe is guaranteed by the primary
 * key; the index serves the per-owner, newest-first read.
 *
 * <p>No data is written. {@code RestoreService} calls {@link #createIndexes} directly, because a
 * restore drops the collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-meal-favourites", order = "060", author = "simonrowe")
public class V060CreateMealFavourites {

  public static final String OWNER_ADDED_INDEX = "idx_meal_favourites_owner_added";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  /** Creates the collection if missing, and the index. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    if (!mongoTemplate.collectionExists(MealFavourite.COLLECTION)) {
      mongoTemplate.createCollection(MealFavourite.COLLECTION);
    }
    mongoTemplate.indexOps(MealFavourite.COLLECTION).createIndex(new Index()
        .named(OWNER_ADDED_INDEX).on("ownerId", Sort.Direction.ASC)
        .on("addedAt", Sort.Direction.DESC));
  }

  /** Drops the index only: the collection holds people's favourites. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(MealFavourite.COLLECTION).dropIndex(OWNER_ADDED_INDEX);
  }
}
