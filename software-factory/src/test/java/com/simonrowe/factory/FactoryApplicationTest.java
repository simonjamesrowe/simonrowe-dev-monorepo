package com.simonrowe.factory;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The API and the Temporal worker share one context now, so a context-load test has to prove both
 * halves came up. An in-process test server stands in for Temporal; without it the worker connects
 * eagerly at startup and the context fails against a real address. The {@link
 * com.simonrowe.factory.feedback.persistence.LearningIndexInitializer} runs at startup too, so the
 * context now also needs a reachable Mongo.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.temporal.test-server.enabled=true")
@Testcontainers
class FactoryApplicationTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private WorkersTemplate workersTemplate;

  @Test
  void startsTheWebhookApiAndTheReviewWorkerInOneContext() {
    assertThat(workersTemplate.getWorkers()).isNotEmpty();
  }
}
