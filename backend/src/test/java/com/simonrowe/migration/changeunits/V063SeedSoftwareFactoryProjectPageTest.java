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
class V063SeedSoftwareFactoryProjectPageTest extends AbstractIntegrationTest {

  /** A library path in the stored copy. Possessive, over our own few kilobytes of text. */
  private static final Pattern UPLOAD_PATH = Pattern.compile("/uploads/[A-Za-z0-9/._-]++");

  private static final PortfolioPageSeed SEED = V063SeedSoftwareFactoryProjectPage.SEED;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaService mediaService;

  @Value("${uploads.path}")
  private String uploadsPath;

  private final V063SeedSoftwareFactoryProjectPage changeUnit =
      new V063SeedSoftwareFactoryProjectPage();

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

  private Document factory() {
    return projects().find(new Document("slug", "software-factory")).first();
  }

  private void launch() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    changeUnit.execution(mongoTemplate, mediaService);
  }

  @Test
  void launchesTheComingSoonRowWithItsFilmsAndPages() throws Exception {
    launch();

    Document row = factory();
    assertThat(row.getString("status")).isEqualTo("LIVE");
    assertThat(row.getString("name")).isEqualTo("Software Factory");
    assertThat(row.getString("liveUrl"))
        .isEqualTo("https://github.com/simonjamesrowe/simonrowe-dev-monorepo");
    assertThat(row.getList("pages", Document.class))
        .extracting(page -> page.getString("slug"))
        .containsExactly("how-it-works", "architecture", "walkthrough");

    mockMvc.perform(get("/api/portfolio/software-factory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Software Factory"))
        .andExpect(jsonPath("$.headline").value(startsWith("Small loops.")))
        .andExpect(jsonPath("$.statement.points.length()").value(4))
        .andExpect(jsonPath("$.highlights.length()").value(3))
        .andExpect(jsonPath("$.image.url").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.demo.videoUrl").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$.demo.chapters.length()").value(4))
        .andExpect(jsonPath("$.pages[1].body").value(containsString("Docker socket")))
        .andExpect(jsonPath("$.pages[2].body").value(containsString("<video")));
  }

  /** Every file lands in the library, and every path the stored copy names is one on disk. */
  @Test
  void importsEveryFileIntoTheLibraryAndPointsTheCopyAtIt() {
    launch();

    assertThat(media().countDocuments()).isEqualTo(SEED.media().size());
    String stored = factory().toJson();
    assertThat(stored).doesNotContain("{{media:");
    Matcher matcher = UPLOAD_PATH.matcher(stored);
    List<String> paths = matcher.results().map(result -> result.group()).distinct().toList();
    assertThat(paths).hasSize(SEED.media().size());
    assertThat(paths).allSatisfy(path -> assertThat(
        Files.isRegularFile(Path.of(uploadsPath, path.substring("/uploads/".length()))))
        .as(path).isTrue());
    for (String captions : List.of("demo.vtt", "walkthrough-ships.vtt",
        "walkthrough-log-fix.vtt", "walkthrough-cve.vtt")) {
      assertThat(media().find(new Document("legacyId", SEED.legacyIdPrefix() + captions))
          .first().getString("mimeType")).as(captions).isEqualTo("text/vtt");
    }
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
    projects().updateOne(new Document("slug", "software-factory"),
        new Document("$set", new Document("status", "COMING_SOON")));

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(media().countDocuments()).isEqualTo(SEED.media().size());
  }

  @Test
  void leavesAnAlreadyLaunchedProjectAloneAndImportsNothing() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    projects().updateOne(new Document("slug", "software-factory"),
        new Document("$set", new Document("status", "BETA").append("tagline", "Edited")));

    changeUnit.execution(mongoTemplate, mediaService);

    Document row = factory();
    assertThat(row.getString("status")).isEqualTo("BETA");
    assertThat(row.getString("name")).isEqualTo("Software Factory");
    assertThat(row.getString("tagline")).isEqualTo("Edited");
    assertThat(row.containsKey("pages")).isFalse();
    assertThat(media().countDocuments()).isZero();
  }

  /** All four project seeds share the library without colliding. */
  @Test
  void runsAfterTheOtherProjectPagesAndTouchesNoOtherProject() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    new V049SeedTermTimeProjectPage().execution(mongoTemplate, mediaService);
    new V050SeedCliniciansVeilProjectPage().execution(mongoTemplate, mediaService);
    new V057SeedCoParentsProjectPage().execution(mongoTemplate, mediaService);
    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(projects().countDocuments(new Document("status", "COMING_SOON"))).isZero();
    assertThat(projects().countDocuments(new Document("pages", new Document("$exists", true))))
        .isEqualTo(4);
    assertThat(media().countDocuments()).isEqualTo(V049SeedTermTimeProjectPage.MEDIA.size()
        + V050SeedCliniciansVeilProjectPage.SEED.media().size()
        + V057SeedCoParentsProjectPage.SEED.media().size() + SEED.media().size());
  }

  @Test
  void secondRunPreservesTheLaunchedPageExactly() {
    launch();
    Document before = factory();

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(factory()).isEqualTo(before);
    assertThat(media().countDocuments()).isEqualTo(SEED.media().size());
  }

  @Test
  void rollbackPreservesAnEditedHeadline() {
    launch();
    projects().updateOne(new Document("slug", "software-factory"),
        new Document("$set", new Document("headline", "My edited page")));
    Document before = factory();

    changeUnit.rollback(mongoTemplate);

    assertThat(factory()).isEqualTo(before);
  }

  @Test
  void servesEveryCaptionFile() throws Exception {
    launch();
    for (String file : List.of("demo.vtt", "walkthrough-ships.vtt", "walkthrough-log-fix.vtt",
        "walkthrough-cve.vtt")) {
      String url = media().find(new Document("legacyId", SEED.legacyIdPrefix() + file))
          .first().getString("originalPath");
      mockMvc.perform(get(url)).andExpect(status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
              .string(startsWith("WEBVTT")));
    }
  }

  @Test
  void rollbackRestoresTheOriginalTaglineAndSilhouette() {
    launch();

    changeUnit.rollback(mongoTemplate);

    Document row = factory();
    assertThat(row.getString("status")).isEqualTo("COMING_SOON");
    assertThat(row.getString("name")).isEqualTo("Software Factory");
    assertThat(row.getString("tagline"))
        .isEqualTo(V063SeedSoftwareFactoryProjectPage.COMING_SOON_TAGLINE);
    for (String field : PortfolioPageSeed.PAGE_FIELDS) {
      assertThat(row.containsKey(field)).as(field).isFalse();
    }
  }
}
