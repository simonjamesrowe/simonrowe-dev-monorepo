package com.simonrowe.meals;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One standard ingredient. Every recipe line, inventory row and shopping-list line names one of
 * these, which is what lets "1 scoop (25g) protein powder" and "protein powder" add up.
 *
 * @param name canonical lower-case name, the id
 * @param unit the unit quantities are kept in: g, ml or each, or null
 * @param tier {@code tracked} (kept as a quantity with a use-by date) or {@code staple} (kept as
 *     have / low / out)
 * @param aisle the shopping-list group
 * @param assumed true for salt, pepper and water: never listed, never counted as missing
 */
@Document(collection = MealIngredient.COLLECTION)
public record MealIngredient(
    @Id String name,
    String unit,
    String tier,
    String aisle,
    boolean assumed
) {

  public static final String COLLECTION = "meal_ingredients";
}
