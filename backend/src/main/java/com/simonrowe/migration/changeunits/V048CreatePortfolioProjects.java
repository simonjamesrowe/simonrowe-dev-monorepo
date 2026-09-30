package com.simonrowe.migration.changeunits;

import static org.springframework.data.domain.Sort.Direction.ASC;

import com.mongodb.client.model.UpdateOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates {@code portfolio_projects}' indexes and seeds the four launch projects.
 *
 * <p>The unique slug index is what makes a slug unique — {@code auto-index-creation} is off —
 * and {@code RestoreService} calls {@link #createIndexes} directly after a restore drops the
 * collection, because Mongock will not re-run a recorded unit.
 *
 * <p>The seed is all four projects as published and Coming soon. It inserts only slugs that are
 * absent, so a re-run never overwrites an edit made in the CMS, and the rollback removes only
 * rows still exactly as seeded.
 */
@ChangeUnit(id = "create-portfolio-projects", order = "048", author = "simonrowe")
public class V048CreatePortfolioProjects {

  public static final String COLLECTION = "portfolio_projects";
  public static final String SLUG_INDEX = "idx_portfolio_slug";
  public static final String PUBLISHED_ORDER_INDEX = "idx_portfolio_published_order";

  /** slug, name, accent hue, tagline — in display order. */
  static final List<String[]> SEED = List.of(
      new String[] {"software-factory", "Software Factory", "212",
          "An autonomous loop that reviews, deploys and watches this site."},
      new String[] {"term-time", "Term Time", "152",
          "A school assistant for parents, grounded in what the school publishes."},
      new String[] {"co-parents", "Co-Parents", "336",
          "Shared family admin for parents across two homes."},
      new String[] {"clinicians-veil", "Clinician's Veil", "266", "Details soon."});

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
    seed(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    var collection = mongoTemplate.getCollection(COLLECTION);
    for (String[] row : SEED) {
      collection.deleteOne(new Document("slug", row[0])
          .append("tagline", row[3])
          .append("status", "COMING_SOON"));
    }
    mongoTemplate.indexOps(COLLECTION).dropIndex(SLUG_INDEX);
    mongoTemplate.indexOps(COLLECTION).dropIndex(PUBLISHED_ORDER_INDEX);
  }

  public static void createIndexes(final MongoTemplate mongoTemplate) {
    var indexOps = mongoTemplate.indexOps(COLLECTION);
    indexOps.createIndex(new Index().named(SLUG_INDEX).on("slug", ASC).unique());
    indexOps.createIndex(new Index().named(PUBLISHED_ORDER_INDEX)
        .on("published", ASC).on("displayOrder", ASC));
  }

  static void seed(final MongoTemplate mongoTemplate) {
    var collection = mongoTemplate.getCollection(COLLECTION);
    Date now = Date.from(Instant.now());
    for (int order = 0; order < SEED.size(); order++) {
      String[] row = SEED.get(order);
      Document project = new Document("slug", row[0])
          .append("name", row[1])
          .append("tagline", row[3])
          .append("status", "COMING_SOON")
          .append("displayOrder", order)
          .append("published", true)
          .append("accentHue", Integer.parseInt(row[2]))
          .append("createdAt", now)
          .append("updatedAt", now);
      collection.updateOne(
          new Document("slug", row[0]),
          new Document("$setOnInsert", project),
          new UpdateOptions().upsert(true));
    }
  }
}
