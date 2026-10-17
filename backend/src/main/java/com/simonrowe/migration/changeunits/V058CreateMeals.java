package com.simonrowe.migration.changeunits;

import com.simonrowe.meals.MealIngredient;
import com.simonrowe.meals.MealRating;
import com.simonrowe.meals.MealRecipe;
import com.simonrowe.meals.MealTemplate;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the meals library collections (spec 053) and the ratings index.
 *
 * <p>Recipes, ingredients and weeks are keyed by their own ids, which is all their lookups
 * need. Ratings are read per owner, newest first, and per recipe for one owner.
 *
 * <p>No data is written: the books are copyrighted, so recipes arrive only through an
 * uploaded bundle, never from this repository. {@code RestoreService} calls
 * {@link #createIndexes} directly, because a restore drops the collection and Mongock never
 * re-runs a recorded unit.
 */
@ChangeUnit(id = "create-meals", order = "058", author = "simonrowe")
public class V058CreateMeals {

  public static final List<String> COLLECTIONS = List.of(MealRecipe.COLLECTION,
      MealIngredient.COLLECTION, MealTemplate.COLLECTION, MealRating.COLLECTION);
  public static final String RATINGS_BY_OWNER_INDEX = "idx_meal_ratings_owner_at";
  public static final String RATINGS_BY_RECIPE_INDEX = "idx_meal_ratings_owner_recipe_at";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  /** Creates any missing collection, and the ratings indexes. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    for (String collection : COLLECTIONS) {
      if (!mongoTemplate.collectionExists(collection)) {
        mongoTemplate.createCollection(collection);
      }
    }
    mongoTemplate.indexOps(MealRating.COLLECTION).createIndex(new Index()
        .named(RATINGS_BY_OWNER_INDEX)
        .on("ownerId", Sort.Direction.ASC)
        .on("at", Sort.Direction.DESC));
    mongoTemplate.indexOps(MealRating.COLLECTION).createIndex(new Index()
        .named(RATINGS_BY_RECIPE_INDEX)
        .on("ownerId", Sort.Direction.ASC)
        .on("recipeId", Sort.Direction.ASC)
        .on("at", Sort.Direction.DESC));
  }

  /** Drops the indexes only. The collections hold the owner's ratings and uploaded books. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(MealRating.COLLECTION).dropIndex(RATINGS_BY_OWNER_INDEX);
    mongoTemplate.indexOps(MealRating.COLLECTION).dropIndex(RATINGS_BY_RECIPE_INDEX);
  }
}
