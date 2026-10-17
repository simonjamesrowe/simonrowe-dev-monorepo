package com.simonrowe.meals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.webfetch.UrlFetcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Quick add and the recipe reader, with the model replaced by a canned answer. */
class MealsReadersTest {

  private final MealsModel model = mock(MealsModel.class);
  private final KitchenService kitchen = mock(KitchenService.class);
  private final Clock friday = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);

  {
    when(kitchen.catalogue()).thenReturn(Map.of(
        "egg", new MealIngredient("egg", "each", "tracked", "Dairy & eggs", false),
        "oats", new MealIngredient("oats", "g", "staple", "Cupboard", false)));
  }

  @Test
  void quickAddChecksWhatTheModelProposesAndNamesNewIngredients() {
    when(model.structured(anyString(), anyString(), isNull(), isNull(), anyString()))
        .thenReturn("""
            {"changes":[
              {"name":"Egg","action":"add","quantity":12,"unit":"each","status":null,
               "useBy":"2026-10-30","source":"a dozen eggs"},
              {"name":"oats","action":"status","quantity":null,"unit":null,"status":"low",
               "useBy":null,"source":"low on oats"},
              {"name":"halloumi","action":"add","quantity":250,"unit":"g","status":null,
               "useBy":"not a date","source":"halloumi"},
              {"name":"","action":"add","quantity":1,"unit":"each","status":null,
               "useBy":null,"source":"?"},
              {"name":"milk","action":"drink","quantity":1,"unit":"each","status":null,
               "useBy":null,"source":"?"}]}""");

    QuickAddReader.Proposal proposal = new QuickAddReader(model, kitchen, friday)
        .read("a dozen eggs, low on oats, halloumi", "note", null, null);

    assertThat(proposal.changes()).extracting(InventoryChange::name)
        .containsExactly("egg", "oats", "halloumi");
    assertThat(proposal.changes().getFirst().useBy()).isEqualTo(LocalDate.parse("2026-10-30"));
    assertThat(proposal.changes().get(2).useBy()).isNull();
    assertThat(proposal.newIngredients()).containsExactly("halloumi");
    verify(model).structured(contains("Friday 2026-10-16"), anyString(), isNull(), isNull(),
        anyString());
  }

  @Test
  void quickAddNeedsWordsOrPhoto() {
    QuickAddReader reader = new QuickAddReader(model, kitchen, friday);

    assertThatThrownBy(() -> reader.read(" ", "note", null, null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> reader.read("x".repeat(QuickAddReader.MAX_TEXT + 1), "order", null,
        null)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void recipeReaderPrefersJsonLdAndFindsItInGraph() {
    RecipeReader reader = new RecipeReader(mock(UrlFetcher.class), model, kitchen);
    String html = """
        <html><head>
        <script type="application/ld+json">{ not json </script>
        <script type="application/ld+json">
        {"@context":"https://schema.org","@graph":[
          {"@type":"WebPage","name":"Page"},
          {"@type":["Recipe","Thing"],"name":"Test Omelette",
           "recipeIngredient":["3 eggs"],"recipeInstructions":["Whisk","Cook"]}]}
        </script></head><body>Life story...</body></html>""";

    String found = reader.jsonLdRecipe(Jsoup.parse(html));

    assertThat(found).contains("Test Omelette").contains("3 eggs").doesNotContain("Life story");
    assertThat(reader.jsonLdRecipe(Jsoup.parse("<html><body>no recipe</body></html>"))).isNull();
  }

  @Test
  void recipeReaderKeepsOnlyGenuinelyNewIngredientsItUsed() {
    UrlFetcher fetcher = mock(UrlFetcher.class);
    RecipeReader reader = new RecipeReader(fetcher, model, kitchen);
    when(model.structured(anyString(), anyString(), any(), any(), anyString(),
        eq(MealsModel.LONG_CALL_TIMEOUT))).thenReturn("""
        {"name":"Test Omelette","category":"breakfast","section":null,"servings":1,
         "perServing":{"kcal":300,"protein":20,"carbs":2,"fat":22},"macrosEstimated":true,
         "vegetarian":true,"vegan":false,"freezable":null,"mainProtein":"egg",
         "ingredients":[{"text":"3 eggs","name":"egg","quantity":3,"unit":"each"},
                        {"text":"50g feta","name":"feta","quantity":50,"unit":"g"}],
         "method":["Whisk.","Cook."],"tips":[],"swaps":[],
         "newIngredients":[{"name":"feta","unit":"g","tier":"tracked","aisle":"Dairy & eggs"},
                           {"name":"egg","unit":"each","tier":"tracked","aisle":"Dairy & eggs"},
                           {"name":"saffron","unit":"g","tier":"staple","aisle":"Cupboard"}]}""");

    RecipeService.RecipeInput draft = reader.fromImage(MealBundles.PNG, "image/png");

    assertThat(draft.sourceType()).isEqualTo("PHOTO");
    assertThat(draft.macrosEstimated()).isTrue();
    assertThat(draft.newIngredients()).extracting(MealIngredient::name).containsExactly("feta");
    verify(model).structured(contains("egg | each"), anyString(), eq(MealBundles.PNG),
        eq("image/png"), anyString(), eq(MealsModel.LONG_CALL_TIMEOUT));
  }

  @Test
  void recipeReaderRefusesPrivateAddresses() {
    RecipeReader reader = new RecipeReader(mock(UrlFetcher.class), model, kitchen);

    assertThatThrownBy(() -> reader.fromUrl("http://localhost:8080/admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("public");
    assertThatThrownBy(() -> reader.fromUrl("file:///etc/passwd"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void slugsAreTidyAndStayLinearOnHostileNames() {
    assertThat(RecipeService.slug("  Crème Brûlée (v2)!  ")).isEqualTo("creme-brulee-v2");
    assertThat(RecipeService.slug("---")).isEqualTo("recipe");
    assertThat(RecipeService.slug("a".repeat(99) + " bc")).isEqualTo("a".repeat(99));

    // Only the first 500 characters of a name count, so a name that is all punctuation up to
    // there is a "recipe", however long; one with a word early on keeps it.
    String dashes = "-".repeat(50_000) + "x" + " !".repeat(50_000);
    String early = "x" + " !".repeat(50_000) + "é".repeat(10_000);
    assertThat(assertTimeoutPreemptively(Duration.ofSeconds(2),
        () -> RecipeService.slug(dashes))).isEqualTo("recipe");
    assertThat(assertTimeoutPreemptively(Duration.ofSeconds(2),
        () -> RecipeService.slug(early))).isEqualTo("x");
  }
}
