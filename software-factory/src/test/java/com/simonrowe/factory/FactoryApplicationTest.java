package com.simonrowe.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.workflow.CodeReviewWorkflowImpl;
import com.simonrowe.factory.cvefix.workflow.CveFixWorkflowImpl;
import com.simonrowe.factory.deploy.api.DeployWorkflowService;
import com.simonrowe.factory.deploy.persistence.DeployIndexInitializer;
import com.simonrowe.factory.deploy.workflow.DeployActivitiesImpl;
import com.simonrowe.factory.feedback.workflow.ReviewFeedbackWorkflowImpl;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflowImpl;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The API and the Temporal worker share one context now, so a context-load test has to prove both
 * halves came up. An in-process test server stands in for Temporal; without it the worker connects
 * eagerly at startup and the context fails against a real address. Spring Data Mongo's
 * auto-configuration still wires a {@code MongoTemplate}/repository beans into this context
 * regardless of {@code factory.feedback.enabled}, so a reachable Mongo remains a prerequisite even
 * though {@link com.simonrowe.factory.feedback.persistence.LearningIndexInitializer} itself is now
 * gated on that property and does not run here by default.
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
        "spring.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private WorkersTemplate workersTemplate;
  @Autowired private ApplicationContext context;

  @Test
  void startsTheWebhookApiAndTheReviewWorkerInOneContext() {
    assertThat(workersTemplate.getWorkers()).isNotEmpty();
  }

  @Test
  void startsWithDeployExecutionOffButTheGuardedManualClientAvailable() {
    // The default shape, and the one this feature ships in. Merging must change nothing in
    // production until an operator opts in.
    assertThat(context.getBeanNamesForType(DeployActivitiesImpl.class)).isEmpty();
    assertThat(context.getBeanNamesForType(DeployWorkflowService.class)).hasSize(1);

    // And no deploy index is created. Same reason CveFixIndexInitializer is gated: an
    // unreachable Mongo must not fail this context and take the webhook receiver and the
    // code-review worker - neither of which needs Mongo - down with it.
    assertThat(context.getBeanNamesForType(DeployIndexInitializer.class)).isEmpty();
  }

  @Test
  void pollsOnlyTheWorkflowsWhoseActivitiesRunInThisContainer() {
    // The software-factory role (also the default when FACTORY_RUNTIME_ROLE is unset). It used
    // to poll the deploy and platform-backup queues for workflow tasks as well, because
    // @WorkflowImpl scanning cannot be gated by a condition. That let a lagging deployer and this
    // container run one workflow on two builds; see FactoryWorkflowWorkersTest.
    assertThat(RegisteredWorkflows.in(workersTemplate))
        .containsExactlyInAnyOrder(
            CodeReviewWorkflowImpl.class,
            ReviewFeedbackWorkflowImpl.class,
            CveFixWorkflowImpl.class,
            LogWatchWorkflowImpl.class);
  }
}
