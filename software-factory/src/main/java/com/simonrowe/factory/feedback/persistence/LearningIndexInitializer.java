package com.simonrowe.factory.feedback.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the learnings index at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code. Failing fast on an unreachable Mongo is
 * deliberate — a factory that cannot record evidence should not accept feedback work.
 *
 * <p>Gated on {@code factory.feedback.enabled} so an unreachable Mongo cannot fail the whole
 * application context — and with it the GitHub webhook receiver and the {@code code-review}
 * Temporal worker, neither of which has any Mongo dependency — when the feedback feature isn't
 * even in use.
 */
@Component
@ConditionalOnProperty(name = "factory.feedback.enabled", havingValue = "true")
public class LearningIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  public LearningIndexInitializer(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void run(final ApplicationArguments args) {
    mongoTemplate
        .indexOps(LearningRecord.class)
        .createIndex(
            new Index()
                .named("owner_repo_pr")
                .on("owner", Sort.Direction.ASC)
                .on("repository", Sort.Direction.ASC)
                .on("pullNumber", Sort.Direction.ASC)
                .unique());
  }
}
