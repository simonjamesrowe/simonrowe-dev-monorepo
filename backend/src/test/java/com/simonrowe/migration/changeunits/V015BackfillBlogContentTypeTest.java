package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.DBRef;
import com.simonrowe.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the blog content-type backfill against a real MongoDB. Mongock is disabled in
 * tests, so the change unit is driven directly.
 *
 * <p>Fixtures are written at the raw driver level because tags are persisted as
 * {@code @DBRef}s, which land on disk as sub-documents carrying {@code $ref} and {@code $id}
 * — exactly the shape the change unit inspects. Reading them back yields plain
 * {@link Document}s, so the assertions see what the migration sees.
 */
class V015BackfillBlogContentTypeTest extends AbstractIntegrationTest {

  private static final String BLOGS = "blogs";
  private static final String TAGS = "tags";
  private static final String CONTENT_TYPE = "contentType";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V015BackfillBlogContentType changeUnit = new V015BackfillBlogContentType();

  @BeforeEach
  @AfterEach
  void dropCollections() {
    mongoTemplate.getCollection(BLOGS).drop();
    mongoTemplate.getCollection(TAGS).drop();
  }

  @Test
  void classifiesPostTaggedWeeklyDigestAsDigest() {
    insertBlog("b-digest", tagRef(insertTag("Weekly Digest")));

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-digest")).isEqualTo("DIGEST");
  }

  @Test
  void classifiesPostWithoutTheDigestTagAsEngineering() {
    insertTag("Weekly Digest");
    insertBlog("b-tagged-other", tagRef(insertTag("Java")));
    insertBlog("b-untagged");

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-tagged-other")).isEqualTo("ENGINEERING");
    assertThat(contentTypeOf("b-untagged")).isEqualTo("ENGINEERING");
  }

  @Test
  void classifiesTagNamePaddedWithWhitespaceAsDigest() {
    insertBlog("b-padded", tagRef(insertTag("  weekly digest  ")));

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-padded")).isEqualTo("DIGEST");
  }

  @Test
  void classifiesUppercaseTagNameAsDigest() {
    insertBlog("b-upper", tagRef(insertTag("WEEKLY DIGEST")));

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-upper")).isEqualTo("DIGEST");
  }

  @Test
  void classifiesRawReferenceSubDocumentAsDigest() {
    insertBlog("b-raw-ref", rawTagRef(insertTag("Weekly Digest")));

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-raw-ref")).isEqualTo("DIGEST");
  }

  @Test
  void leavesAlreadyClassifiedPostUntouched() {
    mongoTemplate.getCollection(BLOGS).insertOne(
        blog("b-preclassified", tagRef(insertTag("Weekly Digest")))
            .append(CONTENT_TYPE, "ENGINEERING"));

    changeUnit.execution(mongoTemplate);

    assertThat(contentTypeOf("b-preclassified")).isEqualTo("ENGINEERING");
  }

  @Test
  void isIdempotentAcrossSecondExecution() {
    insertBlog("b-digest", tagRef(insertTag("Weekly Digest")));
    insertBlog("b-engineering");
    changeUnit.execution(mongoTemplate);
    final List<Document> afterFirstRun = allBlogs();

    changeUnit.execution(mongoTemplate);

    assertThat(allBlogs()).isEqualTo(afterFirstRun);
    assertThat(contentTypeOf("b-digest")).isEqualTo("DIGEST");
    assertThat(contentTypeOf("b-engineering")).isEqualTo("ENGINEERING");
  }

  /** Tag ids are Strings in this application, not {@code ObjectId}s. */
  private String insertTag(final String name) {
    final String id = UUID.randomUUID().toString();
    mongoTemplate.getCollection(TAGS).insertOne(new Document("_id", id).append("name", name));
    return id;
  }

  private void insertBlog(final String id, final Object... tagRefs) {
    mongoTemplate.getCollection(BLOGS).insertOne(blog(id, tagRefs));
  }

  private Document blog(final String id, final Object... tagRefs) {
    return new Document("_id", id)
        .append("title", "Post " + id)
        .append("published", true)
        .append(TAGS, List.of(tagRefs));
  }

  /** A reference exactly as Spring Data's {@code @DBRef} mapping writes it. */
  private DBRef tagRef(final String tagId) {
    return new DBRef(TAGS, tagId);
  }

  /** The same reference as a raw sub-document, the shape some codec registries decode to. */
  private Document rawTagRef(final String tagId) {
    return new Document("$ref", TAGS).append("$id", tagId);
  }

  private String contentTypeOf(final String blogId) {
    final Document blog = mongoTemplate.getCollection(BLOGS)
        .find(new Document("_id", blogId))
        .first();
    assertThat(blog).isNotNull();
    return blog.getString(CONTENT_TYPE);
  }

  private List<Document> allBlogs() {
    return mongoTemplate.getCollection(BLOGS)
        .find()
        .sort(new Document("_id", 1))
        .into(new ArrayList<>());
  }
}
