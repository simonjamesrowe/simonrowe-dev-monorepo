package com.simonrowe.migration.changeunits;

import com.simonrowe.meals.MealInventoryEvent;
import com.simonrowe.meals.MealInventoryItem;
import com.simonrowe.meals.MealMcpToken;
import com.simonrowe.meals.MealShoppingMark;
import com.simonrowe.meals.MealUpNextItem;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the meals kitchen, Up next, shopping and MCP token collections (spec 053) and their
 * indexes. Every row but a token is keyed {@code <ownerId>|<name>}, so one ingredient or recipe
 * per owner is guaranteed by the primary key; the indexes serve the per-owner reads. A token's
 * hash is unique, because authentication looks it up and must find at most one.
 *
 * <p>No data is written. {@code RestoreService} calls {@link #createIndexes} directly, because a
 * restore drops the collection and Mongock never re-runs a recorded unit.
 */
@ChangeUnit(id = "create-meals-kitchen", order = "059", author = "simonrowe")
public class V059CreateMealsKitchen {

  public static final List<String> COLLECTIONS = List.of(MealInventoryItem.COLLECTION,
      MealInventoryEvent.COLLECTION, MealUpNextItem.COLLECTION, MealShoppingMark.COLLECTION,
      MealMcpToken.COLLECTION);
  public static final String INVENTORY_OWNER_INDEX = "idx_meal_inventory_owner";
  public static final String EVENTS_OWNER_INDEX = "idx_meal_inventory_events_owner_at";
  public static final String UP_NEXT_OWNER_INDEX = "idx_meal_up_next_owner_added";
  public static final String SHOPPING_OWNER_INDEX = "idx_meal_shopping_owner";
  public static final String TOKEN_HASH_INDEX = "idx_meal_mcp_token_hash";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  /** Creates any missing collection, and the indexes. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    for (String collection : COLLECTIONS) {
      if (!mongoTemplate.collectionExists(collection)) {
        mongoTemplate.createCollection(collection);
      }
    }
    mongoTemplate.indexOps(MealInventoryItem.COLLECTION).createIndex(new Index()
        .named(INVENTORY_OWNER_INDEX).on("ownerId", Sort.Direction.ASC));
    mongoTemplate.indexOps(MealInventoryEvent.COLLECTION).createIndex(new Index()
        .named(EVENTS_OWNER_INDEX).on("ownerId", Sort.Direction.ASC)
        .on("at", Sort.Direction.DESC));
    mongoTemplate.indexOps(MealUpNextItem.COLLECTION).createIndex(new Index()
        .named(UP_NEXT_OWNER_INDEX).on("ownerId", Sort.Direction.ASC)
        .on("addedAt", Sort.Direction.ASC));
    mongoTemplate.indexOps(MealShoppingMark.COLLECTION).createIndex(new Index()
        .named(SHOPPING_OWNER_INDEX).on("ownerId", Sort.Direction.ASC));
    mongoTemplate.indexOps(MealMcpToken.COLLECTION).createIndex(new Index()
        .named(TOKEN_HASH_INDEX).on("tokenHash", Sort.Direction.ASC).unique());
  }

  /** Drops the indexes only: the collections hold the owner's kitchen and plans. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(MealInventoryItem.COLLECTION).dropIndex(INVENTORY_OWNER_INDEX);
    mongoTemplate.indexOps(MealInventoryEvent.COLLECTION).dropIndex(EVENTS_OWNER_INDEX);
    mongoTemplate.indexOps(MealUpNextItem.COLLECTION).dropIndex(UP_NEXT_OWNER_INDEX);
    mongoTemplate.indexOps(MealShoppingMark.COLLECTION).dropIndex(SHOPPING_OWNER_INDEX);
    mongoTemplate.indexOps(MealMcpToken.COLLECTION).dropIndex(TOKEN_HASH_INDEX);
  }
}
