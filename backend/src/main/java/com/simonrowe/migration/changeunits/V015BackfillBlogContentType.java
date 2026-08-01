package com.simonrowe.migration.changeunits;

import com.mongodb.DBRef;
import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Backfills the {@code contentType} field on existing blog posts so the blog listing can
 * separate hand-written engineering posts from generated weekly digests.
 *
 * <p>A post is classified {@code DIGEST} when it references a tag whose name is
 * "Weekly Digest" (compared case- and whitespace-insensitively, since the tag repository's
 * {@code findByName} is exact and cannot express that), and {@code ENGINEERING} otherwise.
 *
 * <p>Works at the raw {@link Document} level rather than through the {@code Blog} records:
 * there are two of them mapped to this collection with different component orders, and a
 * record round-trip would rewrite fields this migration has no business touching.
 *
 * <p>Idempotent: only documents with no {@code contentType} are considered, so a re-run is
 * a no-op and a later manual reclassification is never overwritten.
 */
@ChangeUnit(id = "backfill-blog-content-type", order = "015", author = "simonrowe")
public class V015BackfillBlogContentType {

  private static final String BLOGS = "blogs";
  private static final String TAGS = "tags";
  private static final String CONTENT_TYPE = "contentType";
  private static final String DIGEST_TAG_NAME = "weekly digest";
  private static final String ENGINEERING = "ENGINEERING";
  private static final String DIGEST = "DIGEST";

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    final Set<Object> digestTagIds = findDigestTagIds(mongoTemplate);
    final MongoCollection<Document> blogs = mongoTemplate.getCollection(BLOGS);

    for (final Document blog : blogs.find(new Document(CONTENT_TYPE, null))) {
      final String contentType = referencesDigestTag(blog, digestTagIds) ? DIGEST : ENGINEERING;
      blogs.updateOne(
          new Document("_id", blog.get("_id")),
          new Document("$set", new Document(CONTENT_TYPE, contentType)));
    }
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.getCollection(BLOGS).updateMany(
        new Document(),
        new Document("$unset", new Document(CONTENT_TYPE, "")));
  }

  /** Ids of every tag named "Weekly Digest", ignoring case and surrounding whitespace. */
  private Set<Object> findDigestTagIds(final MongoTemplate mongoTemplate) {
    final Set<Object> ids = new HashSet<>();
    for (final Document tag : mongoTemplate.getCollection(TAGS).find()) {
      final String name = tag.getString("name");
      if (name != null && DIGEST_TAG_NAME.equals(name.trim().toLowerCase(Locale.ROOT))) {
        ids.add(tag.get("_id"));
      }
    }
    return ids;
  }

  /** Whether a blog document's {@code tags} array references any of the given tag ids. */
  private boolean referencesDigestTag(final Document blog, final Set<Object> digestTagIds) {
    if (digestTagIds.isEmpty()) {
      return false;
    }
    final List<?> tags = blog.getList(TAGS, Object.class);
    if (tags == null) {
      return false;
    }
    return tags.stream().anyMatch(tag -> digestTagIds.contains(referencedId(tag)));
  }

  /**
   * The tag id a {@code tags} array element points at, or {@code null} when the element is
   * not a reference at all.
   *
   * <p>Tags are stored as {@code @DBRef}s, which land on disk as sub-documents carrying
   * {@code $ref} and {@code $id}. The Mongo driver decodes those back into {@link DBRef}
   * instances rather than plain {@link Document}s, so that is the shape seen in practice;
   * the raw-document shape is tolerated as well.
   */
  private Object referencedId(final Object tag) {
    if (tag instanceof DBRef ref) {
      return ref.getId();
    }
    if (tag instanceof Document ref) {
      return ref.get("$id");
    }
    return null;
  }
}
