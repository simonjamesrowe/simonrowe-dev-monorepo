package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.meals.MealBundleImporter.BundleIngredient;
import com.simonrowe.meals.MealBundleImporter.BundleLine;
import com.simonrowe.meals.MealBundleImporter.BundleRecipe;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

/** The validation a bundle recipe or ingredient must pass, one rule at a time. */
class MealBundleImporterTest {

  private static final BundleRecipe VALID = new BundleRecipe("test-chilli", "Test Chilli",
      "book", List.of(4), null, "Mains", "main", false, false, 4,
      new MealRecipe.Macros(450, 40, 35, 15),
      List.of(new BundleLine("500g mince", "beef mince", 500.0, "g")),
      List.of("Cook it."), List.of(), List.of(), true, "beef");

  @Test
  void wellFormedRecipePasses() {
    assertThat(MealBundleImporter.problem(VALID)).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenRecipes")
  void eachRuleRefusesItsOwnFault(final String rule, final BundleRecipe recipe) {
    assertThat(MealBundleImporter.problem(recipe)).as(rule).isNotNull();
  }

  static Stream<Arguments> brokenRecipes() {
    return Stream.of(
        broken("id with spaces", r -> with(r, "id", "Bad Id")),
        broken("no id", r -> with(r, "id", null)),
        broken("blank name", r -> with(r, "name", " ")),
        broken("unknown category", r -> with(r, "category", "supper")),
        broken("zero servings", r -> with(r, "servings", 0)),
        broken("too many servings", r -> with(r, "servings", 51)),
        broken("no macros", r -> with(r, "perServing", null)),
        broken("negative protein", r -> with(r, "perServing", new MealRecipe.Macros(1, -1, 1, 1))),
        broken("no ingredients", r -> with(r, "ingredients", List.of())),
        broken("too many ingredients", r -> with(r, "ingredients",
            Collections.nCopies(81, new BundleLine("x", "x", null, null)))),
        broken("unnamed ingredient", r -> with(r, "ingredients",
            List.of(new BundleLine("x", " ", null, null)))),
        broken("unknown unit", r -> with(r, "ingredients",
            List.of(new BundleLine("1 cup", "rice", 1.0, "cup")))),
        broken("no method", r -> with(r, "method", List.of())));
  }

  @Test
  void ingredientsNeedNameTierAndKnownUnit() {
    assertThat(MealBundleImporter.problem(
        new BundleIngredient("oats", "g", "staple", "Cupboard", false))).isNull();
    assertThat(MealBundleImporter.problem(
        new BundleIngredient(" ", "g", "staple", "Cupboard", false))).isNotNull();
    assertThat(MealBundleImporter.problem(
        new BundleIngredient("oats", "g", "pantry", "Cupboard", false))).isNotNull();
    assertThat(MealBundleImporter.problem(
        new BundleIngredient("oats", "cup", "staple", "Cupboard", false))).isNotNull();
  }

  @Test
  void zipEntriesAreKeyedByNameAndNeverWrittenAnywhere() {
    Map<String, byte[]> entries = MealBundleImporter.unzip(MealBundles.zip(Map.of(
        "../../outside.txt", "x".getBytes(StandardCharsets.UTF_8))));

    assertThat(entries).containsOnlyKeys("../../outside.txt");
  }

  @Test
  void tooManyFilesIsRefused() {
    Map<String, byte[]> many = new HashMap<>();
    for (int i = 0; i <= MealBundleImporter.MAX_ENTRIES; i++) {
      many.put("f" + i, new byte[] {1});
    }

    assertThatThrownBy(() -> MealBundleImporter.unzip(MealBundles.zip(many)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Too many files");
  }

  @Test
  void oversizedEntryIsRefused() {
    byte[] big = new byte[(int) MealBundleImporter.MAX_ENTRY_BYTES + 1];

    assertThatThrownBy(() -> MealBundleImporter.unzip(MealBundles.zip(Map.of("big", big))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("too large");
  }

  private static Arguments broken(final String rule, final UnaryOperator<BundleRecipe> change) {
    return Arguments.of(rule, change.apply(VALID));
  }

  private static BundleRecipe with(final BundleRecipe r, final String field, final Object value) {
    return new BundleRecipe(
        field.equals("id") ? (String) value : r.id(),
        field.equals("name") ? (String) value : r.name(),
        r.book(), r.pages(), r.photo(), r.section(),
        field.equals("category") ? (String) value : r.category(),
        r.vegetarian(), r.vegan(),
        field.equals("servings") ? (Integer) value : r.servings(),
        field.equals("perServing") ? (MealRecipe.Macros) value : r.perServing(),
        field.equals("ingredients") ? castLines(value) : r.ingredients(),
        field.equals("method") ? castStrings(value) : r.method(),
        r.tips(), r.swaps(), r.freezable(), r.mainProtein());
  }

  @SuppressWarnings("unchecked")
  private static List<BundleLine> castLines(final Object value) {
    return (List<BundleLine>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> castStrings(final Object value) {
    return (List<String>) value;
  }
}
