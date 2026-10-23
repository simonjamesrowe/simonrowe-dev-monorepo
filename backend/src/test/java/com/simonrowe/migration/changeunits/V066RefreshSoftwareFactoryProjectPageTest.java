package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoCollection;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.media.MediaService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V066RefreshSoftwareFactoryProjectPageTest extends AbstractIntegrationTest {

  private static final PortfolioPageSeed SEED = V066RefreshSoftwareFactoryProjectPage.SEED;

  /** Files V066 adds; the rest share V063's names and so its library assets. */
  private static final int NEW_FILES = 8;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaService mediaService;

  private final V066RefreshSoftwareFactoryProjectPage changeUnit =
      new V066RefreshSoftwareFactoryProjectPage();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);
    mongoTemplate.dropCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  private MongoCollection<Document> projects() {
    return mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private MongoCollection<Document> media() {
    return mongoTemplate.getCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  private Document factory() {
    return projects().find(new Document("slug", "software-factory")).first();
  }

  private void launchV063() {
    new V048CreatePortfolioProjects().execution(mongoTemplate);
    new V063SeedSoftwareFactoryProjectPage().execution(mongoTemplate, mediaService);
  }

  @Test
  void replacesTheV063PageWithTheNewCopyAndFilms() {
    launchV063();
    final long before = media().countDocuments();

    changeUnit.execution(mongoTemplate, mediaService);

    Document row = factory();
    assertThat(row.getString("headline")).startsWith("It reviews, fixes and ships.");
    assertThat(row.getString("tagline")).contains("runs itself");
    assertThat(row.toJson()).doesNotContain("{{media:").doesNotContain("demo.mp4")
        .doesNotContain("walkthrough-cve");
    Document demo = row.get("demo", Document.class);
    assertThat(demo.getString("videoUrl")).startsWith("/uploads/");
    assertThat(demo.getList("chapters", Document.class)).hasSize(6);
    assertThat(row.getList("pages", Document.class))
        .extracting(page -> page.getString("slug"))
        .containsExactly("how-it-works", "architecture", "walkthrough");
    assertThat(row.getList("pages", Document.class).get(2).getString("body"))
        .contains("<video").contains("/uploads/");
    assertThat(media().countDocuments()).isEqualTo(before + NEW_FILES);
  }

  @Test
  void sharedFilesReuseTheAssetsV063Imported() {
    launchV063();
    String heroBefore = factory().get("image", Document.class).getString("url");

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(factory().get("image", Document.class).getString("url")).isEqualTo(heroBefore);
  }

  /** A page edited in the CMS since V063 is the owner's, and stays exactly as it is. */
  @Test
  void leavesAnEditedPageAndTheLibraryAlone() {
    launchV063();
    projects().updateOne(new Document("slug", "software-factory"),
        new Document("$set", new Document("headline", "My own headline")));
    Document before = factory();
    long mediaBefore = media().countDocuments();

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(factory()).isEqualTo(before);
    assertThat(media().countDocuments()).isEqualTo(mediaBefore);
  }

  @Test
  void secondRunChangesNothing() {
    launchV063();
    changeUnit.execution(mongoTemplate, mediaService);
    Document before = factory();
    long mediaBefore = media().countDocuments();

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(factory()).isEqualTo(before);
    assertThat(media().countDocuments()).isEqualTo(mediaBefore);
  }

  @Test
  void rollbackPutsV063sCopyBack() {
    launchV063();
    changeUnit.execution(mongoTemplate, mediaService);

    changeUnit.rollback(mongoTemplate, mediaService);

    assertThat(factory().getString("headline"))
        .isEqualTo(V063SeedSoftwareFactoryProjectPage.SEED.headline());
  }

  @Test
  void theSeedNamesExactlyTheFilesItShips() {
    for (String file : SEED.media().keySet()) {
      assertThat(new ClassPathResource(SEED.seedDir() + "media/" + file).exists())
          .as(file).isTrue();
    }
    assertThat(SEED.seedFields(file -> "/uploads/x/" + file).toJson())
        .doesNotContain("{{media:");
  }
}
