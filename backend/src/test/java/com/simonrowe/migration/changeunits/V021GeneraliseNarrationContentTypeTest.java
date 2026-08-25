package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Exercises the blogId → contentType/contentId migration against a real MongoDB. Mongock is
 * disabled in tests, so the change unit is driven directly. This unit performs no external
 * I/O, so the standard change-unit pattern applies rather than the isolated-boot one.
 */
class V021GeneraliseNarrationContentTypeTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "narrations";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V021GeneraliseNarrationContentType changeUnit =
      new V021GeneraliseNarrationContentType();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
  }

  @Test
  void movesBlogIdOntoContentTypeAndContentId() {
    insertLegacy("narration-1", "blog-1");

    changeUnit.execution(mongoTemplate);

    Document migrated = findById("narration-1");
    assertThat(migrated.getString("contentType")).isEqualTo("BLOG");
    assertThat(migrated.getString("contentId")).isEqualTo("blog-1");
    assertThat(migrated.containsKey("blogId")).isFalse();
  }

  @Test
  void leavesEverythingElseOnTheDocumentAlone() {
    insertLegacy("narration-1", "blog-1");

    changeUnit.execution(mongoTemplate);

    Document migrated = findById("narration-1");
    // The fingerprint IS the id and the audio directory name. If the migration touched it,
    // every stored MP3 would be orphaned.
    assertThat(migrated.getString("fingerprint")).isEqualTo("narration-1");
    assertThat(migrated.getString("status")).isEqualTo("READY");
    assertThat(migrated.getString("audioPath"))
        .isEqualTo("/uploads/narrations/narration-1/narration.mp3");
    assertThat(migrated.getInteger("version")).isEqualTo(3);
  }

  @Test
  void migratesEveryLegacyDocument() {
    insertLegacy("narration-1", "blog-1");
    insertLegacy("narration-2", "blog-2");
    insertLegacy("narration-3", "blog-2");

    changeUnit.execution(mongoTemplate);

    assertThat(mongoTemplate.getCollection(COLLECTION)
        .countDocuments(new Document("blogId", new Document("$exists", true))))
        .isZero();
    assertThat(mongoTemplate.getCollection(COLLECTION)
        .countDocuments(new Document("contentType", "BLOG")))
        .isEqualTo(3);
  }

  @Test
  void isIdempotentSoRerunningChangesNothing() {
    insertLegacy("narration-1", "blog-1");

    changeUnit.execution(mongoTemplate);
    Document afterFirst = findById("narration-1");

    changeUnit.execution(mongoTemplate);

    assertThat(findById("narration-1")).isEqualTo(afterFirst);
  }

  @Test
  void leavesAnAlreadyMigratedDocumentUntouched() {
    Document alreadyMigrated = new Document("_id", "narration-9")
        .append("contentType", "ARTICLE_SUMMARY")
        .append("contentId", "article-9")
        .append("status", "READY");
    mongoTemplate.getCollection(COLLECTION).insertOne(alreadyMigrated);

    changeUnit.execution(mongoTemplate);

    Document after = findById("narration-9");
    assertThat(after.getString("contentType")).isEqualTo("ARTICLE_SUMMARY");
    assertThat(after.getString("contentId")).isEqualTo("article-9");
  }

  @Test
  void swapsTheBlogIndexesForContentIndexes() {
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named("blogId").on("blogId", Sort.Direction.ASC));
    mongoTemplate.indexOps(COLLECTION).createIndex(new Index()
        .named("idx_narration_blog_updated")
        .on("blogId", Sort.Direction.ASC)
        .on("updatedAt", Sort.Direction.DESC));

    changeUnit.execution(mongoTemplate);

    assertThat(indexNames())
        .contains("contentId", "idx_narration_content_updated")
        .doesNotContain("blogId", "idx_narration_blog_updated");
  }

  @Test
  void toleratesTheOldIndexesAlreadyBeingAbsent() {
    insertLegacy("narration-1", "blog-1");

    changeUnit.execution(mongoTemplate);

    assertThat(indexNames()).contains("idx_narration_content_updated");
  }

  @Test
  void rollbackRestoresBlogIdAndTheOldIndexes() {
    insertLegacy("narration-1", "blog-1");
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    Document restored = findById("narration-1");
    assertThat(restored.getString("blogId")).isEqualTo("blog-1");
    assertThat(restored.containsKey("contentId")).isFalse();
    assertThat(restored.containsKey("contentType")).isFalse();
    assertThat(indexNames())
        .contains("blogId", "idx_narration_blog_updated")
        .doesNotContain("contentId", "idx_narration_content_updated");
  }

  private void insertLegacy(final String id, final String blogId) {
    mongoTemplate.getCollection(COLLECTION).insertOne(new Document("_id", id)
        .append("blogId", blogId)
        .append("fingerprint", id)
        .append("status", "READY")
        .append("version", 3)
        .append("scriptCharacterCount", 100)
        .append("audioPath", "/uploads/narrations/" + id + "/narration.mp3")
        .append("checksumSha256", "checksum")
        .append("updatedAt", new Date()));
  }

  private Document findById(final String id) {
    return mongoTemplate.getCollection(COLLECTION)
        .find(new Document("_id", id)).first();
  }

  private List<String> indexNames() {
    return mongoTemplate.getCollection(COLLECTION).listIndexes()
        .map(index -> index.getString("name"))
        .into(new ArrayList<>());
  }
}
