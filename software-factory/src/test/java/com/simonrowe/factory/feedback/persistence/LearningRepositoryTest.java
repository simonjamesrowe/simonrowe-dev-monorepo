package com.simonrowe.factory.feedback.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class LearningRepositoryTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private LearningRepository repository;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void savesAndUpsertsByDeterministicId() {
    LearningRecord initial =
        new LearningRecord(
            LearningRecord.idFor("example", "project", 42),
            "example", "project", 42, "Title", "https://pr/42", true,
            "review-feedback-example-project-42", Instant.parse("2026-08-09T00:00:00Z"), "v1",
            List.of(new Lesson(
                "Pin images", "Always pin container image versions.", LessonScope.ORG_WIDE,
                List.of("https://c/1"), LessonSource.HUMAN, LessonConfidence.HIGH)),
            new LearningRecord.Distillation(
                DistillationStatus.SKIPPED_NO_LESSONS, List.of(), null));
    repository.save(initial);

    LearningRecord updated =
        new LearningRecord(
            initial.id(), initial.owner(), initial.repository(), initial.pullNumber(),
            initial.prTitle(), initial.prUrl(), initial.merged(), initial.workflowId(),
            initial.harvestedAt(), initial.promptVersion(), initial.lessons(),
            new LearningRecord.Distillation(
                DistillationStatus.PROPOSED, List.of("https://pr/feedback/1"), null));
    repository.save(updated);

    assertThat(repository.count()).isEqualTo(1);
    assertThat(repository.findById(initial.id()).orElseThrow().distillation().status())
        .isEqualTo(DistillationStatus.PROPOSED);
  }

  @Test
  void indexInitializerCreatesTheUniqueCompoundIndex() {
    new LearningIndexInitializer(mongoTemplate).run(null);

    assertThat(
            mongoTemplate.indexOps(LearningRecord.class).getIndexInfo().stream()
                .anyMatch(index -> index.isUnique() && index.getName().equals("owner_repo_pr")))
        .isTrue();
  }
}
