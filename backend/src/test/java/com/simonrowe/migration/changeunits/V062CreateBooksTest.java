package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoCollection;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.media.MediaService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V062CreateBooksTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaService mediaService;

  @Value("${uploads.path}")
  private String uploadsPath;

  private final V062CreateBooks changeUnit = new V062CreateBooks();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V062CreateBooks.COLLECTION);
    mongoTemplate.dropCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  private MongoCollection<Document> books() {
    return mongoTemplate.getCollection(V062CreateBooks.COLLECTION);
  }

  private MongoCollection<Document> media() {
    return mongoTemplate.getCollection(V049SeedTermTimeProjectPage.MEDIA_COLLECTION);
  }

  @Test
  void seedsEveryBookWithTheShortlistPublishedAndTheRestAsDrafts() throws Exception {
    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(books().countDocuments()).isEqualTo(30);
    assertThat(books().countDocuments(new Document("published", true))).isEqualTo(16);

    mockMvc.perform(get("/api/books"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(16))
        .andExpect(jsonPath("$[0].slug").value("vibe-coding"))
        .andExpect(jsonPath("$[0].authors[0]").value("Gene Kim"))
        .andExpect(jsonPath("$[0].coverImage.url").value(startsWith("/uploads/")))
        .andExpect(jsonPath("$[0].purchaseUrl")
            .value("https://www.amazon.co.uk/dp/B0F8C22MDN"))
        .andExpect(jsonPath("$[0].note").isNotEmpty());
  }

  /** Every cover lands in the library once, and every book points at a file on disk. */
  @Test
  void importsEveryCoverIntoTheLibraryAndPointsTheBookAtIt() {
    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(media().countDocuments()).isEqualTo(30);
    for (Document book : books().find()) {
      String url = book.get("coverImage", Document.class).getString("url");
      assertThat(Files.isRegularFile(Path.of(uploadsPath, url.substring("/uploads/".length()))))
          .as(url).isTrue();
      assertThat(book.containsKey("cover")).isFalse();
    }
  }

  @Test
  void secondRunAddsNothingAndNeverOverwritesAnEdit() {
    changeUnit.execution(mongoTemplate, mediaService);
    books().updateOne(new Document("slug", "drive"),
        new Document("$set", new Document("summary", "Edited in the CMS.")));
    books().deleteOne(new Document("slug", "peopleware"));

    changeUnit.execution(mongoTemplate, mediaService);

    assertThat(books().countDocuments()).isEqualTo(30);
    assertThat(media().countDocuments()).isEqualTo(30);
    assertThat(books().find(new Document("slug", "drive")).first().getString("summary"))
        .isEqualTo("Edited in the CMS.");
  }

  @Test
  void rollbackRemovesOnlyBooksUntouchedSinceSeeding() {
    changeUnit.execution(mongoTemplate, mediaService);
    books().updateOne(new Document("slug", "accelerate"), new Document("$set",
        new Document("note", "Mine now.").append("updatedAt", new Date())));

    changeUnit.rollback(mongoTemplate);

    assertThat(books().find().map(book -> book.getString("slug")).into(new ArrayList<>()))
        .containsExactly("accelerate");
  }

  /** The seed and its covers directory name the same files, and every slug is distinct. */
  @Test
  void theSeedNamesExactlyTheCoversItShipsWithDistinctSlugs() throws Exception {
    List<Document> seed = V062CreateBooks.seedBooks();
    assertThat(new HashSet<>(seed.stream().map(book -> book.getString("slug")).toList()))
        .hasSize(seed.size());
    for (Document book : seed) {
      String cover = V062CreateBooks.SEED_DIR + "covers/" + book.getString("cover");
      assertThat(new ClassPathResource(cover).exists()).as(book.getString("slug")).isTrue();
    }
    try (var files = Files.list(
        new ClassPathResource(V062CreateBooks.SEED_DIR + "covers/").getFile().toPath())) {
      assertThat(files.count()).isEqualTo(seed.size());
    }
  }
}
