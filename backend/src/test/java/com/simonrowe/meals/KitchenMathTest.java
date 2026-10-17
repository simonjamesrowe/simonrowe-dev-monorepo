package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The kitchen's arithmetic, on invented recipes. */
class KitchenMathTest {

  static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");
  static final LocalDate TODAY = LocalDate.parse("2026-10-17");

  static final Map<String, MealIngredient> CATALOGUE = Map.of(
      "beef mince", new MealIngredient("beef mince", "g", "tracked", "Meat & fish", false),
      "onion", new MealIngredient("onion", "each", "tracked", "Fruit & veg", false),
      "paprika", new MealIngredient("paprika", "g", "staple", "Cupboard", false),
      "salt", new MealIngredient("salt", null, "staple", "Cupboard", true),
      "oats", new MealIngredient("oats", "g", "staple", "Cupboard", false));

  static MealRecipe chilli() {
    return recipe("chilli", "main", 4, "beef",
        line("500g mince", "beef mince", 500.0, "g"),
        line("2 onions", "onion", 2.0, "each"),
        line("1 tsp paprika", "paprika", 5.0, "g"),
        line("salt", "salt", null, null));
  }

  static MealRecipe oats() {
    return recipe("oats", "breakfast", 1, null, line("40g oats", "oats", 40.0, "g"));
  }

  @Test
  void needsScaleWithPortionsAndMergeRepeatedLines() {
    MealRecipe twice = recipe("x", "main", 2, null,
        line("200g mince", "beef mince", 200.0, "g"), line("100g more", "beef mince", 100.0, "g"));

    assertThat(KitchenMath.needs(twice, 4).get("beef mince"))
        .isEqualTo(new KitchenMath.Need(600.0, "g"));
    assertThat(KitchenMath.needs(chilli(), 2).get("onion")).isEqualTo(new KitchenMath.Need(1.0,
        "each"));
  }

  @Test
  void unrecordedStapleIsUnknownNotMissing() {
    KitchenMath.Coverage cov = KitchenMath.coverage(chilli(), 4, Map.of(
        "beef mince", tracked("beef mince", 500, "g", null),
        "onion", tracked("onion", 1, "each", null)), CATALOGUE);

    assertThat(cov.missing()).isEmpty();
    assertThat(cov.shortOf()).containsExactly("onion");
    assertThat(cov.unknown()).containsExactly("paprika");
    assertThat(cov.lines()).doesNotContainKey("salt");
    assertThat(cov.ratio()).isBetween(0.7, 0.8);
  }

  @Test
  void stapleMarkedOutIsMissingAndLowStillCounts() {
    assertThat(KitchenMath.cover(staple("paprika", "out"), new KitchenMath.Need(5.0, "g"),
        "staple")).isEqualTo(KitchenMath.Cover.MISSING);
    assertThat(KitchenMath.cover(staple("paprika", "low"), new KitchenMath.Need(5.0, "g"),
        "staple")).isEqualTo(KitchenMath.Cover.HAVE);
    assertThat(KitchenMath.cover(null, new KitchenMath.Need(5.0, "g"), "tracked"))
        .isEqualTo(KitchenMath.Cover.MISSING);
  }

  @Test
  void suggestionsLeadWithWhatUsesUpTheFridge() {
    Map<String, MealInventoryItem> stock = Map.of(
        "beef mince", tracked("beef mince", 500, "g", TODAY.plusDays(1)),
        "onion", tracked("onion", 3, "each", null),
        "oats", staple("oats", "have"));

    List<KitchenMath.Suggestion> out = KitchenMath.suggest(List.of(oats(), chilli()), stock,
        CATALOGUE, List.of(), TODAY, NOW);

    assertThat(out.getFirst().recipeId()).isEqualTo("chilli");
    assertThat(out.getFirst().expiring()).containsExactly("beef mince");
    assertThat(out.getFirst().reasons()).first().asString().startsWith("check you have paprika");
  }

  @Test
  void notAgainAndRecentlyCookedFallBehind() {
    Map<String, MealInventoryItem> stock = Map.of("oats", staple("oats", "have"));
    MealRecipe porridge = recipe("porridge", "breakfast", 1, null,
        line("40g oats", "oats", 40.0, "g"));
    List<MealRating> ratings = List.of(
        rating("oats", 5, false, NOW.minusSeconds(86_400 * 20)),
        rating("porridge", 5, true, NOW.minusSeconds(86_400 * 30)));

    List<KitchenMath.Suggestion> out = KitchenMath.suggest(List.of(oats(), porridge), stock,
        CATALOGUE, ratings, TODAY, NOW);

    assertThat(out.getFirst().recipeId()).isEqualTo("porridge");
    assertThat(out.getFirst().reasons()).contains("you rated it 5.0");

    List<KitchenMath.Suggestion> recent = KitchenMath.suggest(List.of(oats(), porridge), stock,
        CATALOGUE, List.of(rating("porridge", 5, true, NOW.minusSeconds(3_600))), TODAY, NOW);
    assertThat(recent.getFirst().recipeId()).isEqualTo("oats");
  }

  @Test
  void theShoppingListIsUpNextMinusTheKitchen() {
    List<MealUpNextItem> upNext = List.of(
        new MealUpNextItem("o|chilli", "o", "chilli", 8, NOW),
        new MealUpNextItem("o|oats", "o", "oats", 1, NOW));
    Map<String, MealInventoryItem> stock = Map.of(
        "beef mince", tracked("beef mince", 400, "g", null),
        "oats", staple("oats", "low"));

    List<KitchenMath.ShoppingLine> lines = KitchenMath.shoppingList(upNext,
        Map.of("chilli", chilli(), "oats", oats()), stock, CATALOGUE, List.of("bin bags"));

    assertThat(lines).extracting(KitchenMath.ShoppingLine::name)
        .containsExactly("onion", "beef mince", "oats", "bin bags", "paprika");
    KitchenMath.ShoppingLine mince = lines.get(1);
    assertThat(mince.toBuy()).isEqualTo(600.0);
    assertThat(mince.why()).isEqualTo("you have 400g");
    assertThat(mince.searchTerm()).isEqualTo("beef mince 600g");
    assertThat(lines.get(0).searchTerm()).isEqualTo("onion 4");
    assertThat(lines.get(2).why()).isEqualTo("running low");
    assertThat(lines.get(4).aisle()).isEqualTo("Check the cupboard");
  }

  @Test
  void nothingIsListedWhenTheKitchenHasEnough() {
    Map<String, MealInventoryItem> stock = Map.of(
        "beef mince", tracked("beef mince", 2000, "g", null),
        "onion", tracked("onion", 6, "each", null),
        "paprika", staple("paprika", "have"));

    assertThat(KitchenMath.shoppingList(List.of(new MealUpNextItem("o|chilli", "o", "chilli", 4,
        NOW)), Map.of("chilli", chilli()), stock, CATALOGUE, List.of())).isEmpty();
  }

  @Test
  void deductionsTakeWhatTheRecipeNeedsCappedAtWhatIsIn() {
    List<KitchenMath.Deduction> out = KitchenMath.defaultDeductions(chilli(), 4,
        Map.of("beef mince", tracked("beef mince", 300, "g", null)), CATALOGUE);

    assertThat(out).extracting(KitchenMath.Deduction::name)
        .containsExactly("beef mince", "onion", "paprika");
    assertThat(out.getFirst().amount()).isEqualTo(300.0);
    assertThat(out.get(2).amount()).isNull();
  }

  @Test
  void eachActionChangesTheRowAsDescribed() {
    MealIngredient mince = CATALOGUE.get("beef mince");
    MealInventoryItem some = tracked("beef mince", 500, "g", TODAY);

    assertThat(KitchenMath.apply("o", some, change("add", 250.0), mince, NOW).quantity())
        .isEqualTo(750.0);
    assertThat(KitchenMath.apply("o", some, change("use", 600.0), mince, NOW).quantity())
        .isZero();
    assertThat(KitchenMath.apply("o", some, change("set", 100.0), mince, NOW).quantity())
        .isEqualTo(100.0);
    assertThat(KitchenMath.apply("o", some, change("add", 1.0), mince, NOW).useBy())
        .isEqualTo(TODAY);
    assertThat(KitchenMath.apply("o", some, change("remove", null), mince, NOW)).isNull();
    MealInventoryItem oats = KitchenMath.apply("o", null,
        new InventoryChange("oats", "status", null, null, "low", null, null),
        CATALOGUE.get("oats"), NOW);
    assertThat(oats.status()).isEqualTo("low");
    assertThat(oats.id()).isEqualTo("o|oats");
  }

  @Test
  void quantitiesReadTheWayShopsListThem() {
    assertThat(KitchenMath.formatQuantity(1200.0, "g")).isEqualTo("1.2kg");
    assertThat(KitchenMath.formatQuantity(2.4, "each")).isEqualTo("3");
    assertThat(KitchenMath.formatQuantity(250.0, "ml")).isEqualTo("250ml");
    assertThat(KitchenMath.formatQuantity(null, "g")).isEmpty();
    assertThat(KitchenMath.describe(change("add", 500.0))).isEqualTo("+ 500g beef mince");
    assertThat(KitchenMath.describe(new InventoryChange("oats", "status", null, null, "out",
        null, null))).isEqualTo("oats: out");
  }

  static InventoryChange change(final String action, final Double quantity) {
    return new InventoryChange("beef mince", action, quantity, "g", null, null, null);
  }

  static MealRecipe recipe(final String id, final String category, final int servings,
      final String protein, final MealRecipe.IngredientLine... lines) {
    return new MealRecipe(id, id, new MealRecipe.RecipeSource("BOOK", "test", List.of(), null),
        "Test", category, false, false, servings, new MealRecipe.Macros(400, 30, 40, 10), false,
        List.of(lines), List.of("Cook."), List.of(), List.of(), null, protein, null, NOW);
  }

  static MealRecipe.IngredientLine line(final String text, final String name, final Double q,
      final String unit) {
    return new MealRecipe.IngredientLine(text, name, q, unit);
  }

  static MealInventoryItem tracked(final String name, final double quantity, final String unit,
      final LocalDate useBy) {
    return new MealInventoryItem("o|" + name, "o", name, "tracked", quantity, unit, null, useBy,
        NOW);
  }

  static MealInventoryItem staple(final String name, final String status) {
    return new MealInventoryItem("o|" + name, "o", name, "staple", null, null, status, null, NOW);
  }

  static MealRating rating(final String recipeId, final int score, final boolean again,
      final Instant at) {
    return new MealRating(recipeId + at, "o", recipeId, at, score, again, List.of(), "");
  }
}
