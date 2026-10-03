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
 * Launches Clinician's Veil's portfolio page: moves it from Coming soon to In development, imports
 * its screenshots, diagrams and demo video into the media library, and fills in its overview,
 * highlights, demo and two sub-pages, exactly as {@code V049} did for Term Time.
 *
 * <p>The copy lives in {@code seed/portfolio/clinicians-veil/} and is ordinary CMS content once
 * written, edited at {@code /admin/portfolio}. Every screenshot is of the synthetic case the demo
 * uses; no real patient data is in the seed. It applies only while the row is still Coming soon,
 * so a page already launched or edited by hand is never overwritten.
 */
@ChangeUnit(id = "seed-clinicians-veil-project-page", order = "050", author = "simonrowe")
public class V050SeedCliniciansVeilProjectPage {

  public static final String SLUG = "clinicians-veil";

  /** The tagline {@code V048} seeded, which the page's own replaces until a rollback. */
  static final String COMING_SOON_TAGLINE = "Details soon.";

  static final PortfolioPageSeed SEED = new PortfolioPageSeed(SLUG, "Clinician's Veil",
      mediaTypes(), new Document("tagline", COMING_SOON_TAGLINE));

  private static Map<String, String> mediaTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("hero.webp", "image/webp");
    types.put("highlight-side-by-side.webp", "image/webp");
    types.put("highlight-request.webp", "image/webp");
    types.put("highlight-restored.webp", "image/webp");
    types.put("stays-and-leaves.svg", "image/svg+xml");
    types.put("architecture.svg", "image/svg+xml");
    types.put("demo.mp4", "video/mp4");
    types.put("demo.vtt", "text/vtt");
    types.put("demo-poster.webp", "image/webp");
    return types;
  }

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    SEED.apply(mongoTemplate, mediaService);
  }

  /** Puts the row back to Coming soon with its old tagline, if it still has the seed's headline. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    SEED.rollback(mongoTemplate);
  }
}
