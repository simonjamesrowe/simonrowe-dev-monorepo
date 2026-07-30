package com.simonrowe.factory;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The API and the Temporal worker share one context now, so a context-load test has to prove both
 * halves came up. An in-process test server stands in for Temporal; without it the worker connects
 * eagerly at startup and the context fails against a real address.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.temporal.test-server.enabled=true")
class FactoryApplicationTest {

  @Autowired private WorkersTemplate workersTemplate;

  @Test
  void startsTheWebhookApiAndTheReviewWorkerInOneContext() {
    assertThat(workersTemplate.getWorkers()).isNotEmpty();
  }
}
