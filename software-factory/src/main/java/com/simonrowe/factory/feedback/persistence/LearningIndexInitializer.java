package com.simonrowe.factory.feedback.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the learnings index at startup. Mongock stays backend-owned; this database belongs to
 * the factory, so index management lives here in code. Failing fast on an unreachable Mongo is
 * deliberate — a factory that cannot record evidence should not accept feedback work.
 */
@Component
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
