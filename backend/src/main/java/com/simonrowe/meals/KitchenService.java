package com.simonrowe.meals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The owner's kitchen, Up next and shopping list, on top of {@link KitchenMath}. Every method
 * takes the owner's Auth0 subject and touches only that owner's rows; the recipe library is
 * shared.
 */
@Service
public class KitchenService {

  static final Set<String> ACTIONS = Set.of("add", "use", "set", "status", "remove");
  static final Set<String> STATUSES = Set.of("have", "low", "out");
  static final Set<String> SHOPS = Set.of("Ocado", "Amazon", "Deliveroo");
  static final Set<String> SLOTS = Set.of("breakfast", "main", "snack");
  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final int MAX_NAME = 80;

  private final MealRecipeRepository recipes;
  private final MealIngredientRepository ingredients;
  private final MealTemplateRepository templates;
  private final MealRatingRepository ratings;
  private final MealInventoryItemRepository inventory;
  private final MealInventoryEventRepository events;
  private final MealUpNextItemRepository upNext;
  private final MealShoppingMarkRepository shopping;
  private final Clock clock;

  public KitchenService(
      final MealRecipeRepository recipes,
      final MealIngredientRepository ingredients,
      final MealTemplateRepository templates,
      final MealRatingRepository ratings,
      final MealInventoryItemRepository inventory,
      final MealInventoryEventRepository events,
      final MealUpNextItemRepository upNext,
      final MealShoppingMarkRepository shopping,
      final Clock clock
  ) {
    this.recipes = recipes;
    this.ingredients = ingredients;
    this.templates = templates;
    this.ratings = ratings;
    this.inventory = inventory;
    this.events = events;
    this.upNext = upNext;
    this.shopping = shopping;
    this.clock = clock;
  }

  // ---- Kitchen ----------------------------------------------------------------------------

  public List<MealInventoryItem> inventory(final String ownerId) {
    return inventory.findByOwnerId(ownerId).stream()
        .sorted((a, b) -> a.name().compareTo(b.name()))
        .toList();
  }

  public List<MealInventoryEvent> history(final String ownerId, final int limit) {
    return events.findByOwnerIdOrderByAtDesc(ownerId, PageRequest.of(0, Math.min(limit, 200)));
  }

  /**
   * Applies reviewed changes and records them as one event. Every change is validated before any
   * is written, so a bad one refuses the lot.
   */
  public List<MealInventoryItem> applyChanges(final String ownerId,
      final List<InventoryChange> changes, final String kind) {
    if (changes == null || changes.isEmpty()) {
      return inventory(ownerId);
    }
    List<InventoryChange> clean = changes.stream().map(KitchenService::validated).toList();
    Map<String, MealIngredient> catalogue = catalogue();
    Instant now = clock.instant();
    for (InventoryChange change : clean) {
      String id = MealInventoryItem.idFor(ownerId, change.name());
      MealInventoryItem prev = inventory.findById(id).orElse(null);
      MealInventoryItem next = KitchenMath.apply(ownerId, prev, change,
          catalogue.get(change.name()), now);
      if (next == null) {
        inventory.deleteById(id);
      } else {
        inventory.save(next);
      }
    }
    record(ownerId, kind, clean.stream().map(KitchenMath::describe)
        .collect(Collectors.joining(", ")), clean);
    return inventory(ownerId);
  }

  static InventoryChange validated(final InventoryChange change) {
    if (change == null || change.name() == null || change.name().isBlank()) {
      throw bad("A change needs an ingredient name");
    }
    String name = change.name().strip().toLowerCase();
    if (name.length() > MAX_NAME) {
      throw bad("Ingredient names are at most " + MAX_NAME + " characters");
    }
    if (change.action() == null || !ACTIONS.contains(change.action())) {
      throw bad("action must be one of " + ACTIONS);
    }
    if (change.status() != null && !STATUSES.contains(change.status())) {
      throw bad("status must be one of " + STATUSES);
    }
    if (change.unit() != null && !MealBundleImporter.UNITS.contains(change.unit())) {
      throw bad("unit must be one of " + MealBundleImporter.UNITS);
    }
    if (change.quantity() != null && (change.quantity() < 0 || change.quantity() > 1_000_000)) {
      throw bad("quantity must be between 0 and 1,000,000");
    }
    return new InventoryChange(name, change.action(), change.quantity(), change.unit(),
        change.status(), change.useBy(), change.source());
  }

  // ---- Cooking ----------------------------------------------------------------------------

  /** What the kitchen covers for every recipe at its own servings. */
  public Map<String, KitchenMath.Coverage> coverageOfAll(final String ownerId) {
    Map<String, MealInventoryItem> stock = stock(ownerId);
    Map<String, MealIngredient> catalogue = catalogue();
    Map<String, KitchenMath.Coverage> out = new LinkedHashMap<>();
    for (MealRecipe recipe : recipes.findAll()) {
      out.put(recipe.id(), KitchenMath.coverage(recipe, recipe.servings(), stock, catalogue));
    }
    return out;
  }

  public KitchenMath.Coverage coverage(final String ownerId, final String recipeId,
      final int portions) {
    return KitchenMath.coverage(recipe(recipeId), portions(portions), stock(ownerId), catalogue());
  }

  /** Suggestions for one meal (breakfast, main or snack), best first. */
  public List<KitchenMath.Suggestion> suggestions(final String ownerId, final String slot,
      final int limit) {
    if (slot != null && !SLOTS.contains(slot)) {
      throw bad("slot must be one of " + SLOTS);
    }
    Set<String> categories = slot == null ? null : switch (slot) {
      case "breakfast" -> Set.of("breakfast");
      case "main" -> Set.of("main");
      default -> Set.of("snack", "dessert", "drink");
    };
    List<MealRecipe> pool = recipes.findAll().stream()
        .filter(r -> categories == null || categories.contains(r.category()))
        .toList();
    Instant now = clock.instant();
    return KitchenMath.suggest(pool, stock(ownerId), catalogue(),
            ratings.findByOwnerIdOrderByAtDesc(ownerId), LocalDate.ofInstant(now, LONDON), now)
        .stream().limit(Math.max(1, Math.min(limit, 200))).toList();
  }

  public List<KitchenMath.Deduction> deductions(final String ownerId, final String recipeId,
      final int portions) {
    return KitchenMath.defaultDeductions(recipe(recipeId), portions(portions), stock(ownerId),
        catalogue());
  }

  /**
   * Records a cook: the rating, what it took out of the kitchen, and that it is no longer up
   * next. The deductions are the owner's reviewed ones, not recomputed.
   */
  public MealRating cooked(final String ownerId, final CookedRequest request) {
    if (request.score() < 1 || request.score() > 5) {
      throw bad("score must be 1 to 5");
    }
    final MealRecipe recipe = recipe(request.recipeId());
    Instant now = clock.instant();
    List<InventoryChange> changes = new ArrayList<>();
    Map<String, MealInventoryItem> stock = stock(ownerId);
    for (KitchenMath.Deduction d : request.deductions() == null ? List.<KitchenMath.Deduction>of()
        : request.deductions()) {
      MealInventoryItem have = stock.get(d.name());
      if (have == null) {
        continue;
      }
      if (have.isStaple()) {
        if (d.ranOut()) {
          changes.add(new InventoryChange(d.name(), "status", null, null, "out", null, null));
        }
      } else if (d.ranOut()) {
        changes.add(new InventoryChange(d.name(), "set", 0.0, have.unit(), null, null, null));
      } else if (d.amount() != null && d.amount() > 0) {
        changes.add(new InventoryChange(d.name(), "use", d.amount(), have.unit(), null, null,
            null));
      }
    }
    Map<String, MealIngredient> catalogue = catalogue();
    for (InventoryChange change : changes) {
      String id = MealInventoryItem.idFor(ownerId, change.name());
      MealInventoryItem next = KitchenMath.apply(ownerId, inventory.findById(id).orElse(null),
          change, catalogue.get(change.name()), now);
      inventory.save(next);
    }
    record(ownerId, "cooked", "cooked " + recipe.name(), changes);
    upNext.deleteById(MealUpNextItem.idFor(ownerId, recipe.id()));
    return ratings.save(new MealRating(UUID.randomUUID().toString(), ownerId, recipe.id(), now,
        request.score(), request.again() == null || request.again(),
        request.tags() == null ? List.of() : List.copyOf(request.tags()),
        request.notes() == null ? "" : request.notes().strip()));
  }

  /**
   * A cook to record.
   *
   * @param recipeId the recipe cooked
   * @param portions how many portions were made
   * @param score 1 to 5
   * @param again whether to make it again; null means yes
   * @param tags quick tags
   * @param notes notes for next time
   * @param deductions what it took out of the kitchen, as reviewed
   */
  public record CookedRequest(String recipeId, int portions, int score, Boolean again,
      List<String> tags, String notes, List<KitchenMath.Deduction> deductions) {
  }

  // ---- Up next ----------------------------------------------------------------------------

  public List<MealUpNextItem> upNext(final String ownerId) {
    return upNext.findByOwnerIdOrderByAddedAtAsc(ownerId);
  }

  public MealUpNextItem queue(final String ownerId, final String recipeId, final Integer portions) {
    MealRecipe recipe = recipe(recipeId);
    String id = MealUpNextItem.idFor(ownerId, recipeId);
    MealUpNextItem prev = upNext.findById(id).orElse(null);
    int count = portions == null ? recipe.servings() : portions(portions);
    return upNext.save(new MealUpNextItem(id, ownerId, recipeId, count,
        prev == null ? clock.instant() : prev.addedAt()));
  }

  public void unqueue(final String ownerId, final String recipeId) {
    upNext.deleteById(MealUpNextItem.idFor(ownerId, recipeId));
  }

  /** Queues every recipe of one of Dave's weeks that is not queued already. */
  public List<MealUpNextItem> queueTemplate(final String ownerId, final String templateId) {
    MealTemplate template = templates.findById(templateId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such week"));
    for (String recipeId : template.recipeIds()) {
      if (recipes.existsById(recipeId)
          && !upNext.existsById(MealUpNextItem.idFor(ownerId, recipeId))) {
        queue(ownerId, recipeId, null);
      }
    }
    return upNext(ownerId);
  }

  // ---- Shopping ---------------------------------------------------------------------------

  /** The list, with each line's basket mark. */
  public ShoppingList shoppingList(final String ownerId) {
    List<MealShoppingMark> marks = shopping.findByOwnerId(ownerId);
    List<String> extras = marks.stream().filter(MealShoppingMark::extra)
        .map(MealShoppingMark::name).sorted().toList();
    Map<String, String> basket = new HashMap<>();
    marks.stream().filter(m -> m.shop() != null).forEach(m -> basket.put(m.name(), m.shop()));
    List<KitchenMath.ShoppingLine> lines = KitchenMath.shoppingList(upNext(ownerId),
        recipeMap(), stock(ownerId), catalogue(), extras);
    List<ShoppingItem> items = lines.stream()
        .map(l -> new ShoppingItem(l, l.searchTerm(), basket.get(l.name())))
        .toList();
    return new ShoppingList(items, upNext(ownerId).size());
  }

  /**
   * The shopping list.
   *
   * @param items each line with its search term and basket
   * @param upNextCount how many recipes it was built from
   */
  public record ShoppingList(List<ShoppingItem> items, int upNextCount) {
  }

  /**
   * One line of the list.
   *
   * @param line what to buy and why
   * @param searchTerm what to type into the shop's search box
   * @param basket the shop whose basket it is in, or null
   */
  public record ShoppingItem(KitchenMath.ShoppingLine line, String searchTerm, String basket) {
  }

  public void markInBasket(final String ownerId, final String name, final String shop) {
    if (shop != null && !SHOPS.contains(shop)) {
      throw bad("shop must be one of " + SHOPS);
    }
    String clean = cleanName(name);
    String id = MealShoppingMark.idFor(ownerId, clean);
    MealShoppingMark prev = shopping.findById(id).orElse(null);
    boolean extra = prev != null && prev.extra();
    if (shop == null && !extra) {
      shopping.deleteById(id);
      return;
    }
    shopping.save(new MealShoppingMark(id, ownerId, clean, extra, shop, clock.instant()));
  }

  public void addExtra(final String ownerId, final String name) {
    String clean = cleanName(name);
    String id = MealShoppingMark.idFor(ownerId, clean);
    MealShoppingMark prev = shopping.findById(id).orElse(null);
    shopping.save(new MealShoppingMark(id, ownerId, clean, true,
        prev == null ? null : prev.shop(), clock.instant()));
  }

  public void removeExtra(final String ownerId, final String name) {
    shopping.deleteById(MealShoppingMark.idFor(ownerId, cleanName(name)));
  }

  /**
   * Everything ticked into a basket has arrived: add each to the kitchen at the listed amount
   * and clear its mark. Hand-added extras that are not ingredients are simply cleared.
   */
  public List<MealInventoryItem> delivered(final String ownerId) {
    List<ShoppingItem> inBasket = shoppingList(ownerId).items().stream()
        .filter(i -> i.basket() != null).toList();
    Map<String, MealIngredient> catalogue = catalogue();
    List<InventoryChange> changes = new ArrayList<>();
    for (ShoppingItem item : inBasket) {
      KitchenMath.ShoppingLine line = item.line();
      boolean isIngredient = catalogue.containsKey(line.name()) || !line.recipes().isEmpty();
      if (!isIngredient) {
        continue;
      }
      changes.add("staple".equals(line.tier())
          ? new InventoryChange(line.name(), "status", null, null, "have", null, null)
          : new InventoryChange(line.name(), "add", line.toBuy() == null ? 1.0 : line.toBuy(),
              line.unit(), null, null, null));
    }
    if (!changes.isEmpty()) {
      applyChanges(ownerId, changes, "delivered");
    }
    inBasket.forEach(i -> shopping.deleteById(MealShoppingMark.idFor(ownerId, i.line().name())));
    return inventory(ownerId);
  }

  /** Clears every basket mark, after a pasted order was read into the kitchen instead. */
  public void clearBasket(final String ownerId) {
    // A hand-added extra that went into a basket has been bought too, so it goes with its mark.
    shopping.findByOwnerId(ownerId).stream()
        .filter(mark -> mark.shop() != null)
        .forEach(mark -> shopping.deleteById(mark.id()));
  }

  // ---- helpers ----------------------------------------------------------------------------

  Map<String, MealIngredient> catalogue() {
    return ingredients.findAll().stream()
        .collect(Collectors.toMap(MealIngredient::name, Function.identity(), (a, b) -> a));
  }

  Map<String, MealRecipe> recipeMap() {
    return recipes.findAll().stream()
        .collect(Collectors.toMap(MealRecipe::id, Function.identity(), (a, b) -> a));
  }

  Map<String, MealInventoryItem> stock(final String ownerId) {
    return inventory.findByOwnerId(ownerId).stream()
        .collect(Collectors.toMap(MealInventoryItem::name, Function.identity(), (a, b) -> a));
  }

  MealRecipe recipe(final String recipeId) {
    return recipes.findById(recipeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recipe"));
  }

  private void record(final String ownerId, final String kind, final String summary,
      final List<InventoryChange> changes) {
    if (changes.isEmpty() && !"cooked".equals(kind)) {
      return;
    }
    events.save(new MealInventoryEvent(UUID.randomUUID().toString(), ownerId, clock.instant(),
        kind, summary.length() > 500 ? summary.substring(0, 500) + "…" : summary,
        List.copyOf(changes)));
  }

  private static int portions(final int portions) {
    if (portions < 1 || portions > 50) {
      throw bad("portions must be 1 to 50");
    }
    return portions;
  }

  private static String cleanName(final String name) {
    if (name == null || name.isBlank()) {
      throw bad("A name is needed");
    }
    String clean = name.strip().toLowerCase();
    if (clean.length() > MAX_NAME) {
      throw bad("Names are at most " + MAX_NAME + " characters");
    }
    return clean;
  }

  private static ResponseStatusException bad(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
