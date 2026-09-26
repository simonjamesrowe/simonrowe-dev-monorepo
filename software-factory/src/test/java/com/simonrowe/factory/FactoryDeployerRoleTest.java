package com.simonrowe.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.deploy.workflow.DeployWorkflowImpl;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflowImpl;
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
 * The deployer's context, selected the way production selects it: FACTORY_RUNTIME_ROLE becomes
 * the Spring profile, and the profile replaces the workflow package list.
 * FactoryWorkflowWorkersTest proves the configuration resolves; this proves the Temporal starter
 * honours it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.temporal.test-server.enabled=true", "FACTORY_RUNTIME_ROLE=deployer"})
@Testcontainers
class FactoryDeployerRoleTest {

  @Container
  private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

  @DynamicPropertySource
  static void mongoUri(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.mongodb.uri",
        () -> MONGO.getConnectionString() + "/software_factory_test");
  }

  @Autowired private WorkersTemplate workersTemplate;

  @Test
  void pollsOnlyTheDeployAndPlatformBackupWorkflows() {
    assertThat(RegisteredWorkflows.in(workersTemplate))
        .containsExactlyInAnyOrder(DeployWorkflowImpl.class, PlatformBackupWorkflowImpl.class);
  }
}
