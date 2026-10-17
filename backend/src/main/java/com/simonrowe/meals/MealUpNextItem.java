package com.simonrowe.meals;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A recipe the owner means to cook soon. There are no days or slots: Up next is a shortlist, and
 * the shopping list is built from it.
 *
 * @param id {@code <ownerId>|<recipeId>}
 * @param ownerId Auth0 subject
 * @param recipeId the recipe
 * @param portions how many portions to make
 * @param addedAt when it was added
 */
@Document(collection = MealUpNextItem.COLLECTION)
public record MealUpNextItem(
    @Id String id,
    String ownerId,
    String recipeId,
    int portions,
    Instant addedAt
) {

  public static final String COLLECTION = "meal_up_next";

  public static String idFor(final String ownerId, final String recipeId) {
    return ownerId + "|" + recipeId;
  }
}
