package com.simonrowe.migration.changeunits;

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
import java.util.List;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Launches Term Time's portfolio page: moves it from Coming soon to Beta and fills in its
 * overview, example questions, highlights, demo and two sub-pages.
 *
 * <p>The copy lives in {@code seed/portfolio/term-time/} — {@code project.json} for the
 * structured fields, and one markdown file per sub-page, named by that page's {@code body} —
 * because a markdown page with code blocks in it does not survive being a Java string. After
 * this runs it is ordinary CMS content, edited at {@code /admin/portfolio}.
 *
 * <p>It applies only while the row is still Coming soon, the state {@code V048} seeded it in. A
 * project already launched by hand in the CMS is left exactly as it was edited.
 *
 * <p>The images, diagrams and video it points at are files in the frontend bundle under
 * {@code /media/portfolio/term-time/}, so they ship and version with this change rather than
 * depending on an upload to production's media library first. Any of them can be replaced from
 * the CMS with a media-library upload later.
 */
@ChangeUnit(id = "seed-term-time-project-page", order = "049", author = "simonrowe")
public class V049SeedTermTimeProjectPage {

  public static final String SLUG = "term-time";
  static final String SEED_DIR = "seed/portfolio/term-time/";

  /** Every field this unit writes, so the rollback can remove exactly those. */
  static final List<String> PAGE_FIELDS = List.of("headline", "summary", "statement",
      "exampleQuestions", "highlights", "demo", "pages", "image", "liveUrl");

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    Document fields = seedFields();
    fields.append("updatedAt", Date.from(Instant.now()));
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION).updateOne(
        new Document("slug", SLUG).append("status", "COMING_SOON"),
        new Document("$set", fields));
  }

  /** Puts the row back to Coming soon, but only if it still carries this unit's headline. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    Document unset = new Document();
    PAGE_FIELDS.forEach(field -> unset.append(field, ""));
    mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION).updateOne(
        new Document("slug", SLUG).append("headline", seedFields().getString("headline")),
        new Document("$set", new Document("status", "COMING_SOON")).append("$unset", unset));
  }

  /** The seed's fields, with each page's {@code body} file name replaced by that file. */
  static Document seedFields() {
    Document fields = Document.parse(read("project.json"));
    List<Document> pages = new ArrayList<>();
    for (Document page : fields.getList("pages", Document.class)) {
      pages.add(new Document(page).append("body", read(page.getString("body"))));
    }
    fields.put("pages", pages);
    return fields;
  }

  private static String read(final String name) {
    try (InputStream in = new ClassPathResource(SEED_DIR + name).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Missing Term Time seed file " + name, e);
    }
  }
}
