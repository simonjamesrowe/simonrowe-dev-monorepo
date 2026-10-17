package com.simonrowe.meals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The kitchen's arithmetic, kept free of storage so it can be tested on its own and shared by
 * the app's API and the MCP server: what a recipe needs, how much of it is in, what to suggest,
 * what to buy, and what cooking takes out.
 *
 * <p>Two rules decide most of the behaviour. A cupboard staple the owner has never recorded is
 * <em>unknown</em>, not missing: most kitchens have paprika, and counting it as missing made
 * almost nothing cookable. And an {@code assumed} ingredient (salt, pepper, water) is never
 * asked for or listed.
 */
public final class KitchenMath {

  static final List<String> AISLE_ORDER = List.of("Fruit & veg", "Meat & fish", "Dairy & eggs",
      "Bakery", "Frozen", "Cupboard", "Other", "Added by you", "Check the cupboard");
  private static final double EPSILON = 1e-9;

  private KitchenMath() {
  }

  /** How much of one ingredient a cook needs. A null quantity means "some", unstated. */
  public record Need(Double quantity, String unit) {
  }

  /** Whether the kitchen covers one ingredient. */
  public enum Cover {
    HAVE, SHORT, MISSING, UNKNOWN
  }

  /**
   * How much of a recipe the kitchen covers.
   *
   * @param ratio 0 to 1, tracked ingredients weighing double
   * @param missing ingredients not in at all
   * @param shortOf ingredients in, but not enough
   * @param unknown cupboard staples never recorded: probably in, worth checking
   * @param lines each counted ingredient's state, in recipe order
   */
  public record Coverage(double ratio, List<String> missing, List<String> shortOf,
      List<String> unknown, Map<String, Cover> lines) {

    public int toGet() {
      return missing.size() + shortOf.size();
    }
  }

  /**
   * One suggestion and why.
   *
   * @param recipeId the recipe
   * @param score higher is better
   * @param coverage what the kitchen covers
   * @param reasons short phrases for people
   * @param expiring ingredients it uses that are near their use-by date
   */
  public record Suggestion(String recipeId, double score, Coverage coverage, List<String> reasons,
      List<String> expiring) {
  }

  /**
   * One shopping-list line.
   *
   * @param name what to buy
   * @param aisle where it sits on the list
   * @param tier tracked or staple
   * @param toBuy how much, or null when the amount is not known
   * @param unit the amount's unit
   * @param why why it is on the list
   * @param recipes the Up next recipes that need it
   */
  public record ShoppingLine(String name, String aisle, String tier, Double toBuy, String unit,
      String why, List<String> recipes) {

    /** The short text to type into a shop's search box. */
    public String searchTerm() {
      String amount = formatQuantity(toBuy, unit);
      return amount.isEmpty() ? name : name + " " + amount;
    }
  }

  /**
   * What cooking takes out of the kitchen, before the owner adjusts it.
   *
   * @param name ingredient
   * @param tier tracked or staple
   * @param amount amount to take off, tracked only
   * @param unit its unit
   * @param ranOut whether it is now used up
   */
  public record Deduction(String name, String tier, Double amount, String unit, boolean ranOut) {
  }

  /** What one cook needs, per ingredient, scaled to the portions being made. */
  public static Map<String, Need> needs(final MealRecipe recipe, final int portions) {
    double scale = (double) portions / Math.max(1, recipe.servings());
    Map<String, Need> out = new LinkedHashMap<>();
    for (MealRecipe.IngredientLine line : recipe.ingredients()) {
      Double quantity = line.quantity() == null ? null : line.quantity() * scale;
      Need prev = out.get(line.name());
      if (prev == null) {
        out.put(line.name(), new Need(quantity, line.unit()));
      } else if (prev.quantity() != null && quantity != null
          && Objects.equals(prev.unit(), line.unit())) {
        out.put(line.name(), new Need(prev.quantity() + quantity, prev.unit()));
      }
    }
    return out;
  }

  /** Whether the kitchen covers one need. */
  public static Cover cover(final MealInventoryItem item, final Need need, final String tier) {
    if (item == null) {
      return "staple".equals(tier) ? Cover.UNKNOWN : Cover.MISSING;
    }
    if (item.isStaple()) {
      return "out".equals(item.status()) ? Cover.MISSING : Cover.HAVE;
    }
    double have = item.quantity() == null ? 0 : item.quantity();
    if (have <= 0) {
      return Cover.MISSING;
    }
    if (need.quantity() == null || have + EPSILON >= need.quantity()) {
      return Cover.HAVE;
    }
    return Cover.SHORT;
  }

  /** How much of a recipe the kitchen covers, at the given portions. */
  public static Coverage coverage(final MealRecipe recipe, final int portions,
      final Map<String, MealInventoryItem> inventory, final Map<String, MealIngredient> catalogue) {
    double total = 0;
    double got = 0;
    List<String> missing = new ArrayList<>();
    List<String> shortOf = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    Map<String, Cover> lines = new LinkedHashMap<>();
    for (Map.Entry<String, Need> entry : needs(recipe, portions).entrySet()) {
      MealIngredient ingredient = catalogue.get(entry.getKey());
      if (ingredient != null && ingredient.assumed()) {
        continue;
      }
      String tier = ingredient == null ? "tracked" : ingredient.tier();
      double weight = "staple".equals(tier) ? 1 : 2;
      Cover state = cover(inventory.get(entry.getKey()), entry.getValue(), tier);
      total += weight;
      switch (state) {
        case HAVE -> got += weight;
        case SHORT -> {
          got += weight / 2;
          shortOf.add(entry.getKey());
        }
        case UNKNOWN -> {
          got += weight * 0.75;
          unknown.add(entry.getKey());
        }
        default -> missing.add(entry.getKey());
      }
      lines.put(entry.getKey(), state);
    }
    return new Coverage(total == 0 ? 1 : got / total, missing, shortOf, unknown, lines);
  }

  /**
   * Scores every recipe for "what shall I eat": coverage of the kitchen first, then anything
   * near its use-by date, then how much the owner liked it (or similar recipes), then variety.
   * A recipe last marked "not again" is pushed well down.
   */
  public static List<Suggestion> suggest(final List<MealRecipe> recipes,
      final Map<String, MealInventoryItem> inventory, final Map<String, MealIngredient> catalogue,
      final List<MealRating> ratings, final LocalDate today, final Instant now) {
    Map<String, Long> expiring = new HashMap<>();
    for (MealInventoryItem item : inventory.values()) {
      if (!item.isStaple() && item.inStock() && item.useBy() != null) {
        long days = ChronoUnit.DAYS.between(today, item.useBy());
        if (days <= 3) {
          expiring.put(item.name(), days);
        }
      }
    }
    Map<String, List<MealRating>> byRecipe = new HashMap<>();
    for (MealRating rating : ratings) {
      byRecipe.computeIfAbsent(rating.recipeId(), k -> new ArrayList<>()).add(rating);
    }
    Map<String, MealRecipe> byId = new HashMap<>();
    recipes.forEach(r -> byId.put(r.id(), r));

    List<Suggestion> out = new ArrayList<>();
    for (MealRecipe recipe : recipes) {
      Coverage cov = coverage(recipe, recipe.servings(), inventory, catalogue);
      List<String> reasons = new ArrayList<>();
      double score = cov.ratio() * 60;

      Set<String> uses = new HashSet<>();
      recipe.ingredients().forEach(l -> uses.add(l.name()));
      List<String> soon = expiring.keySet().stream().filter(uses::contains).sorted().toList();
      if (!soon.isEmpty()) {
        long soonest = soon.stream().mapToLong(expiring::get).min().orElse(9);
        score += soonest <= 1 ? 25 : 12;
        reasons.add("uses " + String.join(", ", soon) + " before it goes off");
      }

      List<MealRating> mine = byRecipe.getOrDefault(recipe.id(), List.of());
      MealRating latest = mine.stream().max(Comparator.comparing(MealRating::at)).orElse(null);
      if (!mine.isEmpty()) {
        double avg = mine.stream().mapToInt(MealRating::score).average().orElse(3);
        score += (avg - 3) * 8;
        if (avg >= 4) {
          reasons.add("you rated it %.1f".formatted(avg));
        }
        if (!latest.again()) {
          score -= 20;
        }
        double daysAgo = Duration.between(latest.at(), now).toHours() / 24.0;
        if (daysAgo < 5) {
          score -= 25;
        } else if (daysAgo < 10) {
          score -= 10;
        }
      } else if (recipe.mainProtein() != null) {
        double similar = ratings.stream()
            .filter(r -> {
              MealRecipe other = byId.get(r.recipeId());
              return other != null && recipe.category().equals(other.category())
                  && recipe.mainProtein().equals(other.mainProtein());
            })
            .mapToInt(MealRating::score).average().orElse(Double.NaN);
        if (!Double.isNaN(similar)) {
          score += (similar - 3) * 4;
          if (similar >= 4) {
            reasons.add("you liked other " + recipe.mainProtein() + " dishes");
          }
        }
      }

      if (cov.toGet() == 0) {
        reasons.addFirst(cov.unknown().isEmpty() ? "you have everything"
            : "check you have " + String.join(", ", cov.unknown().stream().limit(3).toList())
            + (cov.unknown().size() > 3 ? "…" : ""));
      } else if (cov.toGet() <= 2) {
        List<String> toGet = new ArrayList<>(cov.missing());
        toGet.addAll(cov.shortOf());
        reasons.addFirst("missing " + String.join(", ", toGet));
      }
      out.add(new Suggestion(recipe.id(), score, cov, reasons, soon));
    }
    out.sort(Comparator.comparingDouble(Suggestion::score).reversed()
        .thenComparing(Suggestion::recipeId));
    return out;
  }

  /**
   * Up next, minus the kitchen. A staple appears only when low, out, or never recorded (as
   * "check the cupboard"); a tracked ingredient appears for whatever the kitchen is short of.
   */
  public static List<ShoppingLine> shoppingList(final List<MealUpNextItem> upNext,
      final Map<String, MealRecipe> recipes, final Map<String, MealInventoryItem> inventory,
      final Map<String, MealIngredient> catalogue, final List<String> extras) {
    record Total(Double quantity, String unit, List<String> recipes) {
    }

    Map<String, Total> totals = new LinkedHashMap<>();
    for (MealUpNextItem item : upNext) {
      MealRecipe recipe = recipes.get(item.recipeId());
      if (recipe == null) {
        continue;
      }
      for (Map.Entry<String, Need> entry : needs(recipe, item.portions()).entrySet()) {
        Need need = entry.getValue();
        Total prev = totals.get(entry.getKey());
        if (prev == null) {
          totals.put(entry.getKey(), new Total(need.quantity(), need.unit(),
              new ArrayList<>(List.of(recipe.name()))));
          continue;
        }
        Double quantity = prev.quantity();
        String unit = prev.unit();
        if (quantity != null && need.quantity() != null && Objects.equals(unit, need.unit())) {
          quantity = quantity + need.quantity();
        } else if (quantity == null) {
          quantity = need.quantity();
          unit = need.unit();
        }
        if (!prev.recipes().contains(recipe.name())) {
          prev.recipes().add(recipe.name());
        }
        totals.put(entry.getKey(), new Total(quantity, unit, prev.recipes()));
      }
    }

    List<ShoppingLine> lines = new ArrayList<>();
    for (Map.Entry<String, Total> entry : totals.entrySet()) {
      String name = entry.getKey();
      Total need = entry.getValue();
      MealIngredient ingredient = catalogue.get(name);
      if (ingredient != null && ingredient.assumed()) {
        continue;
      }
      String tier = ingredient == null ? "tracked" : ingredient.tier();
      String aisle = ingredient == null || ingredient.aisle() == null ? "Other"
          : ingredient.aisle();
      MealInventoryItem have = inventory.get(name);
      if ("staple".equals(tier)) {
        String status = have == null ? null : have.status();
        if ("have".equals(status)) {
          continue;
        }
        String why = status == null ? "not recorded, so maybe you have it"
            : "low".equals(status) ? "running low" : "run out";
        lines.add(new ShoppingLine(name, status == null ? "Check the cupboard" : aisle, tier, null,
            null, why, need.recipes()));
        continue;
      }
      double held = have == null || have.quantity() == null ? 0 : have.quantity();
      if (need.quantity() == null) {
        if (held <= 0) {
          lines.add(new ShoppingLine(name, aisle, tier, null, null, "needed", need.recipes()));
        }
        continue;
      }
      double toBuy = need.quantity() - held;
      if (toBuy > EPSILON) {
        String why = held > 0 ? "you have " + formatQuantity(held, need.unit()) : "none in";
        lines.add(new ShoppingLine(name, aisle, tier, toBuy, need.unit(), why, need.recipes()));
      }
    }
    for (String extra : extras) {
      lines.add(new ShoppingLine(extra, "Added by you", "tracked", null, null, "added by hand",
          List.of()));
    }
    lines.sort(Comparator.<ShoppingLine>comparingInt(l -> aisleRank(l.aisle()))
        .thenComparing(ShoppingLine::name));
    return lines;
  }

  /** What cooking a recipe takes out, by default: its tracked needs, capped at what is in. */
  public static List<Deduction> defaultDeductions(final MealRecipe recipe, final int portions,
      final Map<String, MealInventoryItem> inventory, final Map<String, MealIngredient> catalogue) {
    List<Deduction> out = new ArrayList<>();
    for (Map.Entry<String, Need> entry : needs(recipe, portions).entrySet()) {
      MealIngredient ingredient = catalogue.get(entry.getKey());
      if (ingredient != null && ingredient.assumed()) {
        continue;
      }
      String tier = ingredient == null ? "tracked" : ingredient.tier();
      MealInventoryItem have = inventory.get(entry.getKey());
      Double amount = null;
      if ("tracked".equals(tier) && entry.getValue().quantity() != null) {
        double needed = entry.getValue().quantity();
        amount = have == null || have.quantity() == null ? needed
            : Math.min(needed, have.quantity());
      }
      out.add(new Deduction(entry.getKey(), tier, amount, entry.getValue().unit(), false));
    }
    return out;
  }

  /** Applies one change to an inventory row (null when absent); returns null to delete it. */
  public static MealInventoryItem apply(final String ownerId, final MealInventoryItem prev,
      final InventoryChange change, final MealIngredient ingredient, final Instant now) {
    String action = change.action();
    if ("remove".equals(action)) {
      return null;
    }
    String tier = ingredient != null ? ingredient.tier()
        : prev != null ? prev.tier() : "status".equals(action) ? "staple" : "tracked";
    String id = MealInventoryItem.idFor(ownerId, change.name());
    if ("staple".equals(tier)) {
      String status = change.status() != null ? change.status() : "use".equals(action) ? "low"
          : "have";
      return new MealInventoryItem(id, ownerId, change.name(), tier, null, null, status, null, now);
    }
    String unit = change.unit() != null ? change.unit()
        : prev != null && prev.unit() != null ? prev.unit()
        : ingredient != null ? ingredient.unit() : "each";
    double base = prev == null || prev.quantity() == null ? 0 : prev.quantity();
    double quantity = switch (action) {
      case "add" -> base + (change.quantity() == null ? 1 : change.quantity());
      case "use" -> Math.max(0, base - (change.quantity() == null ? base : change.quantity()));
      case "set" -> change.quantity() == null ? 0 : Math.max(0, change.quantity());
      case "status" -> "out".equals(change.status()) ? 0 : base > 0 ? base : 1;
      default -> throw new IllegalArgumentException("Unknown action " + action);
    };
    LocalDate useBy = change.useBy() != null ? change.useBy() : prev == null ? null : prev.useBy();
    return new MealInventoryItem(id, ownerId, change.name(), tier, quantity, unit, null, useBy,
        now);
  }

  /** One line describing a change, for the kitchen's history. */
  public static String describe(final InventoryChange change) {
    String amount = formatQuantity(change.quantity(), change.unit());
    String spaced = amount.isEmpty() ? "" : amount + " ";
    return switch (change.action()) {
      case "add" -> "+ " + spaced + change.name();
      case "use" -> "− " + spaced + change.name();
      case "set" -> change.name() + " → " + (amount.isEmpty() ? "0" : amount);
      case "remove" -> "removed " + change.name();
      default -> change.name() + ": " + (change.status() == null ? "have" : change.status());
    };
  }

  /** 1200 g as "1.2kg", 3 each as "3", 250 ml as "250ml"; empty when unknown. */
  public static String formatQuantity(final Double quantity, final String unit) {
    if (quantity == null || unit == null) {
      return "";
    }
    if ("each".equals(unit)) {
      return trim(Math.ceil(quantity - EPSILON));
    }
    if (quantity >= 1000) {
      return trim(Math.round(quantity / 10.0) / 100.0) + ("g".equals(unit) ? "kg" : "L");
    }
    return Math.round(quantity) + unit;
  }

  private static String trim(final double value) {
    return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
  }

  private static int aisleRank(final String aisle) {
    int index = AISLE_ORDER.indexOf(aisle);
    return index < 0 ? AISLE_ORDER.indexOf("Other") : index;
  }
}
