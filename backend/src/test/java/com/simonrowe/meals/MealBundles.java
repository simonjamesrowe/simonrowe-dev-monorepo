package com.simonrowe.meals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic recipe bundles for tests. The real bundles hold copyrighted book content and never
 * enter this repository, so every recipe here is invented.
 */
final class MealBundles {

  /** The smallest valid PNG header plus padding: enough for the type sniffer. */
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 1};

  static final String MANIFEST = """
      {
        "version": 1,
        "recipes": [
          {
            "id": "test-chilli", "name": "Test Chilli", "book": "testbook", "pages": [4, 5],
            "photo": "photos/test-chilli.png", "section": "Mains", "category": "main",
            "vegetarian": false, "vegan": false, "servings": 4,
            "perServing": {"kcal": 450, "protein": 40, "carbs": 35, "fat": 15},
            "ingredients": [
              {"text": "500g lean mince", "name": "beef mince", "quantity": 500, "unit": "g"},
              {"text": "1 onion", "name": "onion", "quantity": 1, "unit": "each"},
              {"text": "Salt to taste", "name": "salt", "quantity": null, "unit": null}
            ],
            "method": ["Brown the mince.", "Add the onion and simmer."],
            "tips": ["Freeze the spares."], "swaps": [], "freezable": true,
            "mainProtein": "beef"
          },
          {
            "id": "test-oats", "name": "Test Oats", "book": "testbook", "pages": [8],
            "photo": null, "section": "Breakfast", "category": "breakfast",
            "vegetarian": true, "vegan": false, "servings": 1,
            "perServing": {"kcal": 400, "protein": 30, "carbs": 50, "fat": 8},
            "ingredients": [{"text": "40g oats", "name": "oats", "quantity": 40, "unit": "g"}],
            "method": ["Mix and chill overnight."], "tips": [], "swaps": [], "freezable": false,
            "mainProtein": null
          },
          {
            "id": "Bad Id!", "name": "Broken", "category": "main", "servings": 1,
            "perServing": {"kcal": 1, "protein": 1, "carbs": 1, "fat": 1},
            "ingredients": [{"text": "x", "name": "x"}], "method": ["x"]
          }
        ],
        "ingredients": [
          {"name": "beef mince", "unit": "g", "tier": "tracked", "aisle": "Meat & fish",
           "assumed": false},
          {"name": "oats", "unit": "g", "tier": "staple", "aisle": "Cupboard", "assumed": false},
          {"name": "salt", "unit": null, "tier": "staple", "aisle": "Cupboard", "assumed": true}
        ],
        "templates": [
          {"id": "tp-1500-w1", "label": "1500 kcal - week 1", "source": "Test Plans",
           "kcal": 1500, "week": 1, "recipeIds": ["test-chilli", "test-oats"]}
        ]
      }
      """;

  private MealBundles() {
  }

  static byte[] valid() {
    return zip(Map.of("manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8),
        "photos/test-chilli.png", PNG));
  }

  static byte[] zip(final Map<String, byte[]> entries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
