package com.simonrowe.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.SharedMongoContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataMongoTest
class OriginalMediaPruneServiceDbTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MediaAssetRepository mediaAssetRepository;

  @TempDir
  private Path uploads;

  private OriginalMediaPruneService service;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    service = new OriginalMediaPruneService(
        mongoTemplate, mediaAssetRepository, uploads.toString());
    mediaAssetRepository.deleteAll();
    mongoTemplate.remove(new Query(), "blogs");
    mongoTemplate.remove(new Query(), "skill_groups");
    mongoTemplate.remove(new Query(), "tourSteps");
  }

  private String body(final String collection, final String id, final String field) {
    return mongoTemplate.findById(id, Document.class, collection).getString(field);
  }

  private void writeFile(final String relative) throws IOException {
    Path file = uploads.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "bytes");
  }

  private MediaAsset saveAsset(
      final String id, final String ext,
      final Map<String, MediaAsset.VariantInfo> variants) {
    return mediaAssetRepository.save(new MediaAsset(
        id, id + "." + ext, "image/" + ext, 1000,
        "/uploads/" + id + "/original." + ext, variants,
        Instant.EPOCH, Instant.EPOCH, "legacy-" + id));
  }

  @Test
  void rewritesBodiesAndDeletesOriginalsButKeepsVariantsAndSvgs() throws IOException {
    // Asset A: JPEG with a full variant set — original should be deleted and
    // inline references rewritten to the large variant.
    saveAsset("A", "jpg", Map.of(
        "large", new MediaAsset.VariantInfo("/uploads/A/A_large.jpg", 1200, 1200, 200),
        "small", new MediaAsset.VariantInfo("/uploads/A/A_small.jpg", 300, 300, 50)));
    writeFile("A/original.jpg");
    writeFile("A/A_large.jpg");

    // Asset S: SVG with no variants — original must be preserved and its inline
    // reference left untouched.
    saveAsset("S", "svg", Map.of());
    writeFile("S/original.svg");

    mongoTemplate.insert(new Document(Map.of(
        "_id", "blog1",
        "content", "Body ![d](/uploads/A/original.jpg) and ![l](/uploads/S/original.svg)")),
        "blogs");
    mongoTemplate.insert(new Document(Map.of(
        "_id", "sg1",
        "description", "Skills ![d](/uploads/A/original.jpg)")), "skill_groups");
    mongoTemplate.insert(new Document(Map.of(
        "_id", "ts1",
        "description", "Tour ![d](/uploads/A/original.jpg)")), "tourSteps");

    service.prune();

    // Bodies: A rewritten to its large variant, S left as-is.
    assertThat(body("blogs", "blog1", "content"))
        .isEqualTo("Body ![d](/uploads/A/A_large.jpg) and ![l](/uploads/S/original.svg)");
    assertThat(body("skill_groups", "sg1", "description"))
        .isEqualTo("Skills ![d](/uploads/A/A_large.jpg)");
    assertThat(body("tourSteps", "ts1", "description"))
        .isEqualTo("Tour ![d](/uploads/A/A_large.jpg)");

    // Files: A original gone, A variant kept, S original kept.
    assertThat(Files.exists(uploads.resolve("A/original.jpg"))).isFalse();
    assertThat(Files.exists(uploads.resolve("A/A_large.jpg"))).isTrue();
    assertThat(Files.exists(uploads.resolve("S/original.svg"))).isTrue();

    // Asset documents remain intact (resolver still keys off originalPath).
    assertThat(mediaAssetRepository.findById("A")).get()
        .extracting(MediaAsset::originalPath).isEqualTo("/uploads/A/original.jpg");
    assertThat(mediaAssetRepository.count()).isEqualTo(2);
  }

  @Test
  void isIdempotentOnSecondRun() throws IOException {
    saveAsset("A", "jpg", Map.of(
        "large", new MediaAsset.VariantInfo("/uploads/A/A_large.jpg", 1200, 1200, 200)));
    writeFile("A/original.jpg");
    mongoTemplate.insert(new Document(Map.of(
        "_id", "blog1", "content", "![d](/uploads/A/original.jpg)")), "blogs");

    service.prune();
    // Second run must be a no-op and must not throw.
    service.prune();

    assertThat(body("blogs", "blog1", "content"))
        .isEqualTo("![d](/uploads/A/A_large.jpg)");
    assertThat(Files.exists(uploads.resolve("A/original.jpg"))).isFalse();
  }
}
