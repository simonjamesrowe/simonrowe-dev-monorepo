package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Marks Term Time, CoParent and Clinician's Veil Live. All three are in daily use; the Beta and
 * In development badges their page seeds gave them (V049, V057, V050) no longer describe them.
 *
 * <p>Each row changes only while it still carries the status its seed left, so a status chosen
 * in the CMS since is never overwritten. Idempotent: a second run matches nothing.
 */
@ChangeUnit(id = "mark-portfolio-projects-live", order = "065", author = "simonrowe")
public class V065MarkPortfolioProjectsLive {

  private static final Logger log = LoggerFactory.getLogger(V065MarkPortfolioProjectsLive.class);

  /** Slug to the status its seed left it in. */
  static final Map<String, String> SEEDED_STATUS = Map.of(
      "term-time", "BETA",
      "co-parents", "IN_DEVELOPMENT",
      "clinicians-veil", "IN_DEVELOPMENT");

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    long changed = 0;
    for (Map.Entry<String, String> project : SEEDED_STATUS.entrySet()) {
      changed += mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION)
          .updateOne(
              new Document("slug", project.getKey()).append("status", project.getValue()),
              new Document("$set", new Document("status", "LIVE")
                  .append("updatedAt", Date.from(Instant.now()))))
          .getModifiedCount();
    }
    log.info("Marked {} portfolio project(s) Live", changed);
  }

  /** Puts each seeded status back, only on rows still Live. */
  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    SEEDED_STATUS.forEach((slug, status) ->
        mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION).updateOne(
            new Document("slug", slug).append("status", "LIVE"),
            new Document("$set", new Document("status", status))));
  }
}
