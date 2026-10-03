package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
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
class V050SeedCliniciansVeilProjectPageTest extends AbstractIntegrationTest {

  /** A library path in the stored copy. Possessive, over our own few kilobytes of text. */
  private static final Pattern UPLOAD_PATH = Pattern.compile("/uploads/[A-Za-z0-9/._-]++");

  private static final PortfolioPageSeed SEED = V050SeedCliniciansVeilProjectPage.SEED;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaService mediaService;

  @Value("${uploads.path}")
  private String uploadsPath;

  private final V050SeedCliniciansVeilProjectPage changeUnit =
      new V050SeedCliniciansVeilProjectPage();

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

  private Document cliniciansVeil() {
    return projects().find(new Document("slug", "clinicians-veil")).first();
  }

  private void launch() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    changeUnit.execution(mongoTemplate, mediaService);
  }

  @Test
  void launchesTheComingSoonRowWithItsPageAndSubPages() throws Exception {
    launch();

    Document row = cliniciansVeil();
    assertThat(row.getString("status")).isEqualTo("IN_DEVELOPMENT");
    assertThat(row.getString("name")).isEqualTo("Clinician's Veil");
    assertThat(row.getString("tagline")).isNotEqualTo(
        V050SeedCliniciansVeilProjectPage.COMING_SOON_TAGLINE);
    assertThat(row.getList("pages", Document.class))
        .extracting(page -> page.getString("slug"))
        .containsExactly("how-it-works", "architecture");

    mockMvc.perform(get("/api/portfolio/clinicians-veil"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headline").value(startsWith("Premium AI for clinical letters.")))
        .andExpect(jsonPath("$.statement.points.length()").value(4))
        .andExpect(jsonPath("$.highlights.length()").value(3))
        .andExpect(jsonPath("$.image.url").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.demo.videoUrl").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.demo.chapters.length()").value(8))
        .andExpect(jsonPath("$.pages[1].body").value(containsString("```rust")));
  }

  /** Every file lands in the library, and every path the stored copy names is one on disk. */
  @Test
  void importsEveryFileIntoTheLibraryAndPointsTheCopyAtIt() {
    launch();

    assertThat(media().countDocuments()).isEqualTo(SEED.media().size());
    String stored = cliniciansVeil().toJson();
    assertThat(stored).doesNotContain("{{media:");
    Matcher matcher = UPLOAD_PATH.matcher(stored);
    List<String> paths = matcher.results().map(result -> result.group()).distinct().toList();
    assertThat(paths).hasSize(SEED.media().size());
    assertThat(paths).allSatisfy(path -> assertThat(
        Files.isRegularFile(Path.of(uploadsPath, path.substring("/uploads/".length()))))
        .as(path).isTrue());
    Document captions = media().find(
        new Document("legacyId", SEED.legacyIdPrefix() + "demo.vtt")).first();
    assertThat(captions.getString("mimeType")).isEqualTo("text/vtt");
  }

  /** The seed and its media directory list the same files, so neither can name a missing one. */
  @Test
  void theSeedNamesExactlyTheFilesItShips() {
    for (String file : SEED.media().keySet()) {
      assertThat(new ClassPathResource(SEED.seedDir() + "media/" + file).exists())
          .as(file).isTrue();
    }
    assertThat(SEED.seedFields(file -> "/uploads/x/" + file).toJson())
        .doesNotContain("{{media:");
  }

  @Test
  void secondRunImportsNothingTwice() {
    launch();
    projects().updateOne(new Document("slug", "clinicians-veil"),
        new Document("$set", new Document("status", "COMING_SOON")));

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(media().countDocuments()).isEqualTo(SEED.media().size());
  }

  @Test
  void leavesAnAlreadyLaunchedProjectAloneAndImportsNothing() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    projects().updateOne(new Document("slug", "clinicians-veil"),
        new Document("$set", new Document("status", "BETA").append("tagline", "Edited")));

    changeUnit.execution(mongoTemplate, mediaService);

    Document row = cliniciansVeil();
    assertThat(row.getString("status")).isEqualTo("BETA");
    assertThat(row.getString("tagline")).isEqualTo("Edited");
    assertThat(row.containsKey("pages")).isFalse();
    assertThat(media().countDocuments()).isZero();
  }

  /** Term Time's own seed and this one share the library without colliding. */
  @Test
  void runsAfterTermTimeAndTouchesNoOtherProject() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    new V049SeedTermTimeProjectPage().execution(mongoTemplate, mediaService);
    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(projects().countDocuments(new Document("status", "COMING_SOON"))).isEqualTo(2);
    assertThat(projects().countDocuments(new Document("pages", new Document("$exists", true))))
        .isEqualTo(2);
    assertThat(media().countDocuments())
        .isEqualTo(V049SeedTermTimeProjectPage.MEDIA.size() + SEED.media().size());
  }

  @Test
  void rollbackReturnsTheRowToComingSoonWithItsOldTagline() {
    launch();

    changeUnit.rollback(mongoTemplate);

    Document row = cliniciansVeil();
    assertThat(row.getString("status")).isEqualTo("COMING_SOON");
    assertThat(row.getString("tagline"))
        .isEqualTo(V050SeedCliniciansVeilProjectPage.COMING_SOON_TAGLINE);
    for (String field : PortfolioPageSeed.PAGE_FIELDS) {
      assertThat(row.containsKey(field)).as(field).isFalse();
    }
  }
}
