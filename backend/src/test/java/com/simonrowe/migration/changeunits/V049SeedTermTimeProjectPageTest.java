package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.media.MediaService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V049SeedTermTimeProjectPageTest extends AbstractIntegrationTest {

  /** A library path in the stored copy. Possessive, over our own few kilobytes of text. */
  private static final Pattern UPLOAD_PATH = Pattern.compile("/uploads/[A-Za-z0-9/._-]++");

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaService mediaService;

  @Value("${uploads.path}")
  private String uploadsPath;

  private final V049SeedTermTimeProjectPage changeUnit = new V049SeedTermTimeProjectPage();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);
    mongoTemplate.dropCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  private com.mongodb.client.MongoCollection<Document> projects() {
    return mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private com.mongodb.client.MongoCollection<Document> media() {
    return mongoTemplate.getCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  private Document termTime() {
    return projects().find(new Document("slug", "term-time")).first();
  }

  private void launch() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    changeUnit.execution(mongoTemplate, mediaService);
  }

  @Test
  void launchesTheComingSoonRowWithItsPageAndSubPages() throws Exception {
    launch();

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("BETA");
    assertThat(row.getString("name")).isEqualTo("Term Time");
    assertThat(row.getList("exampleQuestions", String.class)).contains("When is half term?");
    assertThat(row.getList("pages", Document.class))
        .extracting(page -> page.getString("slug"))
        .containsExactly("how-it-works", "architecture");
    assertThat(row.getList("pages", Document.class).get(0).getString("body"))
        .startsWith("## Four sources, one place to ask");

    mockMvc.perform(get("/api/portfolio/term-time"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headline").value("School life,\none question away."))
        .andExpect(jsonPath("$.statement.points.length()").value(4))
        .andExpect(jsonPath("$.highlights.length()").value(3))
        .andExpect(jsonPath("$.image.url").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.demo.videoUrl").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.pages[1].body").value(containsString("```java")));
  }

  /**
   * Every file lands in the media library, and every path the stored copy names is one of those
   * files on disk. The seed's {@code {{media:...}}} tokens must all be gone, or the page would
   * show a token where an image should be.
   */
  @Test
  void importsEveryFileIntoTheLibraryAndPointsTheCopyAtIt() throws Exception {
    launch();

    assertThat(media().countDocuments()).isEqualTo(V049SeedTermTimeProjectPage.MEDIA.size());
    String stored = termTime().toJson();
    assertThat(stored).doesNotContain("{{media:");
    Matcher matcher = UPLOAD_PATH.matcher(stored);
    List<String> paths = matcher.results().map(result -> result.group()).distinct().toList();
    assertThat(paths).hasSize(V049SeedTermTimeProjectPage.MEDIA.size());
    assertThat(paths).allSatisfy(path -> assertThat(
        Files.isRegularFile(Path.of(uploadsPath, path.substring("/uploads/".length()))))
        .as(path).isTrue());
    Document video = media().find(new Document("legacyId",
        V049SeedTermTimeProjectPage.LEGACY_ID_PREFIX + "demo.mp4")).first();
    assertThat(video.getString("mimeType")).isEqualTo("video/mp4");
  }

  /** The seed and its media directory list the same files, so neither can name a missing one. */
  @Test
  void theSeedNamesExactlyTheFilesItShips() throws Exception {
    for (String file : V049SeedTermTimeProjectPage.MEDIA.keySet()) {
      assertThat(new ClassPathResource(V049SeedTermTimeProjectPage.SEED_DIR + "media/" + file)
          .exists()).as(file).isTrue();
    }
    assertThat(V049SeedTermTimeProjectPage.seedFields(file -> "/uploads/x/" + file).toJson())
        .doesNotContain("{{media:");
  }

  @Test
  void aSecondRunImportsNothingTwice() {
    launch();
    projects().updateOne(new Document("slug", "term-time"),
        new Document("$set", new Document("status", "COMING_SOON")));

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(media().countDocuments()).isEqualTo(V049SeedTermTimeProjectPage.MEDIA.size());
  }

  @Test
  void theLibraryKeyIsUniqueButAssetsWithoutOneDoNotCollide() {
    V049SeedTermTimeProjectPage.createMediaIndexes(mongoTemplate);

    media().insertOne(new Document("fileName", "a.png"));
    media().insertOne(new Document("fileName", "b.png"));
    media().insertOne(new Document("fileName", "c.png").append("legacyId", "seed:x"));
    assertThat(media().countDocuments()).isEqualTo(3);
    assertThatThrownBy(() -> media().insertOne(new Document("legacyId", "seed:x")))
        .hasMessageContaining("duplicate key");
  }

  @Test
  void leavesAnAlreadyLaunchedProjectAloneAndImportsNothing() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    projects().updateOne(new Document("slug", "term-time"),
        new Document("$set", new Document("status", "LIVE").append("tagline", "Edited")));

    changeUnit.execution(mongoTemplate, mediaService);

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("LIVE");
    assertThat(row.getString("tagline")).isEqualTo("Edited");
    assertThat(row.containsKey("pages")).isFalse();
    assertThat(media().countDocuments()).isZero();
  }

  @Test
  void touchesNoOtherProject() {
    launch();

    assertThat(projects().countDocuments(new Document("status", "COMING_SOON"))).isEqualTo(3);
    assertThat(projects().countDocuments(new Document("pages", new Document("$exists", true))))
        .isEqualTo(1);
  }

  @Test
  void rollbackReturnsTheRowToComingSoon() {
    launch();

    changeUnit.rollback(mongoTemplate);

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("COMING_SOON");
    for (String field : V049SeedTermTimeProjectPage.PAGE_FIELDS) {
      assertThat(row.containsKey(field)).as(field).isFalse();
    }
  }
}
