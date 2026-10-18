package com.simonrowe.meals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The meals MCP server's tools and prompts. Each tool is one call into the services the app uses,
 * so Claude and the app always agree. Write tools change only the owner's meals data, and kitchen
 * changes are recorded in the kitchen's history like any other.
 */
@Component
public class MealsMcpTools {

  static final String INSTRUCTIONS = """
      Simon's private meals app: a library of about 150 recipes (mostly from Dave Fell's \
      budget high-protein books), his kitchen inventory, an Up next shortlist of what he means to \
      cook, his hearted favourites, and a shopping list built from Up next minus the kitchen. Use \
      what_can_i_cook_now, suggest_meals and get_favourites to answer "what should I eat"; \
      plan_meal to put something in Up next; \
      update_inventory when he says what he bought or used; get_shopping_list for what to buy. \
      Quantities are in each ingredient's unit (g, ml or each); cupboard staples are \
      have/low/out.""";

  /** One tool: its name, what it does, its JSON input schema and its handler. */
  record ToolDef(String name, String description, String inputSchema,
      BiFunction<String, Map<String, Object>, String> handler) {
  }

  /** One prompt. */
  record PromptDef(String name, String description, String text) {
  }

  private final KitchenService kitchen;
  private final MealRecipeRepository recipes;
  private final MealRatingRepository ratings;
  private final RecipeReader reader;
  private final RecipeService recipeService;
  private final MealFavouriteService favourites;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public MealsMcpTools(final KitchenService kitchen, final MealRecipeRepository recipes,
      final MealRatingRepository ratings, final RecipeReader reader,
      final RecipeService recipeService, final MealFavouriteService favourites) {
    this.kitchen = kitchen;
    this.recipes = recipes;
    this.ratings = ratings;
    this.reader = reader;
    this.recipeService = recipeService;
    this.favourites = favourites;
  }

  List<ToolDef> tools() {
    return List.of(
        new ToolDef("search_recipes",
            "Search the recipe library by name or ingredient, optionally by category.", """
            {"type":"object","properties":{
             "query":{"type":"string","description":"words to match in names and ingredients"},
             "category":{"type":"string","enum":["breakfast","main","snack","drink","dessert"]},
             "vegetarian":{"type":"boolean"},
             "limit":{"type":"integer","minimum":1,"maximum":50}}}""", this::searchRecipes),
        new ToolDef("get_recipe",
            "One recipe in full: ingredients, method, macros, and what the kitchen covers.", """
            {"type":"object","required":["id"],"properties":{"id":{"type":"string"},
             "portions":{"type":"integer","minimum":1,"maximum":50}}}""", this::getRecipe),
        new ToolDef("suggest_meals",
            "Ranked suggestions for a meal from what is in, use-by dates, ratings and variety.", """
            {"type":"object","properties":{
             "slot":{"type":"string","enum":["breakfast","main","snack"]},
             "limit":{"type":"integer","minimum":1,"maximum":30}}}""", this::suggest),
        new ToolDef("what_can_i_cook_now",
            "Recipes the kitchen has everything for (staples never recorded count as maybe).", """
            {"type":"object","properties":{
             "slot":{"type":"string","enum":["breakfast","main","snack"]}}}""", this::cookNow),
        new ToolDef("get_inventory", "Everything recorded in the kitchen.",
            "{\"type\":\"object\",\"properties\":{}}", (owner, args) ->
                write(kitchen.inventory(owner).stream().map(MealsMcpTools::item).toList())),
        new ToolDef("update_inventory",
            "Record what was bought, used or is left. Each change: name (canonical ingredient), "
                .concat("action add|use|set|status, quantity+unit (g, ml, each) or status ")
                .concat("have|low|out for cupboard staples, optional useBy YYYY-MM-DD."), """
            {"type":"object","required":["changes"],"properties":{"changes":{"type":"array",
             "items":{"type":"object","required":["name","action"],"properties":{
              "name":{"type":"string"},
              "action":{"type":"string","enum":["add","use","set","status","remove"]},
              "quantity":{"type":"number"},
              "unit":{"type":"string","enum":["g","ml","each"]},
              "status":{"type":"string","enum":["have","low","out"]},
              "useBy":{"type":"string"}}}}}}""", this::updateInventory),
        new ToolDef("get_favourites",
            "The recipes the token's owner has hearted, most recently hearted first.",
            "{\"type\":\"object\",\"properties\":{}}", this::getFavourites),
        new ToolDef("get_up_next", "The recipes queued to cook soon, with portions.",
            "{\"type\":\"object\",\"properties\":{}}", this::upNext),
        new ToolDef("plan_meal", "Add a recipe to Up next, or change its portions.", """
            {"type":"object","required":["recipeId"],"properties":{"recipeId":{"type":"string"},
             "portions":{"type":"integer","minimum":1,"maximum":50}}}""", this::planMeal),
        new ToolDef("remove_from_up_next", "Take a recipe off Up next.", """
            {"type":"object","required":["recipeId"],"properties":{
             "recipeId":{"type":"string"}}}""", this::removeFromUpNext),
        new ToolDef("get_shopping_list",
            "What to buy for Up next, minus what is in, grouped by aisle, with search terms.",
            "{\"type\":\"object\",\"properties\":{}}", this::shoppingList),
        new ToolDef("record_cooked",
            "Record that a recipe was cooked and rated (1-5). Optionally take its ingredients out "
                .concat("of the kitchen at the default amounts."), """
            {"type":"object","required":["recipeId","score"],"properties":{
             "recipeId":{"type":"string"},
             "score":{"type":"integer","minimum":1,"maximum":5},
             "again":{"type":"boolean"},
             "notes":{"type":"string"},
             "portions":{"type":"integer","minimum":1,"maximum":50},
             "takeFromKitchen":{"type":"boolean"}}}""", this::recordCooked),
        new ToolDef("import_recipe_from_url",
            "Read a recipe from a public web page. Returns a draft; pass save=true to add it to "
                .concat("the library once it looks right."), """
            {"type":"object","required":["url"],"properties":{"url":{"type":"string"},
             "save":{"type":"boolean"}}}""", this::importFromUrl));
  }

  List<PromptDef> prompts() {
    return List.of(
        new PromptDef("plan-my-week", "Plan the next few days' cooking from what is in.", """
            Look at my kitchen (get_inventory), my ratings via suggest_meals for main and \
            breakfast, and what is already in Up next (get_up_next). Pick 3 to 5 recipes that \
            use what I have, especially anything near its use-by date, mixing things I rated \
            well with one or two I have not tried. Add them with plan_meal at sensible portions, \
            then show me the shopping list (get_shopping_list) grouped by aisle."""),
        new PromptDef("use-up-whats-expiring", "Find recipes for what is about to go off.", """
            Check get_inventory for anything with a use-by date in the next three days. Suggest \
            recipes that use it (suggest_meals, then get_recipe for detail), say what else I \
            would need, and offer to add the best one to Up next."""));
  }

  private String searchRecipes(final String owner, final Map<String, Object> args) {
    String query = string(args, "query");
    String category = string(args, "category");
    Boolean vegetarian = (Boolean) args.get("vegetarian");
    int limit = integer(args, "limit", 20);
    List<String> terms = query == null ? List.of()
        : List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
    Map<String, Double> scores = new LinkedHashMap<>();
    ratings.findByOwnerIdOrderByAtDesc(owner).forEach(r ->
        scores.merge(r.recipeId(), (double) r.score(), (a, b) -> (a + b) / 2));
    return write(recipes.findAll().stream()
        .filter(r -> category == null || category.equals(r.category()))
        .filter(r -> vegetarian == null || r.vegetarian() == vegetarian)
        .filter(r -> terms.stream().allMatch(t -> r.name().toLowerCase(Locale.ROOT).contains(t)
            || r.ingredients().stream().anyMatch(l -> l.name().contains(t))))
        .sorted(Comparator.comparing(MealRecipe::name))
        .limit(Math.min(limit, 50))
        .map(r -> summary(r, scores.get(r.id())))
        .toList());
  }

  private String getRecipe(final String owner, final Map<String, Object> args) {
    MealRecipe recipe = kitchen.recipe(string(args, "id"));
    int portions = integer(args, "portions", recipe.servings());
    Map<String, Object> out = new LinkedHashMap<>(summary(recipe, null));
    out.put("ingredients", recipe.ingredients().stream().map(MealRecipe.IngredientLine::text)
        .toList());
    out.put("method", recipe.method());
    out.put("tips", recipe.tips());
    out.put("swaps", recipe.swaps());
    KitchenMath.Coverage coverage = kitchen.coverage(owner, recipe.id(), portions);
    out.put("kitchen", Map.of("toGet", join(coverage.missing(), coverage.shortOf()),
        "checkCupboard", coverage.unknown()));
    return write(out);
  }

  private String suggest(final String owner, final Map<String, Object> args) {
    return write(suggestions(owner, string(args, "slot"), integer(args, "limit", 10), false));
  }

  private String cookNow(final String owner, final Map<String, Object> args) {
    return write(suggestions(owner, string(args, "slot"), 15, true));
  }

  private List<Map<String, Object>> suggestions(final String owner, final String slot,
      final int limit, final boolean readyOnly) {
    Map<String, MealRecipe> byId = kitchen.recipeMap();
    return kitchen.suggestions(owner, slot, 200).stream()
        .filter(s -> !readyOnly || s.coverage().toGet() == 0)
        .limit(Math.min(limit, 30))
        .map(s -> {
          MealRecipe r = byId.get(s.recipeId());
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", r.id());
          m.put("name", r.name());
          m.put("kcal", Math.round(r.perServing().kcal()));
          m.put("protein", Math.round(r.perServing().protein()));
          m.put("why", s.reasons());
          m.put("toGet", join(s.coverage().missing(), s.coverage().shortOf()));
          return m;
        }).toList();
  }

  private String updateInventory(final String owner, final Map<String, Object> args) {
    Object raw = args.get("changes");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("changes must be a non-empty list");
    }
    List<InventoryChange> changes = new ArrayList<>();
    for (Object each : list) {
      changes.add(objectMapper.convertValue(each, InventoryChange.class));
    }
    return write(kitchen.applyChanges(owner, changes, "mcp").stream().map(MealsMcpTools::item)
        .toList());
  }

  private String upNext(final String owner, final Map<String, Object> args) {
    Map<String, MealRecipe> byId = kitchen.recipeMap();
    return write(kitchen.upNext(owner).stream().map(u -> Map.of(
        "recipeId", u.recipeId(),
        "name", byId.containsKey(u.recipeId()) ? byId.get(u.recipeId()).name() : u.recipeId(),
        "portions", u.portions())).toList());
  }

  private String getFavourites(final String owner, final Map<String, Object> args) {
    Map<String, MealRecipe> byId = kitchen.recipeMap();
    // A favourite whose recipe is no longer in the library (an older restore) is skipped.
    return write(favourites.list(owner).stream()
        .filter(f -> byId.containsKey(f.recipeId()))
        .map(f -> summary(byId.get(f.recipeId()), null))
        .toList());
  }

  private String removeFromUpNext(final String owner, final Map<String, Object> args) {
    kitchen.unqueue(owner, string(args, "recipeId"));
    return upNext(owner, args);
  }

  private String planMeal(final String owner, final Map<String, Object> args) {
    Object portions = args.get("portions");
    kitchen.queue(owner, string(args, "recipeId"),
        portions instanceof Number n ? n.intValue() : null);
    return upNext(owner, args);
  }

  private String shoppingList(final String owner, final Map<String, Object> args) {
    KitchenService.ShoppingList list = kitchen.shoppingList(owner);
    Map<String, List<Map<String, Object>>> byAisle = new LinkedHashMap<>();
    for (KitchenService.ShoppingItem item : list.items()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("search", item.searchTerm());
      m.put("why", item.line().why());
      m.put("for", item.line().recipes());
      if (item.basket() != null) {
        m.put("inBasket", item.basket());
      }
      byAisle.computeIfAbsent(item.line().aisle(), k -> new ArrayList<>()).add(m);
    }
    return write(byAisle);
  }

  private String recordCooked(final String owner, final Map<String, Object> args) {
    MealRecipe recipe = kitchen.recipe(string(args, "recipeId"));
    int portions = integer(args, "portions", recipe.servings());
    boolean take = Boolean.TRUE.equals(args.get("takeFromKitchen"));
    List<KitchenMath.Deduction> deductions = take
        ? kitchen.deductions(owner, recipe.id(), portions) : List.of();
    Object again = args.get("again");
    MealRating rating = kitchen.cooked(owner, new KitchenService.CookedRequest(recipe.id(),
        portions, integer(args, "score", 0), again instanceof Boolean b ? b : null, List.of(),
        string(args, "notes"), deductions));
    return write(Map.of("recorded", rating.id(), "recipe", recipe.name(),
        "tookFromKitchen", take));
  }

  private String importFromUrl(final String owner, final Map<String, Object> args) {
    RecipeService.RecipeInput draft = reader.fromUrl(string(args, "url"));
    if (Boolean.TRUE.equals(args.get("save"))) {
      MealRecipe saved = recipeService.create(draft);
      return write(Map.of("saved", saved.id(), "name", saved.name()));
    }
    return write(draft);
  }

  private static Map<String, Object> summary(final MealRecipe r, final Double rating) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("name", r.name());
    m.put("category", r.category());
    m.put("servings", r.servings());
    m.put("kcalPerServing", Math.round(r.perServing().kcal()));
    m.put("proteinPerServing", Math.round(r.perServing().protein()));
    m.put("vegetarian", r.vegetarian());
    if (r.freezable() != null) {
      m.put("freezes", r.freezable());
    }
    if (rating != null) {
      m.put("yourRating", rating);
    }
    return m;
  }

  private static Map<String, Object> item(final MealInventoryItem i) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", i.name());
    if (i.isStaple()) {
      m.put("status", i.status());
    } else {
      m.put("quantity", KitchenMath.formatQuantity(i.quantity(), i.unit()));
      if (i.useBy() != null) {
        m.put("useBy", i.useBy().toString());
      }
    }
    return m;
  }

  private static List<String> join(final List<String> a, final List<String> b) {
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static String string(final Map<String, Object> args, final String key) {
    Object value = args.get(key);
    return value == null ? null : value.toString();
  }

  private static int integer(final Map<String, Object> args, final String key, final int other) {
    return args.get(key) instanceof Number n ? n.intValue() : other;
  }

  private String write(final Object value) {
    return objectMapper.writeValueAsString(value);
  }
}
