package com.simonrowe.migration.changeunits;

import static org.springframework.data.domain.Sort.Direction.ASC;

import com.mongodb.client.model.UpdateOptions;
import com.simonrowe.media.MediaAsset;
import com.simonrowe.media.MediaService;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates {@code books}' indexes and seeds the Reading list from {@code seed/books/}.
 *
 * <p>The seed is the reading list from Trisha Gee's talk "Shiny New Tools Won't Fix Your
 * Problem": all thirty books, with the sixteen that fit this site published and the rest left as
 * drafts to switch on in the CMS. Each cover is imported into the media library under the key
 * {@code seed:books/<file>}, so a second run finds it rather than importing it again, and the
 * book points at the library's path.
 *
 * <p>Books are inserted only where the slug is absent, so a re-run never overwrites an edit made
 * at {@code /admin/books}. The rollback removes only rows untouched since seeding (their
 * {@code updatedAt} still equals {@code createdAt}); the imported covers stay in the library,
 * where they may have been reused.
 *
 * <p>The unique slug index is what makes a slug unique ({@code auto-index-creation} is off), and
 * {@code RestoreService} calls {@link #createIndexes} directly after a restore drops the
 * collection, because Mongock will not re-run a recorded unit.
 */
@ChangeUnit(id = "create-books", order = "062", author = "simonrowe")
public class V062CreateBooks {

  public static final String COLLECTION = "books";
  public static final String SLUG_INDEX = "idx_books_slug";
  public static final String PUBLISHED_ORDER_INDEX = "idx_books_published_order";

  static final String SEED_DIR = "seed/books/";
  static final String LEGACY_ID_PREFIX = "seed:books/";

  @Execution
  public void execution(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    createIndexes(mongoTemplate);
    V049SeedTermTimeProjectPage.createMediaIndexes(mongoTemplate);
    seed(mongoTemplate, mediaService);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    List<String> slugs = seedBooks().stream().map(book -> book.getString("slug")).toList();
    mongoTemplate.getCollection(COLLECTION).deleteMany(new Document("slug",
        new Document("$in", slugs))
        .append("$expr", new Document("$eq", List.of("$createdAt", "$updatedAt"))));
    mongoTemplate.indexOps(COLLECTION).dropIndex(SLUG_INDEX);
    mongoTemplate.indexOps(COLLECTION).dropIndex(PUBLISHED_ORDER_INDEX);
  }

  public static void createIndexes(final MongoTemplate mongoTemplate) {
    var indexOps = mongoTemplate.indexOps(COLLECTION);
    indexOps.createIndex(new Index().named(SLUG_INDEX).on("slug", ASC).unique());
    indexOps.createIndex(new Index().named(PUBLISHED_ORDER_INDEX)
        .on("published", ASC).on("displayOrder", ASC));
  }

  static void seed(final MongoTemplate mongoTemplate, final MediaService mediaService) {
    var collection = mongoTemplate.getCollection(COLLECTION);
    List<Document> books = seedBooks();
    for (int order = 0; order < books.size(); order++) {
      Document seed = books.get(order);
      String slug = seed.getString("slug");
      if (collection.countDocuments(new Document("slug", slug)) > 0) {
        // Already there, seeded or added by hand: leave it, and its cover, exactly as it is.
        continue;
      }
      String cover = seed.getString("cover");
      MediaAsset asset = mediaService.importFile(
          readBytes("covers/" + cover), cover, "image/jpeg", LEGACY_ID_PREFIX + cover);
      Date now = Date.from(Instant.now());
      Document book = new Document(seed)
          .append("coverImage", new Document("url", asset.originalPath()))
          .append("displayOrder", order)
          .append("createdAt", now)
          .append("updatedAt", now);
      book.remove("cover");
      // Upsert rather than insert, so a book created between the count and here is kept.
      collection.updateOne(new Document("slug", slug), new Document("$setOnInsert", book),
          new UpdateOptions().upsert(true));
    }
  }

  /** The seed's books in display order, each still naming its cover file. */
  static List<Document> seedBooks() {
    String json = new String(readBytes("books.json"), StandardCharsets.UTF_8);
    return Document.parse(json).getList("books", Document.class);
  }

  static byte[] readBytes(final String file) {
    try (InputStream in = new ClassPathResource(SEED_DIR + file).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Missing books seed file " + file, e);
    }
  }
}
