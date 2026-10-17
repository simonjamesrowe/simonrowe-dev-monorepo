package com.simonrowe.meals;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns plain words, a pasted online order or a photo (fridge shelf, receipt) into proposed
 * kitchen changes. Nothing is applied here: the owner ticks what is right and the page sends the
 * ticked ones to {@link KitchenService#applyChanges}.
 */
@Component
public class QuickAddReader {

  static final int MAX_TEXT = 20_000;
  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final String PROMPT = """
      You update a home kitchen inventory. Today is %s.
      Turn the user's %s into inventory changes.

      Actions:
      - add: they bought or now have MORE of something (quantity added, in the ingredient's unit).
      - use: they used some (quantity used). "Used the last of X" is status out instead.
      - set: they state how much is left ("6 eggs left").
      - status: for cupboard staples, have / low / out ("running low on oats").

      Map every item to a canonical name from the list when one fits, using its unit. Convert
      packs when the size is stated or common: a pack of chicken breast is about 600 g, a dozen
      eggs is 12 each. Staples (tier staple) use status, not quantity. An item matching nothing
      gets a short generic lower-case name. Ignore anything that is not food or drink. Never
      invent items. useBy is YYYY-MM-DD when stated or obvious ("use by Friday"), else null.
      source is the words the change came from.

      Canonical ingredients (name | unit | tier):
      %s
      """;
  private static final String SCHEMA = """
      {"type":"object","additionalProperties":false,"required":["changes"],
       "properties":{"changes":{"type":"array","items":{"type":"object",
        "additionalProperties":false,
        "required":["name","action","quantity","unit","status","useBy","source"],
        "properties":{
         "name":{"type":"string"},
         "action":{"type":"string","enum":["add","use","set","status"]},
         "quantity":{"type":["number","null"]},
         "unit":{"type":["string","null"],"enum":["g","ml","each",null]},
         "status":{"type":["string","null"],"enum":["have","low","out",null]},
         "useBy":{"type":["string","null"]},
         "source":{"type":"string"}}}}}}
      """;

  private static final String ORDER = """
      pasted online grocery order (Ocado, Amazon or Deliveroo). Respect substitutions; items \
      marked missing or unavailable are NOT added""";
  private static final String PHOTO = "photo (a fridge shelf, a cupboard or a receipt) and note";

  private final MealsModel model;
  private final KitchenService kitchen;
  private final Clock clock;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public QuickAddReader(final MealsModel model, final KitchenService kitchen, final Clock clock) {
    this.model = model;
    this.kitchen = kitchen;
    this.clock = clock;
  }

  /** What the page shows for review: the proposals, and which names are new. */
  public record Proposal(List<InventoryChange> changes, List<String> newIngredients) {
  }

  /**
   * Reads one note, order or photo.
   *
   * @param text the owner's words or pasted order, may be blank when there is an image
   * @param mode {@code note} or {@code order}
   * @param image a photo, or null
   * @param imageType its sniffed MIME type
   * @return proposed changes, already checked
   */
  public Proposal read(final String text, final String mode, final byte[] image,
      final String imageType) {
    if ((text == null || text.isBlank()) && image == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what changed, or add a photo");
    }
    if (text != null && text.length() > MAX_TEXT) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is too long to read");
    }
    String what = "order".equals(mode) ? ORDER : image != null ? PHOTO : "note";
    LocalDate today = LocalDate.now(clock.withZone(LONDON));
    String day = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.UK) + " " + today;
    var catalogue = kitchen.catalogue();
    String names = catalogue.values().stream()
        .filter(i -> !i.assumed())
        .sorted(Comparator.comparing(MealIngredient::name))
        .map(i -> i.name() + " | " + (i.unit() == null ? "-" : i.unit()) + " | " + i.tier())
        .collect(Collectors.joining("\n"));
    String json = model.structured(PROMPT.formatted(day, what, names), text, image, imageType,
        SCHEMA);
    List<InventoryChange> changes = parse(json);
    List<String> fresh = changes.stream().map(InventoryChange::name)
        .filter(n -> !catalogue.containsKey(n)).distinct().toList();
    return new Proposal(changes, fresh);
  }

  List<InventoryChange> parse(final String json) {
    JsonNode root;
    try {
      root = objectMapper.readTree(json);
    } catch (JacksonException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "The model's answer was unreadable");
    }
    List<InventoryChange> out = new ArrayList<>();
    for (JsonNode node : root.path("changes")) {
      String name = node.path("name").asString("").strip().toLowerCase();
      String action = node.path("action").asString("");
      if (name.isEmpty() || name.length() > 80 || !KitchenService.ACTIONS.contains(action)) {
        continue;
      }
      String unit = text(node, "unit");
      String status = text(node, "status");
      Double quantity = node.path("quantity").isNumber() ? node.path("quantity").asDouble() : null;
      out.add(new InventoryChange(name, action,
          quantity == null || quantity < 0 ? null : quantity,
          // Set.of(...).contains(null) throws, so a missing unit or status is checked first.
          unit != null && MealBundleImporter.UNITS.contains(unit) ? unit : null,
          status != null && KitchenService.STATUSES.contains(status) ? status : null,
          date(text(node, "useBy")), text(node, "source")));
    }
    return out;
  }

  private static String text(final JsonNode node, final String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.asString() : null;
  }

  private static LocalDate date(final String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
