package com.simonrowe.migration.changeunits;

import com.simonrowe.media.MediaAsset;
import com.simonrowe.media.MediaService;
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

/**
 * Launches one Coming soon portfolio project from the seed in {@code seed/portfolio/<slug>/}:
 * imports its media into the library and writes its page fields, the way {@code V049} launched
 * Term Time. A change unit names the slug and the files it ships; this does the rest.
 *
 * <p>{@code project.json} holds the structured fields and names each sub-page's markdown file as
 * its {@code body}. Any file in {@code media/} is named in the copy as {@code {{media:<file>}}},
 * and each token is replaced by that file's library path once it has been imported, under the key
 * {@code seed:portfolio/<slug>/<file>} so a second run finds it rather than importing it again.
 *
 * <p>It applies only while the row is still Coming soon, the state {@code V048} seeded it in, and
 * imports nothing otherwise, so a project already launched by hand is left exactly as edited.
 */
final class PortfolioPageSeed {

  /** Every field a page seed may write, so the rollback can remove exactly those. */
  static final List<String> PAGE_FIELDS = List.of("headline", "summary", "statement",
      "exampleQuestions", "highlights", "demo", "pages", "image", "liveUrl");

  /** A media token. Possessive over a class that excludes the braces, so nothing backtracks. */
  private static final Pattern MEDIA_TOKEN = Pattern.compile("\\{\\{media:([a-z0-9.-]++)}}");

  private final String slug;
  private final String name;
  private final Map<String, String> media;
  private final Document restoredOnRollback;

  /**
   * @param slug the project's slug, and the name of its seed directory
   * @param name the project's name, for error messages
   * @param media every file the seed may name, with the type the library stores it as
   * @param restoredOnRollback fields the seed overwrites that {@code V048} gave a value, put back
   *     when the unit is rolled back
   */
  PortfolioPageSeed(final String slug, final String name, final Map<String, String> media,
      final Document restoredOnRollback) {
    this.slug = slug;
    this.name = name;
    this.media = Map.copyOf(media);
    this.restoredOnRollback = new Document(restoredOnRollback);
  }

  String seedDir() {
    return "seed/portfolio/" + slug + "/";
  }

  String legacyIdPrefix() {
    return "seed:portfolio/" + slug + "/";
  }

  Map<String, String> media() {
    return media;
  }

  /** Imports the media and writes the page, if the project is still Coming soon. */
  void apply(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    V049SeedTermTimeProjectPage.createMediaIndexes(mongoTemplate);
    Document stillComingSoon = new Document("slug", slug).append("status", "COMING_SOON");
    if (mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION)
        .countDocuments(stillComingSoon) == 0) {
      return;
    }
    Map<String, String> paths = new LinkedHashMap<>();
    media.forEach((file, type) -> {
      MediaAsset asset = mediaService.importFile(
          readBytes("media/" + file), file, type, legacyIdPrefix() + file);
      paths.put(file, asset.originalPath());
    });
    Document fields = seedFields(paths::get);
    fields.append("updatedAt", Date.from(Instant.now()));
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION)
        .updateOne(stillComingSoon, new Document("$set", fields));
  }

  /**
   * Puts the row back to Coming soon, but only if it still carries the seed's headline. The
   * imported media stays in the library: it may have been reused elsewhere since.
   */
  void rollback(final MongoTemplate mongoTemplate) {
    Document unset = new Document();
    PAGE_FIELDS.forEach(field -> unset.append(field, ""));
    Document set = new Document("status", "COMING_SOON");
    set.putAll(restoredOnRollback);
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION).updateOne(
        new Document("slug", slug)
            .append("headline", seedFields(file -> file).getString("headline")),
        new Document("$set", set).append("$unset", unset));
  }

  /**
   * The seed's fields, with each page's {@code body} file name replaced by that file and every
   * media token replaced by {@code pathFor} of its file.
   */
  Document seedFields(final UnaryOperator<String> pathFor) {
    Document fields = Document.parse(resolveMedia(read("project.json"), pathFor));
    List<Document> pages = new ArrayList<>();
    for (Document page : fields.getList("pages", Document.class)) {
      String body = resolveMedia(read(page.getString("body")), pathFor);
      pages.add(new Document(page).append("body", body));
    }
    fields.put("pages", pages);
    return fields;
  }

  private String resolveMedia(final String text, final UnaryOperator<String> pathFor) {
    Matcher matcher = MEDIA_TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String file = matcher.group(1);
      String path = media.containsKey(file) ? pathFor.apply(file) : null;
      if (path == null) {
        throw new IllegalStateException("The " + name + " seed names unknown media " + file);
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(path));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String read(final String file) {
    return new String(readBytes(file), StandardCharsets.UTF_8);
  }

  private byte[] readBytes(final String file) {
    try (InputStream in = new ClassPathResource(seedDir() + file).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Missing " + name + " seed file " + file, e);
    }
  }
}
