package com.simonrowe.migration.changeunits;

import static org.springframework.data.domain.Sort.Direction.ASC;

import com.simonrowe.media.MediaService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Launches Term Time's portfolio page: moves it from Coming soon to Beta, imports its screenshots,
 * diagrams and demo video into the media library, and fills in its overview, example questions,
 * highlights, demo and two sub-pages.
 *
 * <p>Everything it writes is ordinary CMS content afterwards. The copy lives in
 * {@code seed/portfolio/term-time/} ({@code project.json} for the structured fields and one
 * markdown file per sub-page) and is edited at {@code /admin/portfolio}. The media is imported
 * through {@link MediaService#importFile}, so it is listed in the media library, replaceable
 * there, and backed up with every other upload. Nothing is served from the frontend bundle.
 * The seed names a file as {@code {{media:<file>}}}, and each token is replaced by that file's
 * library path once it has been imported.
 *
 * <p>It applies only while the row is still Coming soon, the state {@code V048} seeded it in, and
 * imports nothing otherwise, so a project already launched by hand is left exactly as edited.
 * Each file is imported under the key {@code seed:portfolio/term-time/<file>}, which the unique
 * {@link #MEDIA_LEGACY_ID_INDEX} makes impossible to import twice. The work itself is
 * {@link PortfolioPageSeed}'s, which later project pages share.
 */
@ChangeUnit(id = "seed-term-time-project-page", order = "049", author = "simonrowe")
public class V049SeedTermTimeProjectPage {

  public static final String SLUG = "term-time";
  public static final String MEDIA_COLLECTION = "media_assets";
  public static final String MEDIA_LEGACY_ID_INDEX = "idx_media_legacy_id";
  private static final int BSON_STRING = 2;

  /** Every file the seed may name, with the type the library stores it as. */
  static final Map<String, String> MEDIA = mediaTypes();

  static final PortfolioPageSeed SEED =
      new PortfolioPageSeed(SLUG, "Term Time", MEDIA, new Document());
  static final String SEED_DIR = SEED.seedDir();
  static final String LEGACY_ID_PREFIX = SEED.legacyIdPrefix();

  /** Every field this unit writes, so the rollback can remove exactly those. */
  static final List<String> PAGE_FIELDS = PortfolioPageSeed.PAGE_FIELDS;

  private static Map<String, String> mediaTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("hero.webp", "image/webp");
    types.put("highlight-term-dates.webp", "image/webp");
    types.put("highlight-year-groups.webp", "image/webp");
    types.put("highlight-notes.webp", "image/webp");
    types.put("sources.svg", "image/svg+xml");
    types.put("schedule.svg", "image/svg+xml");
    types.put("newsletter.svg", "image/svg+xml");
    types.put("architecture.svg", "image/svg+xml");
    types.put("demo.mp4", "video/mp4");
    types.put("demo.vtt", "text/vtt");
    types.put("demo-poster.webp", "image/webp");
    return Map.copyOf(types);
  }

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    SEED.apply(mongoTemplate, mediaService);
  }

  /**
   * Puts the row back to Coming soon, but only if it still carries this unit's headline. The
   * imported media stays in the library: it may have been reused elsewhere since.
   */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    SEED.rollback(mongoTemplate);
  }

  /**
   * Makes a library key name exactly one asset, so {@link MediaService#importFile} can find what a
   * previous run imported. Partial on string values: assets without a key never collide.
   * {@code RestoreService} calls this after a restore drops the collection.
   */
  public static void createMediaIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(MEDIA_COLLECTION).createIndex(new Index()
        .named(MEDIA_LEGACY_ID_INDEX)
        .on("legacyId", ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("legacyId").type(BSON_STRING))));
  }

  /**
   * The seed's fields, with each page's {@code body} file name replaced by that file and every
   * media token replaced by {@code pathFor} of its file.
   */
  static Document seedFields(final UnaryOperator<String> pathFor) {
    return SEED.seedFields(pathFor);
  }
}
