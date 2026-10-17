package com.simonrowe.meals;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One ingredient in the owner's kitchen. A {@code tracked} ingredient (fresh food, meat, dairy,
 * bread) keeps a quantity in the ingredient's unit and an optional use-by date; a {@code staple}
 * (oats, spices, sauces, tins) keeps only have / low / out, because exact grams for those would
 * be wrong within a week.
 *
 * @param id {@code <ownerId>|<name>}, so one row per ingredient per owner
 * @param ownerId Auth0 subject
 * @param name canonical ingredient name
 * @param tier tracked or staple
 * @param quantity amount in {@code unit}, tracked only
 * @param unit g, ml or each, tracked only
 * @param status have, low or out, staple only
 * @param useBy use-by date, tracked only
 * @param updatedAt last change
 */
@Document(collection = MealInventoryItem.COLLECTION)
public record MealInventoryItem(
    @Id String id,
    String ownerId,
    String name,
    String tier,
    Double quantity,
    String unit,
    String status,
    LocalDate useBy,
    Instant updatedAt
) {

  public static final String COLLECTION = "meal_inventory";

  public static String idFor(final String ownerId, final String name) {
    return ownerId + "|" + name;
  }

  public boolean isStaple() {
    return "staple".equals(tier);
  }

  /** Whether any of it is in: a staple not out, or a tracked item with some left. */
  public boolean inStock() {
    return isStaple() ? !"out".equals(status) : quantity != null && quantity > 0;
  }
}
