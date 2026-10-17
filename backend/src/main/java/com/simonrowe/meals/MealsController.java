package com.simonrowe.meals;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The recipe library: recipes, standard ingredients, Dave's weeks and photos. Admin-only, like
 * every route under {@code /api/meals} (see {@code SecurityConfig}).
 *
 * <p>The whole library is returned in one response. It is about 150 recipes, and the browser
 * needs every ingredient line to work out what can be cooked from the kitchen.
 */
@RestController
@RequestMapping("/api/meals")
public class MealsController {

  private final MealRecipeRepository recipes;
  private final MealIngredientRepository ingredients;
  private final MealTemplateRepository templates;
  private final MealImageStore imageStore;

  public MealsController(
      final MealRecipeRepository recipes,
      final MealIngredientRepository ingredients,
      final MealTemplateRepository templates,
      final MealImageStore imageStore
  ) {
    this.recipes = recipes;
    this.ingredients = ingredients;
    this.templates = templates;
    this.imageStore = imageStore;
  }

  @GetMapping("/recipes")
  public List<MealRecipe> recipes() {
    return recipes.findAll().stream()
        .sorted(Comparator.comparing(MealRecipe::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @GetMapping("/recipes/{id}")
  public MealRecipe recipe(@PathVariable final String id) {
    return recipes.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recipe"));
  }

  @GetMapping("/ingredients")
  public List<MealIngredient> ingredients() {
    return ingredients.findAll().stream()
        .sorted(Comparator.comparing(MealIngredient::name))
        .toList();
  }

  @GetMapping("/templates")
  public List<MealTemplate> templates() {
    return templates.findAll().stream()
        .sorted(Comparator.comparing(MealTemplate::source)
            .thenComparingInt(MealTemplate::kcal)
            .thenComparingInt(MealTemplate::week))
        .toList();
  }

  /**
   * A recipe photo. The name is the hash of the bytes, so the response never changes and the
   * browser may keep it; {@code private} keeps it out of shared caches.
   */
  @GetMapping("/images/{name}")
  public ResponseEntity<byte[]> image(@PathVariable final String name) {
    byte[] bytes = imageStore.read(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image"));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(MealImageStore.contentType(name)))
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate()
            .immutable())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }
}
