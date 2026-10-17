package com.simonrowe.meals;

import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adds and edits library recipes from the app: a draft read from a link or a photo once the owner
 * has reviewed it, or a fix to a book recipe the extraction got wrong.
 *
 * <p>New ingredient names are created only from the {@code newIngredients} the owner reviewed,
 * never silently from a line: a wrong merge would corrupt every list that uses the ingredient.
 */
@Service
public class RecipeService {

  static final Set<String> AISLES = Set.of("Fruit & veg", "Meat & fish", "Dairy & eggs",
      "Bakery", "Frozen", "Cupboard", "Other");
  static final Set<String> SOURCES = Set.of("BOOK", "LINK", "PHOTO");

  private final MongoTemplate mongoTemplate;
  private final MealRecipeRepository recipes;
  private final MealIngredientRepository ingredients;
  private final MealImageStore images;
  private final Clock clock;

  public RecipeService(final MongoTemplate mongoTemplate, final MealRecipeRepository recipes,
      final MealIngredientRepository ingredients, final MealImageStore images, final Clock clock) {
    this.mongoTemplate = mongoTemplate;
    this.recipes = recipes;
    this.ingredients = ingredients;
    this.images = images;
    this.clock = clock;
  }

  /**
   * A recipe as the editor sends it.
   *
   * @param name display name
   * @param category breakfast, main, snack, drink or dessert
   * @param section a heading such as "Mains"
   * @param servings portions it makes
   * @param perServing macros per portion
   * @param macrosEstimated whether the macros were estimated rather than stated
   * @param vegetarian vegetarian
   * @param vegan vegan
   * @param freezable freezes, if known
   * @param mainProtein main protein, if any
   * @param ingredients lines for the whole recipe
   * @param method steps
   * @param tips tips
   * @param swaps swaps
   * @param sourceType BOOK, LINK or PHOTO
   * @param sourceUrl the page it came from, for LINK
   * @param photo an image name from {@code POST /api/meals/images}, or null
   * @param newIngredients ingredients to add to the catalogue, as reviewed
   */
  public record RecipeInput(String name, String category, String section, Integer servings,
      MealRecipe.Macros perServing, Boolean macrosEstimated, Boolean vegetarian, Boolean vegan,
      Boolean freezable, String mainProtein, List<MealRecipe.IngredientLine> ingredients,
      List<String> method, List<String> tips, List<String> swaps, String sourceType,
      String sourceUrl, String photo, List<MealIngredient> newIngredients) {
  }

  public MealRecipe create(final RecipeInput input) {
    validate(input);
    addIngredients(input.newIngredients());
    String base = slug(input.name());
    for (int n = 1; n < 100; n++) {
      String id = n == 1 ? base : base + "-" + n;
      try {
        return mongoTemplate.insert(toRecipe(id, input, null));
      } catch (DuplicateKeyException taken) {
        // try the next suffix
      }
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many recipes with that name");
  }

  /** Replaces a recipe's content. A book recipe keeps its book and pages. */
  public MealRecipe update(final String id, final RecipeInput input) {
    MealRecipe existing = recipes.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recipe"));
    validate(input);
    addIngredients(input.newIngredients());
    return recipes.save(toRecipe(id, input, existing));
  }

  private MealRecipe toRecipe(final String id, final RecipeInput in, final MealRecipe existing) {
    MealRecipe.RecipeSource source = existing != null && "BOOK".equals(existing.source().type())
        ? existing.source()
        : new MealRecipe.RecipeSource(in.sourceType() == null ? "LINK" : in.sourceType(), null,
            List.of(), "LINK".equals(in.sourceType()) ? clip(in.sourceUrl(), 2_000) : null);
    boolean vegetarian = Boolean.TRUE.equals(in.vegetarian());
    List<MealRecipe.IngredientLine> lines = in.ingredients().stream()
        .map(l -> new MealRecipe.IngredientLine(clip(l.text(), 300),
            l.name().strip().toLowerCase(Locale.ROOT),
            l.unit() == null ? null : l.quantity(), l.quantity() == null ? null : l.unit()))
        .toList();
    return new MealRecipe(id, clip(in.name(), 200), source, clip(in.section(), 80), in.category(),
        vegetarian, vegetarian && Boolean.TRUE.equals(in.vegan()), in.servings(), in.perServing(),
        Boolean.TRUE.equals(in.macrosEstimated()), lines, clipAll(in.method()), clipAll(in.tips()),
        clipAll(in.swaps()), in.freezable(), blankToNull(in.mainProtein()), in.photo(),
        existing == null ? clock.instant() : existing.createdAt());
  }

  void validate(final RecipeInput in) {
    if (in == null || in.name() == null || in.name().isBlank()) {
      throw bad("A recipe needs a name");
    }
    if (in.category() == null || !MealBundleImporter.CATEGORIES.contains(in.category())) {
      throw bad("category must be one of " + MealBundleImporter.CATEGORIES);
    }
    if (in.servings() == null || in.servings() < 1 || in.servings() > 50) {
      throw bad("servings must be 1 to 50");
    }
    MealRecipe.Macros m = in.perServing();
    if (m == null || m.kcal() < 0 || m.protein() < 0 || m.carbs() < 0 || m.fat() < 0) {
      throw bad("Per-serving calories and macros are needed, and none can be negative");
    }
    if (in.ingredients() == null || in.ingredients().isEmpty() || in.ingredients().size() > 80) {
      throw bad("A recipe needs 1 to 80 ingredient lines");
    }
    for (MealRecipe.IngredientLine line : in.ingredients()) {
      if (line == null || line.name() == null || line.name().isBlank() || line.text() == null) {
        throw bad("Every ingredient line needs its text and an ingredient name");
      }
      if (line.unit() != null && !MealBundleImporter.UNITS.contains(line.unit())) {
        throw bad("unit must be one of " + MealBundleImporter.UNITS);
      }
    }
    if (in.method() == null || in.method().isEmpty() || in.method().size() > 60) {
      throw bad("A recipe needs 1 to 60 method steps");
    }
    if (in.sourceType() != null && !SOURCES.contains(in.sourceType())) {
      throw bad("sourceType must be one of " + SOURCES);
    }
    if (in.photo() != null && images.read(in.photo()).isEmpty()) {
      throw bad("That photo was not uploaded");
    }
    for (MealIngredient ingredient : in.newIngredients() == null ? List.<MealIngredient>of()
        : in.newIngredients()) {
      if (ingredient.name() == null || ingredient.name().isBlank()
          || ingredient.name().length() > 80) {
        throw bad("A new ingredient needs a name of at most 80 characters");
      }
      if (ingredient.tier() == null || !MealBundleImporter.TIERS.contains(ingredient.tier())) {
        throw bad("A new ingredient's tier must be one of " + MealBundleImporter.TIERS);
      }
      if (ingredient.unit() != null && !MealBundleImporter.UNITS.contains(ingredient.unit())) {
        throw bad("A new ingredient's unit must be one of " + MealBundleImporter.UNITS);
      }
      if (ingredient.aisle() != null && !AISLES.contains(ingredient.aisle())) {
        throw bad("aisle must be one of " + AISLES);
      }
    }
  }

  private void addIngredients(final List<MealIngredient> fresh) {
    if (fresh == null) {
      return;
    }
    for (MealIngredient ingredient : fresh) {
      String name = ingredient.name().strip().toLowerCase(Locale.ROOT);
      if (!ingredients.existsById(name)) {
        try {
          mongoTemplate.insert(new MealIngredient(name, ingredient.unit(), ingredient.tier(),
              ingredient.aisle() == null ? "Other" : ingredient.aisle(), false));
        } catch (DuplicateKeyException alreadyThere) {
          // added concurrently: the first one stands
        }
      }
    }
  }

  static String slug(final String name) {
    String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-+|-+$)", "");
    if (slug.isEmpty()) {
      slug = "recipe";
    }
    return slug.length() > 100 ? slug.substring(0, 100).replaceAll("-+$", "") : slug;
  }

  private static String clip(final String value, final int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static List<String> clipAll(final List<String> values) {
    List<String> out = new ArrayList<>();
    for (String value : values == null ? List.<String>of() : values) {
      if (value != null && !value.isBlank() && out.size() < 60) {
        out.add(clip(value, 2_000));
      }
    }
    return out;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static ResponseStatusException bad(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
