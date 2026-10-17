package com.simonrowe.meals;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One recipe in the library: from a book, a link or a photo.
 *
 * <p>The library is the app's content, not anyone's personal data, so recipes carry no owner;
 * ratings, the inventory and Up next do. {@code photo} is the file name of an image in
 * {@link MealImageStore}, never a path that the browser could fetch without a token.
 *
 * @param id kebab-case slug, unique across the library
 * @param name display name
 * @param source where the recipe came from
 * @param section the book's own section heading ("One Dish", "Breakfast")
 * @param category breakfast, main, snack, drink or dessert
 * @param vegetarian marked V or VG, and no meat or fish in the ingredients
 * @param vegan marked VG
 * @param servings portions the recipe as written makes
 * @param perServing calories and macros for one portion
 * @param macrosEstimated true when a model estimated the macros rather than the source stating
 *     them
 * @param ingredients ingredient lines for the whole recipe as written
 * @param method numbered steps, in order
 * @param tips the source's tips
 * @param swaps the source's suggested swaps
 * @param freezable true or false when the source says, otherwise null
 * @param mainProtein chicken, beef, pork, fish, meat-free or egg, when there is one
 * @param photo image file name in the image store, or null
 * @param createdAt when the recipe joined the library
 */
@Document(collection = MealRecipe.COLLECTION)
public record MealRecipe(
    @Id String id,
    String name,
    RecipeSource source,
    String section,
    String category,
    boolean vegetarian,
    boolean vegan,
    int servings,
    Macros perServing,
    boolean macrosEstimated,
    List<IngredientLine> ingredients,
    List<String> method,
    List<String> tips,
    List<String> swaps,
    Boolean freezable,
    String mainProtein,
    String photo,
    Instant createdAt
) {

  public static final String COLLECTION = "meal_recipes";

  /**
   * Where a recipe came from.
   *
   * @param type BOOK, LINK or PHOTO
   * @param book book code for a book recipe
   * @param pages page numbers within that book
   * @param url the page a linked recipe was read from
   */
  public record RecipeSource(String type, String book, List<Integer> pages, String url) {
  }

  /**
   * Calories and macros for one portion.
   *
   * @param kcal calories
   * @param protein grams of protein
   * @param carbs grams of carbohydrate
   * @param fat grams of fat
   */
  public record Macros(double kcal, double protein, double carbs, double fat) {
  }

  /**
   * One ingredient line.
   *
   * @param text the line as the source printed it
   * @param name canonical ingredient name, a key into the ingredient catalogue
   * @param quantity amount for the whole recipe in {@code unit}, or null when unstated
   * @param unit g, ml or each, or null when unstated
   */
  public record IngredientLine(String text, String name, Double quantity, String unit) {
  }
}
