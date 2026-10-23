package com.simonrowe.factory.staticanalysis.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the static-analysis run index at startup.
 *
 * <p>Gated on {@code factory.staticanalysis.enabled}, as {@code CveFixIndexInitializer} is, so an
 * unreachable Mongo cannot fail the application context - and with it the webhook receiver and
 * the {@code code-review} worker - while the module is off.
 */
@Component
@ConditionalOnProperty(name = "factory.staticanalysis.enabled", havingValue = "true")
public class StaticAnalysisIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /**
   * Creates the initializer.
   *
   * @param mongoTemplate the template used to manage indexes on the factory's own database
   */
  public StaticAnalysisIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Creates the index. Idempotent, which matters because this runs on every restart.
   *
   * @param args the application arguments, unused
   */
  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(StaticAnalysisRunRecord.class)
        .createIndex(new Index().named("startedAt").on("startedAt", Sort.Direction.DESC));
  }
}
