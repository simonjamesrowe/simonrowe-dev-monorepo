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
 * Launches Software Factory's portfolio page with four approved films and the diagrams shared
 * with their walkthroughs. Copy and media live in {@code seed/portfolio/software-factory/};
 * afterwards the CMS owns them. The CVE film is explicitly a historical backend repair.
 *
 * <p>Idempotent: only a Coming soon row is updated, and media imports use stable library keys.
 * A launched or manually edited page is never overwritten. Rollback restores V048's tagline
 * and silhouette, retaining imported media that other content may have reused.
 */
@ChangeUnit(id = "seed-software-factory-project-page", order = "063", author = "simonrowe")
public class V063SeedSoftwareFactoryProjectPage {

  public static final String SLUG = "software-factory";

  static final String COMING_SOON_TAGLINE =
      "An autonomous loop that reviews, deploys and watches this site.";

  static final PortfolioPageSeed SEED = new PortfolioPageSeed(SLUG, "Software Factory",
      mediaTypes(), new Document("tagline", COMING_SOON_TAGLINE));

  private static Map<String, String> mediaTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    for (String image : new String[] {"hero", "highlight-boundary", "highlight-repair",
        "highlight-gates"}) {
      types.put(image + ".webp", "image/webp");
    }
    for (String diagram : new String[] {"constraint", "architecture", "repair", "cve"}) {
      types.put(diagram + ".svg", "image/svg+xml");
    }
    for (String film : new String[] {"demo", "walkthrough-ships", "walkthrough-log-fix",
        "walkthrough-cve"}) {
      types.put(film + ".mp4", "video/mp4");
      types.put(film + ".vtt", "text/vtt");
      types.put(film + "-poster.jpg", "image/jpeg");
    }
    return types;
  }

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    SEED.apply(mongoTemplate, mediaService);
  }

  /** Restores Coming soon only while the page still carries the seed's headline. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    SEED.rollback(mongoTemplate);
  }
}
