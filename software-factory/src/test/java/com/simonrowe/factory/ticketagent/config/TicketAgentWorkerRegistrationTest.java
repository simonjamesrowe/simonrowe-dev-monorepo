package com.simonrowe.factory.ticketagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.ticketagent.api.TicketAgentController;
import com.simonrowe.factory.ticketagent.schedule.TicketAgentScheduleInitializer;
import com.simonrowe.factory.ticketagent.workflow.TicketAgentActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.spring.boot.ActivityImpl;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the gate on the ticket agent's executor and its schedule.
 *
 * <p>Component-scanned for real: a class-level {@code @ConditionalOnProperty} is evaluated by the
 * scanner, and an explicit {@code @Bean} declaration would silently ignore it.
 */
class TicketAgentWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedTicketAgentPackage.class);

  @Test
  void theExecutorAndScheduleAreAbsentByDefault() {
    // The deployer's shape: no FACTORY_TICKET_AGENT_ENABLED, so application.yml's `false` applies.
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(TicketAgentActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(TicketAgentScheduleInitializer.class);
          // The API stays up so a caller gets an honest 503 rather than a 404.
          assertThat(context).hasSingleBean(TicketAgentController.class);
        });
  }

  @Test
  void theExecutorIsPresentWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.ticket-agent.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(TicketAgentActivitiesImpl.class));
  }

  @Test
  void theActivitiesPollTheTicketAgentQueue() {
    ActivityImpl annotation = TicketAgentActivitiesImpl.class.getAnnotation(ActivityImpl.class);
    assertThat(annotation.taskQueues()).containsExactly(TicketAgentTaskQueues.TICKET_AGENT);
  }

  /** A real component scan of the ticket agent package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.ticketagent")
  @EnableConfigurationProperties({TicketAgentProperties.class, NpmRepairProperties.class})
  static class ScannedTicketAgentPackage {

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    ScheduleClient scheduleClient() {
      return mock(ScheduleClient.class);
    }

    @Bean
    ClaudeCliRunner claudeCliRunner() {
      return mock(ClaudeCliRunner.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    LinearGateway linearGateway() {
      return mock(LinearGateway.class);
    }

    @Bean
    LinearProperties linearProperties() {
      return new LinearProperties(true, "key", null, "SIM", null, false, null, null);
    }

    @Bean
    GitHubCredentials gitHubCredentials() {
      return mock(GitHubCredentials.class);
    }

    @Bean
    RepositoryWorkspaceFactory repositoryWorkspaceFactory() {
      return mock(RepositoryWorkspaceFactory.class);
    }

    @Bean
    CodeReviewProperties codeReviewProperties() {
      return new CodeReviewProperties(
          new CodeReviewProperties.Github(
              "https://api.github.com", "", "", "", "", Duration.ofSeconds(5)),
          null, new CodeReviewProperties.Api("", null), null);
    }

    @Bean
    FactoryTokenAuthenticator factoryTokenAuthenticator(final CodeReviewProperties properties) {
      return new FactoryTokenAuthenticator(properties);
    }
  }
}
