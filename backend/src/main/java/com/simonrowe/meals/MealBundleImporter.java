package com.simonrowe.meals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads a recipe bundle: a zip holding {@code manifest.json} and the photos it names.
 *
 * <p>Bundles are how the books reach the library. They are built outside this repository,
 * because the books are copyrighted and the repository is public, and uploaded by the owner.
 * Everything is insert-if-absent: a recipe, ingredient or week that already exists is left
 * exactly as it is, so re-uploading a bundle adds nothing and can never overwrite an edit.
 *
 * <p>A recipe that fails validation is skipped and named in the report rather than failing
 * the whole bundle; a malformed archive or manifest fails it.
 */
@Service
public class MealBundleImporter {

  static final int MAX_ENTRIES = 1_000;
  static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
  static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
  static final Set<String> CATEGORIES = Set.of("breakfast", "main", "snack", "drink", "dessert");
  static final Set<String> UNITS = Set.of("g", "ml", "each");
  static final Set<String> TIERS = Set.of("tracked", "staple");

  private static final Logger LOG = LoggerFactory.getLogger(MealBundleImporter.class);
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,119}");
  private static final String MANIFEST = "manifest.json";

  private final MongoTemplate mongoTemplate;
  private final MealImageStore imageStore;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public MealBundleImporter(final MongoTemplate mongoTemplate, final MealImageStore imageStore) {
    this.mongoTemplate = mongoTemplate;
    this.imageStore = imageStore;
  }

  /**
   * Imports one bundle.
   *
   * @param zip the bundle's bytes
   * @return what was added and what was skipped
   */
  public ImportReport importBundle(final byte[] zip) {
    Map<String, byte[]> entries = unzip(zip);
    byte[] manifestBytes = entries.get(MANIFEST);
    if (manifestBytes == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The bundle has no manifest.json");
    }
    BundleManifest manifest;
    try {
      manifest = objectMapper.readValue(manifestBytes, BundleManifest.class);
    } catch (JacksonException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "manifest.json is not a valid bundle manifest: " + e.getOriginalMessage());
    }
    if (manifest.version() == null || manifest.version() != 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Unsupported bundle version " + manifest.version());
    }

    List<String> skipped = new ArrayList<>();
    int ingredientsAdded = 0;
    for (BundleIngredient ingredient : orEmpty(manifest.ingredients())) {
      String problem = problem(ingredient);
      if (problem != null) {
        skipped.add("ingredient " + ingredient.name() + ": " + problem);
      } else if (insert(new MealIngredient(ingredient.name(), ingredient.unit(),
          ingredient.tier(), ingredient.aisle(), Boolean.TRUE.equals(ingredient.assumed())))) {
        ingredientsAdded++;
      }
    }

    int recipesAdded = 0;
    int recipesExisting = 0;
    int photos = 0;
    Instant now = Instant.now();
    for (BundleRecipe recipe : orEmpty(manifest.recipes())) {
      String problem = problem(recipe);
      if (problem != null) {
        skipped.add("recipe " + recipe.id() + ": " + problem);
        continue;
      }
      if (mongoTemplate.exists(byId(recipe.id()), MealRecipe.class)) {
        recipesExisting++;
        continue;
      }
      String photo = null;
      if (recipe.photo() != null) {
        byte[] bytes = entries.get(recipe.photo());
        photo = bytes == null ? null : imageStore.store(bytes).orElse(null);
        if (photo == null) {
          skipped.add("photo for recipe " + recipe.id() + ": missing or not an image");
        } else {
          photos++;
        }
      }
      if (insert(toRecipe(recipe, photo, now))) {
        recipesAdded++;
      } else {
        recipesExisting++;
      }
    }

    int templatesAdded = 0;
    for (BundleTemplate template : orEmpty(manifest.templates())) {
      if (template.id() == null || !ID.matcher(template.id()).matches()
          || template.recipeIds() == null || template.recipeIds().isEmpty()) {
        skipped.add("week " + template.id() + ": no id or no recipes");
      } else if (insert(new MealTemplate(template.id(), clip(template.label(), 120),
          clip(template.source(), 120), orZero(template.kcal()), orZero(template.week()),
          List.copyOf(template.recipeIds())))) {
        templatesAdded++;
      }
    }

    LOG.info(
        "Meals bundle: +{} recipes ({} existed), +{} ingredients, +{} weeks, {} photos, {} skipped",
        recipesAdded, recipesExisting, ingredientsAdded, templatesAdded, photos, skipped.size());
    return new ImportReport(recipesAdded, recipesExisting, ingredientsAdded, templatesAdded,
        photos, skipped);
  }

  private boolean insert(final Object document) {
    try {
      mongoTemplate.insert(document);
      return true;
    } catch (DuplicateKeyException alreadyThere) {
      return false;
    }
  }

  private static MealRecipe toRecipe(final BundleRecipe r, final String photo, final Instant now) {
    List<MealRecipe.IngredientLine> lines = r.ingredients().stream()
        .map(l -> new MealRecipe.IngredientLine(clip(l.text(), 300), l.name(),
            l.unit() == null ? null : l.quantity(), l.quantity() == null ? null : l.unit()))
        .toList();
    boolean vegetarian = Boolean.TRUE.equals(r.vegetarian());
    return new MealRecipe(r.id(), clip(r.name(), 200),
        new MealRecipe.RecipeSource("BOOK", r.book(), r.pages() == null ? List.of() : r.pages(),
            null),
        clip(r.section(), 80), r.category(), vegetarian,
        vegetarian && Boolean.TRUE.equals(r.vegan()), r.servings(), r.perServing(), false, lines,
        clipAll(r.method()), clipAll(r.tips()),
        clipAll(r.swaps()), r.freezable(), r.mainProtein(), photo, now);
  }

  static String problem(final BundleRecipe r) {
    if (r.id() == null || !ID.matcher(r.id()).matches()) {
      return "id must be lower-case letters, digits and hyphens";
    }
    if (r.name() == null || r.name().isBlank()) {
      return "no name";
    }
    if (r.category() == null || !CATEGORIES.contains(r.category())) {
      return "category must be one of " + CATEGORIES;
    }
    if (r.servings() == null || r.servings() < 1 || r.servings() > 50) {
      return "servings must be 1 to 50";
    }
    MealRecipe.Macros m = r.perServing();
    if (m == null || m.kcal() < 0 || m.protein() < 0 || m.carbs() < 0 || m.fat() < 0) {
      return "per-serving macros missing or negative";
    }
    if (r.ingredients() == null || r.ingredients().isEmpty() || r.ingredients().size() > 80) {
      return "needs 1 to 80 ingredient lines";
    }
    for (BundleLine line : r.ingredients()) {
      if (line.name() == null || line.name().isBlank() || line.text() == null) {
        return "an ingredient line has no name or text";
      }
      if (line.unit() != null && !UNITS.contains(line.unit())) {
        return "unit must be one of " + UNITS;
      }
    }
    if (r.method() == null || r.method().isEmpty() || r.method().size() > 60) {
      return "needs 1 to 60 method steps";
    }
    return null;
  }

  static String problem(final BundleIngredient i) {
    if (i.name() == null || i.name().isBlank() || i.name().length() > 80) {
      return "no name";
    }
    if (i.tier() == null || !TIERS.contains(i.tier())) {
      return "tier must be one of " + TIERS;
    }
    if (i.unit() != null && !UNITS.contains(i.unit())) {
      return "unit must be one of " + UNITS;
    }
    return null;
  }

  /**
   * Reads every entry into memory, bounded by entry count and size. Entry names are only ever
   * used as map keys and are never resolved against the file system, so a {@code ../} name
   * cannot write anywhere.
   */
  static Map<String, byte[]> unzip(final byte[] zip) {
    Map<String, byte[]> entries = new HashMap<>();
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        if (entries.size() >= MAX_ENTRIES) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many files in bundle");
        }
        byte[] bytes = readBounded(in);
        total += bytes.length;
        if (total > MAX_TOTAL_BYTES) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The bundle is too large");
        }
        entries.put(entry.getName(), bytes);
      }
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a readable zip file");
    }
    if (entries.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a readable zip file");
    }
    return entries;
  }

  private static byte[] readBounded(final InputStream in) throws IOException {
    byte[] bytes = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
    if (bytes.length > MAX_ENTRY_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bundle file is too large");
    }
    return bytes;
  }

  private static Query byId(final String id) {
    return Query.query(Criteria.where("_id").is(id));
  }

  private static <T> List<T> orEmpty(final List<T> list) {
    return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
  }

  private static int orZero(final Integer value) {
    return value == null ? 0 : value;
  }

  private static String clip(final String value, final int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static List<String> clipAll(final List<String> values) {
    return orEmpty(values).stream().map(v -> clip(v, 2_000)).limit(60).toList();
  }

  /**
   * What an import did.
   *
   * @param recipesAdded new recipes
   * @param recipesExisting recipes already in the library, left alone
   * @param ingredientsAdded new standard ingredients
   * @param templatesAdded new weeks
   * @param photosStored photos stored for new recipes
   * @param skipped what was refused, and why
   */
  public record ImportReport(int recipesAdded, int recipesExisting, int ingredientsAdded,
      int templatesAdded, int photosStored, List<String> skipped) {
  }

  record BundleManifest(Integer version, List<BundleRecipe> recipes,
      List<BundleIngredient> ingredients, List<BundleTemplate> templates) {
  }

  record BundleRecipe(String id, String name, String book, List<Integer> pages, String photo,
      String section, String category, Boolean vegetarian, Boolean vegan, Integer servings,
      MealRecipe.Macros perServing, List<BundleLine> ingredients, List<String> method,
      List<String> tips, List<String> swaps, Boolean freezable, String mainProtein) {
  }

  record BundleLine(String text, String name, Double quantity, String unit) {
  }

  record BundleIngredient(String name, String unit, String tier, String aisle, Boolean assumed) {
  }

  record BundleTemplate(String id, String label, String source, Integer kcal, Integer week,
      List<String> recipeIds) {
  }
}
