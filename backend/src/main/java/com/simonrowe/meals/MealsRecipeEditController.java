package com.simonrowe.meals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Recipes from a link, pasted text or a photo, edits, and recipe photos. */
@RestController
@RequestMapping("/api/meals")
public class MealsRecipeEditController {

  private final RecipeReader reader;
  private final RecipeService recipes;
  private final MealImageStore images;
  private final MealPhotoGenerator photos;

  public MealsRecipeEditController(final RecipeReader reader, final RecipeService recipes,
      final MealImageStore images, final MealPhotoGenerator photos) {
    this.reader = reader;
    this.recipes = recipes;
    this.images = images;
    this.photos = photos;
  }

  /** Reads a recipe from a web page into a draft. Saves nothing. */
  @PostMapping("/recipes/read/link")
  public RecipeService.RecipeInput readLink(@RequestBody final Link body) {
    return reader.fromUrl(body.url());
  }

  /**
   * A page to read.
   *
   * @param url public http(s) address
   */
  public record Link(String url) {
  }

  /** Reads a recipe pasted as text into a draft. Saves nothing. */
  @PostMapping("/recipes/read/text")
  public RecipeService.RecipeInput readText(@RequestBody final Pasted body) {
    return reader.fromText(body.text());
  }

  /**
   * A recipe pasted in.
   *
   * @param text the recipe as copied, chatter and all
   */
  public record Pasted(String text) {
  }

  /** Reads a recipe from a photo into a draft. Saves nothing. */
  @PostMapping("/recipes/read/photo")
  public RecipeService.RecipeInput readPhoto(@RequestParam("image") final MultipartFile image) {
    byte[] bytes = bytes(image);
    return reader.fromImage(bytes, MealsKitchenController.imageType(bytes));
  }

  @PostMapping("/recipes")
  @ResponseStatus(HttpStatus.CREATED)
  public MealRecipe create(@RequestBody final RecipeService.RecipeInput input) {
    return recipes.create(input);
  }

  @PutMapping("/recipes/{id}")
  public MealRecipe update(@PathVariable final String id,
      @RequestBody final RecipeService.RecipeInput input) {
    return recipes.update(id, input);
  }

  /**
   * Makes a photo for a recipe that has none and returns its name, for a recipe being saved.
   * Attaches it to nothing.
   */
  @PostMapping("/images/generate")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> generate(@RequestBody final PhotoOf dish) {
    return Map.of("name", photos.generate(dish.name(), dish.ingredients()));
  }

  /**
   * The dish to picture.
   *
   * @param name the recipe's name
   * @param ingredients its ingredient names, most important first
   */
  public record PhotoOf(String name, List<String> ingredients) {
  }

  /** Stores a recipe photo and returns its name, for a recipe being saved. */
  @PostMapping("/images")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> upload(@RequestParam("image") final MultipartFile image) {
    String name = images.store(bytes(image)).orElseThrow(() -> new ResponseStatusException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Photos must be JPEG, PNG or WebP"));
    return Map.of("name", name);
  }

  private static byte[] bytes(final MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No photo uploaded");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the photo");
    }
  }
}
