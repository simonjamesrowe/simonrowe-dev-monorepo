package com.simonrowe.meals;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Ratings: the one record of what was cooked, and how it went. Scoped to the signed-in owner. */
@RestController
@RequestMapping("/api/meals/ratings")
@Validated
public class MealRatingsController {

  private final MealRatingRepository ratings;
  private final MealRecipeRepository recipes;

  public MealRatingsController(
      final MealRatingRepository ratings,
      final MealRecipeRepository recipes
  ) {
    this.ratings = ratings;
    this.recipes = recipes;
  }

  @GetMapping
  public List<MealRating> mine(@AuthenticationPrincipal final Jwt jwt) {
    return ratings.findByOwnerIdOrderByAtDesc(jwt.getSubject());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MealRating rate(
      @AuthenticationPrincipal final Jwt jwt,
      @Valid @RequestBody final NewRating request
  ) {
    if (!recipes.existsById(request.recipeId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recipe");
    }
    return ratings.save(new MealRating(UUID.randomUUID().toString(), jwt.getSubject(),
        request.recipeId(), Instant.now(), request.score(), Boolean.TRUE.equals(request.again()),
        request.tags() == null ? List.of() : List.copyOf(request.tags()),
        request.notes() == null ? "" : request.notes().strip()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal final Jwt jwt, @PathVariable final String id) {
    MealRating rating = ratings.findByIdAndOwnerId(id, jwt.getSubject())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rating"));
    ratings.delete(rating);
  }

  /**
   * A new rating.
   *
   * @param recipeId the recipe that was cooked
   * @param score 1 to 5
   * @param again whether to make it again; null means yes
   * @param tags up to eight short tags
   * @param notes notes for next time
   */
  public record NewRating(
      @NotBlank @Size(max = 120) String recipeId,
      @Min(1) @Max(5) int score,
      Boolean again,
      @Size(max = 8) List<@NotBlank @Size(max = 30) String> tags,
      @Size(max = 1_000) String notes
  ) {
  }
}
