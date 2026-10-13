package com.simonrowe.migration.changeunits;

import com.simonrowe.media.MediaService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Launches CoParent's portfolio page: moves it from Coming soon to In development under the
 * product's own name, imports its screenshots, diagrams and four films into the media library,
 * and fills in its overview, highlights, demo and three sub-pages, exactly as {@code V050} did for
 * Clinician's Veil.
 *
 * <p>The copy lives in {@code seed/portfolio/co-parents/} and is ordinary CMS content once
 * written, edited at {@code /admin/portfolio}. Every screenshot and film shows a synthetic family
 * on a local stack; no real family's data is in the seed. It applies only while the row is still
 * Coming soon, so a page already launched or edited by hand is never overwritten.
 */
@ChangeUnit(id = "seed-co-parents-project-page", order = "057", author = "simonrowe")
public class V057SeedCoParentsProjectPage {

  public static final String SLUG = "co-parents";

  /** The name {@code V048} seeded, which the seed's own replaces until a rollback. */
  static final String COMING_SOON_NAME = "Co-Parents";

  static final PortfolioPageSeed SEED = new PortfolioPageSeed(SLUG, "CoParent",
      mediaTypes(), new Document("name", COMING_SOON_NAME));

  private static Map<String, String> mediaTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("hero.webp", "image/webp");
    types.put("highlight-quick-add.webp", "image/webp");
    types.put("highlight-expenses.webp", "image/webp");
    types.put("highlight-statements.webp", "image/webp");
    types.put("quick-add.svg", "image/svg+xml");
    types.put("statements.svg", "image/svg+xml");
    types.put("architecture.svg", "image/svg+xml");
    types.put("demo.mp4", "video/mp4");
    types.put("demo.vtt", "text/vtt");
    types.put("demo-poster.webp", "image/webp");
    for (String film : new String[] {"setup", "expenses", "quick-add"}) {
      types.put("walkthrough-" + film + ".mp4", "video/mp4");
      types.put("walkthrough-" + film + ".vtt", "text/vtt");
      types.put("walkthrough-" + film + "-poster.webp", "image/webp");
    }
    return types;
  }

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    SEED.apply(mongoTemplate, mediaService);
  }

  /** Puts the row back to Coming soon with its old name, if it still has the seed's headline. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    SEED.rollback(mongoTemplate);
  }
}
