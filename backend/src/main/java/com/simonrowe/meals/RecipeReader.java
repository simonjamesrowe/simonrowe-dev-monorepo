package com.simonrowe.meals;

import com.simonrowe.webfetch.UrlFetcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a recipe from a web page or a photo into a draft the owner reviews before it is saved.
 *
 * <p>A page's schema.org {@code Recipe} JSON-LD is preferred over its text: it is what recipe
 * sites publish for search engines, and it carries the ingredient list and steps without the
 * life story. Either way the model does the structuring, because both sources need their
 * ingredient lines mapped onto the kitchen's standard names and units. Macros the source does
 * not state are estimated and marked as estimated.
 */
@Component
public class RecipeReader {

  static final int MAX_PAGE_TEXT = 15_000;
  private static final String PROMPT = """
      You turn a recipe into structured data for a home cook's recipe library.
      - name: the recipe's own title. category: breakfast, main, snack, drink or dessert.
      - servings: how many portions it makes (1 if unstated). perServing: kcal, protein, carbs
        and fat in grams for ONE portion. If the source states them, copy them and set
        macrosEstimated false; otherwise estimate them sensibly and set macrosEstimated true.
      - ingredients: one per line, for the whole recipe. text is the line as written. name is a
        short, generic, lower-case ingredient name: reuse a name from the list below whenever
        one fits. quantity and unit: unit is g, ml or each; prefer grams when a weight is given;
        a teaspoon is 5 and a tablespoon 15; a count is each; null when unstated.
      - method: the steps in order. tips and swaps: the source's own, or empty.
      - vegetarian/vegan from the ingredients. freezable: true/false only if the source says.
      - mainProtein: chicken, beef, pork, fish, meat-free or egg, or null.
      - newIngredients: every ingredient name you used that is NOT in the list, with its unit,
        tier (tracked for fresh food, meat, fish, dairy, eggs and bread; staple for dry goods,
        tins, frozen, spices, sauces and condiments) and aisle.
      Never invent ingredients or steps the source does not have.

      Known ingredients (name | unit):
      %s
      """;
  private static final String SCHEMA = """
      {"type":"object","additionalProperties":false,
       "required":["name","category","section","servings","perServing","macrosEstimated",
         "vegetarian","vegan","freezable","mainProtein","ingredients","method","tips","swaps",
         "newIngredients"],
       "properties":{
        "name":{"type":"string"},
        "category":{"type":"string","enum":["breakfast","main","snack","drink","dessert"]},
        "section":{"type":["string","null"]},
        "servings":{"type":"integer"},
        "perServing":{"type":"object","additionalProperties":false,
          "required":["kcal","protein","carbs","fat"],
          "properties":{"kcal":{"type":"number"},"protein":{"type":"number"},
            "carbs":{"type":"number"},"fat":{"type":"number"}}},
        "macrosEstimated":{"type":"boolean"},
        "vegetarian":{"type":"boolean"},
        "vegan":{"type":"boolean"},
        "freezable":{"type":["boolean","null"]},
        "mainProtein":{"type":["string","null"],
          "enum":["chicken","beef","pork","fish","meat-free","egg",null]},
        "ingredients":{"type":"array","items":{"type":"object","additionalProperties":false,
          "required":["text","name","quantity","unit"],
          "properties":{"text":{"type":"string"},"name":{"type":"string"},
            "quantity":{"type":["number","null"]},
            "unit":{"type":["string","null"],"enum":["g","ml","each",null]}}}},
        "method":{"type":"array","items":{"type":"string"}},
        "tips":{"type":"array","items":{"type":"string"}},
        "swaps":{"type":"array","items":{"type":"string"}},
        "newIngredients":{"type":"array","items":{"type":"object","additionalProperties":false,
          "required":["name","unit","tier","aisle"],
          "properties":{"name":{"type":"string"},
            "unit":{"type":["string","null"],"enum":["g","ml","each",null]},
            "tier":{"type":"string","enum":["tracked","staple"]},
            "aisle":{"type":"string","enum":["Fruit & veg","Meat & fish","Dairy & eggs",
              "Bakery","Frozen","Cupboard","Other"]}}}}}}
      """;

  private final UrlFetcher fetcher;
  private final MealsModel model;
  private final KitchenService kitchen;
  // A new ingredient's primitive `assumed` is not in the model's schema; Jackson 3 refuses a
  // missing primitive by default, which the app's shared mapper also turns off.
  private final ObjectMapper objectMapper = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .build();

  public RecipeReader(final UrlFetcher fetcher, final MealsModel model,
      final KitchenService kitchen) {
    this.fetcher = fetcher;
    this.model = model;
    this.kitchen = kitchen;
  }

  /** Reads the recipe on a public web page. */
  public RecipeService.RecipeInput fromUrl(final String url) {
    if (!UrlFetcher.isFetchableUrl(url)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That is not a public http(s) address");
    }
    UrlFetcher.FetchedPage page = fetcher.fetchPage(url);
    if (page == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "Could not read that page. It may block automated readers.");
    }
    String source = jsonLdRecipe(page.document());
    if (source == null) {
      Document doc = page.document();
      // Jsoup's body() is annotated non-null today; kept in case a version returns null.
      String text = doc.body() == null ? doc.text() : doc.body().text();
      String clipped = text.length() > MAX_PAGE_TEXT ? text.substring(0, MAX_PAGE_TEXT) : text;
      source = "Page title: %s%n%n%s".formatted(doc.title(), clipped);
    }
    return withSource(structure(source, null, null), "LINK", page.finalUrl());
  }

  /** Reads the recipe in a photo: a cookbook page, a screenshot, a card. */
  public RecipeService.RecipeInput fromImage(final byte[] image, final String type) {
    return withSource(structure("Read the recipe in the attached image.", image, type), "PHOTO",
        null);
  }

  /**
   * The page's schema.org Recipe as compact JSON text, or null when it publishes none. Handles a
   * bare object, an array, and an {@code @graph}.
   */
  String jsonLdRecipe(final Document document) {
    for (Element script : document.select("script[type=application/ld+json]")) {
      try {
        JsonNode recipe = findRecipe(objectMapper.readTree(script.data()));
        if (recipe != null) {
          String json = recipe.toString();
          return json.length() > MAX_PAGE_TEXT ? json.substring(0, MAX_PAGE_TEXT) : json;
        }
      } catch (JacksonException malformed) {
        // a broken block is skipped; another may still hold the recipe
      }
    }
    return null;
  }

  private static JsonNode findRecipe(final JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        JsonNode found = findRecipe(item);
        if (found != null) {
          return found;
        }
      }
      return null;
    }
    if (!node.isObject()) {
      return null;
    }
    JsonNode type = node.path("@type");
    boolean isRecipe = type.isString() && "Recipe".equals(type.asString());
    if (type.isArray()) {
      for (JsonNode each : type) {
        isRecipe |= "Recipe".equals(each.asString(""));
      }
    }
    if (isRecipe) {
      return node;
    }
    return findRecipe(node.path("@graph").isMissingNode() ? null : node.path("@graph"));
  }

  private RecipeService.RecipeInput structure(final String source, final byte[] image,
      final String type) {
    Map<String, MealIngredient> catalogue = kitchen.catalogue();
    String names = catalogue.values().stream()
        .sorted(Comparator.comparing(MealIngredient::name))
        .map(i -> i.name() + " | " + (i.unit() == null ? "-" : i.unit()))
        .collect(Collectors.joining("\n"));
    String json = model.structured(PROMPT.formatted(names), source, image, type, SCHEMA,
        MealsModel.LONG_CALL_TIMEOUT);
    try {
      RecipeService.RecipeInput draft =
          objectMapper.readValue(json, RecipeService.RecipeInput.class);
      return keepOnlyNewIngredients(draft, catalogue);
    } catch (JacksonException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "The model's answer was unreadable");
    }
  }

  /** The model's newIngredients, minus any it listed that the catalogue already has. */
  private static RecipeService.RecipeInput keepOnlyNewIngredients(
      final RecipeService.RecipeInput d, final Map<String, MealIngredient> catalogue) {
    List<MealIngredient> fresh = new ArrayList<>();
    for (MealIngredient i : d.newIngredients() == null ? List.<MealIngredient>of()
        : d.newIngredients()) {
      String name = i.name() == null ? "" : i.name().strip().toLowerCase();
      boolean used = d.ingredients() != null
          && d.ingredients().stream().anyMatch(l -> name.equals(l.name()));
      if (!name.isEmpty() && used && !catalogue.containsKey(name)
          && fresh.stream().noneMatch(f -> f.name().equals(name))) {
        fresh.add(new MealIngredient(name, i.unit(), i.tier(), i.aisle(), false));
      }
    }
    return new RecipeService.RecipeInput(d.name(), d.category(), d.section(), d.servings(),
        d.perServing(), d.macrosEstimated(), d.vegetarian(), d.vegan(), d.freezable(),
        d.mainProtein(), d.ingredients(), d.method(), d.tips(), d.swaps(), d.sourceType(),
        d.sourceUrl(), d.photo(), fresh);
  }

  private static RecipeService.RecipeInput withSource(final RecipeService.RecipeInput d,
      final String type, final String url) {
    return new RecipeService.RecipeInput(d.name(), d.category(), d.section(), d.servings(),
        d.perServing(), d.macrosEstimated(), d.vegetarian(), d.vegan(), d.freezable(),
        d.mainProtein(), d.ingredients(), d.method(), d.tips(), d.swaps(), type, url, null,
        d.newIngredients());
  }
}
