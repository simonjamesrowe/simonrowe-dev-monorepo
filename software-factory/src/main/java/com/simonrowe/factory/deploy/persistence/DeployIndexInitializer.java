package com.simonrowe.factory.deploy.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the deploy indexes at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code — the same split {@code
 * CveFixIndexInitializer} makes.
 *
 * <p>Gated on {@code factory.deploy.enabled} so an unreachable Mongo cannot fail the whole
 * application context — and with it the GitHub webhook receiver and the {@code code-review}
 * Temporal worker, neither of which has any Mongo dependency — when the feature isn't in use.
 */
@Component
@ConditionalOnProperty(name = "factory.deploy.enabled", havingValue = "true")
public class DeployIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /**
   * Creates the initializer.
   *
   * @param mongoTemplate the template used to manage indexes on the factory's own database
   */
  public DeployIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Creates the deploy indexes. Safe to call more than once: index creation with the same name and
   * options is idempotent, which matters because this runs on every application restart.
   *
   * @param args the application arguments, unused
   */
  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(DeployRunRecord.class)
        .createIndex(new Index().named("startedAt").on("startedAt", Sort.Direction.DESC));
  }
}
