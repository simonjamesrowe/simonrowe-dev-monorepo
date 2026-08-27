package com.simonrowe.factory.linear.persistence;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.stereotype.Component;

/**
 * Ensures the issue-sink index at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code — see {@code CveFixIndexInitializer}.
 *
 * <p>Gated on {@code factory.linear.enabled} so an unreachable Mongo cannot fail the whole
 * application context, and with it the GitHub webhook receiver and the {@code code-review}
 * worker.
 */
@Component
@ConditionalOnProperty(name = "factory.linear.enabled", havingValue = "true")
public class LinearIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /**
   * Creates the initializer.
   *
   * @param mongoTemplate the template used to manage indexes on the factory's own database
   */
  public LinearIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Creates the {@code {producer, lastSeenAt}} index. Idempotent, because this runs on every
   * restart. No index on the fingerprint: it is the document {@code _id}.
   *
   * @param args the application arguments, unused
   */
  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(LinearIssueRecord.class)
        .createIndex(
            new CompoundIndexDefinition(new Document("producer", 1).append("lastSeenAt", -1))
                .named("producer_lastSeen"));
  }
}
