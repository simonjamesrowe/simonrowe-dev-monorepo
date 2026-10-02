package com.simonrowe.migration.changeunits;

import static org.springframework.data.domain.Sort.Direction.ASC;

import com.simonrowe.media.MediaAsset;
import com.simonrowe.media.MediaService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;
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
 * {@link #MEDIA_LEGACY_ID_INDEX} makes impossible to import twice.
 */
@ChangeUnit(id = "seed-term-time-project-page", order = "049", author = "simonrowe")
public class V049SeedTermTimeProjectPage {

  public static final String SLUG = "term-time";
  public static final String MEDIA_COLLECTION = "media_assets";
  public static final String MEDIA_LEGACY_ID_INDEX = "idx_media_legacy_id";
  static final String SEED_DIR = "seed/portfolio/term-time/";
  static final String LEGACY_ID_PREFIX = "seed:portfolio/term-time/";
  private static final int BSON_STRING = 2;

  /** Every file the seed may name, with the type the library stores it as. */
  static final Map<String, String> MEDIA = mediaTypes();

  /** Every field this unit writes, so the rollback can remove exactly those. */
  static final List<String> PAGE_FIELDS = List.of("headline", "summary", "statement",
      "exampleQuestions", "highlights", "demo", "pages", "image", "liveUrl");

  /** A media token. Possessive over a class that excludes the braces, so nothing backtracks. */
  private static final Pattern MEDIA_TOKEN = Pattern.compile("\\{\\{media:([a-z0-9.-]++)}}");

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
    createMediaIndexes(mongoTemplate);
    Document stillComingSoon = new Document("slug", SLUG).append("status", "COMING_SOON");
    if (mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION)
        .countDocuments(stillComingSoon) == 0) {
      return;
    }
    Map<String, String> paths = new LinkedHashMap<>();
    MEDIA.forEach((file, type) -> {
      MediaAsset asset = mediaService.importFile(
          readBytes("media/" + file), file, type, LEGACY_ID_PREFIX + file);
      paths.put(file, asset.originalPath());
    });
    Document fields = seedFields(file -> paths.get(file));
    fields.append("updatedAt", Date.from(Instant.now()));
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION)
        .updateOne(stillComingSoon, new Document("$set", fields));
  }

  /**
   * Puts the row back to Coming soon, but only if it still carries this unit's headline. The
   * imported media stays in the library: it may have been reused elsewhere since.
   */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    Document unset = new Document();
    PAGE_FIELDS.forEach(field -> unset.append(field, ""));
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION).updateOne(
        new Document("slug", SLUG)
            .append("headline", seedFields(file -> file).getString("headline")),
        new Document("$set", new Document("status", "COMING_SOON")).append("$unset", unset));
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
    Document fields = Document.parse(resolveMedia(read("project.json"), pathFor));
    List<Document> pages = new ArrayList<>();
    for (Document page : fields.getList("pages", Document.class)) {
      String body = resolveMedia(read(page.getString("body")), pathFor);
      pages.add(new Document(page).append("body", body));
    }
    fields.put("pages", pages);
    return fields;
  }

  private static String resolveMedia(final String text, final UnaryOperator<String> pathFor) {
    Matcher matcher = MEDIA_TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String file = matcher.group(1);
      String path = MEDIA.containsKey(file) ? pathFor.apply(file) : null;
      if (path == null) {
        throw new IllegalStateException("The Term Time seed names unknown media " + file);
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(path));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String read(final String name) {
    return new String(readBytes(name), StandardCharsets.UTF_8);
  }

  private static byte[] readBytes(final String name) {
    try (InputStream in = new ClassPathResource(SEED_DIR + name).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Missing Term Time seed file " + name, e);
    }
  }
}
