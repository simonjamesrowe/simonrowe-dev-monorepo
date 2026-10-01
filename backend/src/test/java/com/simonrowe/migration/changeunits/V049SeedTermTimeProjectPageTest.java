package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
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
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V049SeedTermTimeProjectPageTest extends AbstractIntegrationTest {

  /**
   * A {@code /media/...} path in the seed, ending at the first character no path contains.
   * Possessive, and the seed is a few kilobytes of our own text, so nothing can backtrack.
   */
  private static final Pattern MEDIA_PATH = Pattern.compile("/media/[A-Za-z0-9/._-]++");

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V049SeedTermTimeProjectPage changeUnit = new V049SeedTermTimeProjectPage();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private com.mongodb.client.MongoCollection<Document> collection() {
    return mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private Document termTime() {
    return collection().find(new Document("slug", "term-time")).first();
  }

  @Test
  void launchesTheComingSoonRowWithItsPageAndSubPages() throws Exception {
    new V048CreatePortfolioProjects().execution(mongoTemplate);

    changeUnit.execution(mongoTemplate);

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("BETA");
    assertThat(row.getString("name")).isEqualTo("Term Time");
    assertThat(row.getInteger("accentHue")).isEqualTo(152);
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
        .andExpect(jsonPath("$.demo.videoUrl").value("/media/portfolio/term-time/demo.mp4"))
        .andExpect(jsonPath("$.pages[1].body").value(
            org.hamcrest.Matchers.containsString("```java")));
  }

  @Test
  void leavesAnAlreadyLaunchedProjectAlone() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    collection().updateOne(new Document("slug", "term-time"),
        new Document("$set", new Document("status", "LIVE").append("tagline", "Edited")));

    changeUnit.execution(mongoTemplate);

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("LIVE");
    assertThat(row.getString("tagline")).isEqualTo("Edited");
    assertThat(row.containsKey("pages")).isFalse();
  }

  @Test
  void touchesNoOtherProject() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);

    changeUnit.execution(mongoTemplate);

    assertThat(collection().countDocuments(new Document("status", "COMING_SOON"))).isEqualTo(3);
    assertThat(collection().countDocuments(new Document("pages", new Document("$exists", true))))
        .isEqualTo(1);
  }

  @Test
  void rollbackReturnsTheRowToComingSoon() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    Document row = termTime();
    assertThat(row.getString("status")).isEqualTo("COMING_SOON");
    for (String field : V049SeedTermTimeProjectPage.PAGE_FIELDS) {
      assertThat(row.containsKey(field)).as(field).isFalse();
    }
  }

  /**
   * The seed names files in the frontend bundle, and a missing one is a broken image on a public
   * page with nothing in any log. Checked here because nothing else reads both sides.
   */
  @Test
  void everyMediaFileTheSeedNamesShipsInTheFrontend() throws Exception {
    String seed = V049SeedTermTimeProjectPage.seedFields().toJson();
    Matcher matcher = MEDIA_PATH.matcher(seed);
    List<String> paths = matcher.results().map(result -> result.group()).distinct().toList();

    assertThat(paths).hasSizeGreaterThan(8);
    Path publicDir = Path.of("..", "frontend", "public");
    assertThat(paths).allSatisfy(path ->
        assertThat(Files.isRegularFile(publicDir.resolve(path.substring(1))))
            .as(path).isTrue());
  }
}
