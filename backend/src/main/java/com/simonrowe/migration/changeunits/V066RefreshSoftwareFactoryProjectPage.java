package com.simonrowe.migration.changeunits;

import com.simonrowe.media.MediaService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Rewrites the Software Factory page for the factory as it now works: vulnerability fixes merge
 * and deploy themselves, and the page's films are the console tour and the learning loop. The
 * copy is plainer and first person, and the old films (the overview and three walkthroughs, one of
 * which showed a hand-made fix that predates the agent) are replaced.
 *
 * <p>Copy and new media live in {@code seed/portfolio/software-factory-2026-10/}. Files with the
 * same name as V063's resolve to the assets V063 imported, so nothing is stored twice.
 *
 * <p>Idempotent and edit-safe: it writes only while the page still carries V063's headline, so a
 * page edited in the CMS since, or this unit run twice, is left alone. The rollback puts V063's
 * copy back only while the page still carries this unit's headline.
 */
@ChangeUnit(id = "refresh-software-factory-project-page", order = "066", author = "simonrowe")
public class V066RefreshSoftwareFactoryProjectPage {

  private static final Logger log =
      LoggerFactory.getLogger(V066RefreshSoftwareFactoryProjectPage.class);

  static final PortfolioPageSeed SEED = new PortfolioPageSeed(
      V063SeedSoftwareFactoryProjectPage.SLUG, "software-factory-2026-10", "Software Factory",
      mediaTypes(), new Document());

  private static Map<String, String> mediaTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("hero.webp", "image/webp");
    types.put("highlight-boundary.webp", "image/webp");
    types.put("highlight-cve.jpg", "image/jpeg");
    types.put("highlight-learning.jpg", "image/jpeg");
    for (String diagram : new String[] {"constraint", "architecture", "repair"}) {
      types.put(diagram + ".svg", "image/svg+xml");
    }
    for (String film : new String[] {"console", "learning"}) {
      types.put(film + ".mp4", "video/mp4");
      types.put(film + ".vtt", "text/vtt");
      types.put(film + "-poster.jpg", "image/jpeg");
    }
    return types;
  }

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    boolean written = SEED.replaceWhere(mongoTemplate, mediaService,
        new Document("headline", V063SeedSoftwareFactoryProjectPage.SEED.headline()));
    log.info(written
        ? "Refreshed the Software Factory page"
        : "Software Factory page edited since V063; left as it is");
  }

  /** Puts V063's copy back, only while the page still carries this unit's headline. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    V063SeedSoftwareFactoryProjectPage.SEED.replaceWhere(mongoTemplate, mediaService,
        new Document("headline", SEED.headline()));
  }
}
