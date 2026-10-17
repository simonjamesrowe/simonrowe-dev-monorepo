package com.simonrowe.meals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** The owner's kitchen, suggestions and "Cooked it". Every route is the owner's own data. */
@RestController
@RequestMapping("/api/meals")
public class MealsKitchenController {

  private final KitchenService kitchen;
  private final QuickAddReader reader;

  public MealsKitchenController(final KitchenService kitchen, final QuickAddReader reader) {
    this.kitchen = kitchen;
    this.reader = reader;
  }

  @GetMapping("/kitchen")
  public List<MealInventoryItem> inventory(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.inventory(jwt.getSubject());
  }

  @GetMapping("/kitchen/history")
  public List<MealInventoryEvent> history(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.history(jwt.getSubject(), 30);
  }

  /** Applies reviewed changes: the result of quick add, or an edit on the Kitchen page. */
  @PostMapping("/kitchen/changes")
  public List<MealInventoryItem> apply(@AuthenticationPrincipal final Jwt jwt,
      @RequestBody final ChangesRequest request) {
    String kind = request.kind() == null ? "manual" : request.kind();
    if (!List.of("manual", "quick-add", "order").contains(kind)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind " + kind);
    }
    if (request.changes() != null && request.changes().size() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 200 changes at once");
    }
    return kitchen.applyChanges(jwt.getSubject(), request.changes(), kind);
  }

  /**
   * Changes to apply.
   *
   * @param changes the changes
   * @param kind manual, quick-add or order
   */
  public record ChangesRequest(List<InventoryChange> changes, String kind) {
  }

  @DeleteMapping("/kitchen/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal final Jwt jwt, @PathVariable final String name) {
    kitchen.applyChanges(jwt.getSubject(),
        List.of(new InventoryChange(name, "remove", null, null, null, null, null)), "manual");
  }

  /** Reads plain words, a pasted order, or a photo into proposed changes. Saves nothing. */
  @PostMapping("/kitchen/read")
  public QuickAddReader.Proposal read(@RequestParam(required = false) final String text,
      @RequestParam(defaultValue = "note") final String mode,
      @RequestParam(required = false) final MultipartFile image) {
    if (!"note".equals(mode) && !"order".equals(mode)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be note or order");
    }
    byte[] bytes = null;
    String type = null;
    if (image != null && !image.isEmpty()) {
      try {
        bytes = image.getBytes();
      } catch (IOException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the photo");
      }
      type = imageType(bytes);
    }
    return reader.read(text, mode, bytes, type);
  }

  @GetMapping("/suggestions")
  public List<KitchenMath.Suggestion> suggestions(@AuthenticationPrincipal final Jwt jwt,
      @RequestParam(required = false) final String slot,
      @RequestParam(defaultValue = "40") final int limit) {
    return kitchen.suggestions(jwt.getSubject(), slot, limit);
  }

  /** What the kitchen covers for every recipe, keyed by recipe id. */
  @GetMapping("/coverage")
  public Map<String, KitchenMath.Coverage> coverage(@AuthenticationPrincipal final Jwt jwt) {
    return kitchen.coverageOfAll(jwt.getSubject());
  }

  @GetMapping("/recipes/{id}/coverage")
  public KitchenMath.Coverage recipeCoverage(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String id, @RequestParam final int portions) {
    return kitchen.coverage(jwt.getSubject(), id, portions);
  }

  @GetMapping("/recipes/{id}/deductions")
  public List<KitchenMath.Deduction> deductions(@AuthenticationPrincipal final Jwt jwt,
      @PathVariable final String id, @RequestParam final int portions) {
    return kitchen.deductions(jwt.getSubject(), id, portions);
  }

  /** Records a cook: rating, what it took from the kitchen, and off Up next. */
  @PostMapping("/cooked")
  @ResponseStatus(HttpStatus.CREATED)
  public MealRating cooked(@AuthenticationPrincipal final Jwt jwt,
      @RequestBody final KitchenService.CookedRequest request) {
    if (request.notes() != null && request.notes().length() > 1_000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Notes are at most 1,000 characters");
    }
    if (request.tags() != null && (request.tags().size() > 8
        || request.tags().stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 30))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Up to 8 tags of 30 characters");
    }
    return kitchen.cooked(jwt.getSubject(), request);
  }

  static String imageType(final byte[] bytes) {
    String extension = MealImageStore.sniff(bytes);
    if (extension == null) {
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "Photos must be JPEG, PNG or WebP");
    }
    return MealImageStore.contentType("x." + extension);
  }
}
