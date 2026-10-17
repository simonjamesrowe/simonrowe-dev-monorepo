package com.simonrowe.meals;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One change to the kitchen and what caused it, so a wrong deduction can be found and put right.
 *
 * @param id random id
 * @param ownerId Auth0 subject
 * @param at when
 * @param kind quick-add, order, cooked, delivered, manual or mcp
 * @param summary one line for people
 * @param changes what changed
 */
@Document(collection = MealInventoryEvent.COLLECTION)
public record MealInventoryEvent(
    @Id String id,
    String ownerId,
    Instant at,
    String kind,
    String summary,
    List<InventoryChange> changes
) {

  public static final String COLLECTION = "meal_inventory_events";
}
