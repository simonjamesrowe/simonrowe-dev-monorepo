package com.simonrowe.meals;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A recipe one person has hearted. Favourites belong to the person who made them and are never
 * shared: two people using the meals app each see only their own.
 *
 * @param id {@code <ownerId>|<recipeId>}, so a recipe can be hearted at most once per person
 * @param ownerId Auth0 subject
 * @param recipeId the recipe
 * @param addedAt when it was first hearted
 */
@Document(collection = MealFavourite.COLLECTION)
public record MealFavourite(
    @Id String id,
    String ownerId,
    String recipeId,
    Instant addedAt
) {

  public static final String COLLECTION = "meal_favourites";

  public static String idFor(final String ownerId, final String recipeId) {
    return ownerId + "|" + recipeId;
  }
}
