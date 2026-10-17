package com.simonrowe.meals;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One time a recipe was cooked and eaten, and what its owner thought. The only log the app
 * keeps: there is no calorie diary.
 *
 * @param id random id
 * @param ownerId the Auth0 subject of the person who rated it
 * @param recipeId the recipe
 * @param at when it was rated
 * @param score 1 to 5
 * @param again whether they would make it again
 * @param tags quick tags such as "spicy" or "faff"
 * @param notes free-text notes for next time
 */
@Document(collection = MealRating.COLLECTION)
public record MealRating(
    @Id String id,
    String ownerId,
    String recipeId,
    Instant at,
    int score,
    boolean again,
    List<String> tags,
    String notes
) {

  public static final String COLLECTION = "meal_ratings";
}
