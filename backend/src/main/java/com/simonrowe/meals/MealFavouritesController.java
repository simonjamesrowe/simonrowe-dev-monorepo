package com.simonrowe.meals;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's hearted recipes. Never shared between people. */
@RestController
@RequestMapping("/api/meals/favourites")
public class MealFavouritesController {

  private final MealFavouriteService favourites;

  public MealFavouritesController(final MealFavouriteService favourites) {
    this.favourites = favourites;
  }

  @GetMapping
  public List<MealFavourite> list(@AuthenticationPrincipal final Jwt jwt) {
    return favourites.list(jwt.getSubject());
  }

  /** Hearts a recipe; hearting it again keeps the original {@code addedAt}. */
  @PutMapping("/{recipeId}")
  public MealFavourite add(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String recipeId) {
    return favourites.add(jwt.getSubject(), recipeId);
  }

  @DeleteMapping("/{recipeId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String recipeId) {
    favourites.remove(jwt.getSubject(), recipeId);
  }
}
