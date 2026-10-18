package com.simonrowe.meals;

import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-person recipe favourites ("hearts"). Every call is scoped to one owner, the Auth0 subject
 * or the MCP token's owner, so nobody can read or change someone else's.
 */
@Service
public class MealFavouriteService {

  private final MealFavouriteRepository favourites;
  private final MealRecipeRepository recipes;
  private final Clock clock;

  public MealFavouriteService(final MealFavouriteRepository favourites,
      final MealRecipeRepository recipes, final Clock clock) {
    this.favourites = favourites;
    this.recipes = recipes;
    this.clock = clock;
  }

  /** The owner's favourites, most recently hearted first. */
  public List<MealFavourite> list(final String ownerId) {
    return favourites.findByOwnerIdOrderByAddedAtDesc(ownerId);
  }

  /**
   * Hearts a recipe. Idempotent: hearting it again returns the existing favourite unchanged, so
   * it keeps its place in the list.
   *
   * @throws ResponseStatusException 404 when the recipe does not exist
   */
  public MealFavourite add(final String ownerId, final String recipeId) {
    if (!recipes.existsById(recipeId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recipe");
    }
    String id = MealFavourite.idFor(ownerId, recipeId);
    return favourites.findById(id).orElseGet(() ->
        favourites.save(new MealFavourite(id, ownerId, recipeId, clock.instant())));
  }

  /** Un-hearts a recipe. Removing one that is not a favourite does nothing. */
  public void remove(final String ownerId, final String recipeId) {
    favourites.deleteById(MealFavourite.idFor(ownerId, recipeId));
  }
}
