package com.simonrowe.factory.deploy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.deploy.api.DeployWorkflowService;
import com.simonrowe.factory.deploy.persistence.DeployIndexInitializer;
import com.simonrowe.factory.deploy.persistence.DeployRunRepository;
import com.simonrowe.factory.deploy.workflow.DeployActivitiesImpl;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the two feature gates.
 *
 * <p><strong>Do not delete these tests.</strong> {@link DeployActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is the only thing that keeps the Docker socket out of {@code
 * software-factory}, the JVM that terminates untrusted internet traffic.
 *
 * <p>Both containers run the same image and both register a workflow-task poller on the {@code
 * deploy} queue, because {@code @WorkflowImpl} classpath scanning is unconditional and cannot be
 * gated — those classes are not Spring beans. Only the <em>activity</em> bean can be gated, and it
 * is the activities that touch the socket, the deploy directory and {@code restart-prod.sh}.
 *
 * <p>If the condition is removed, nothing fails loudly. Whichever JVM happens to win an activity
 * task runs it, so deploys fail intermittently on a missing docker binary — an error that looks
 * like an infrastructure problem and not like a missing annotation.
 *
 * <p><strong>Why this test component-scans rather than declaring the beans directly.</strong> A
 * class-level {@code @ConditionalOnProperty} is evaluated by the component scanner, not by the
 * bean factory: declaring the same class through an explicit {@code @Bean} method registers it
 * <em>unconditionally</em> and the annotation is silently ignored. A harness built that way passes
 * for `enabled=true` and fails for `enabled=false` while telling you nothing about production —
 * so this uses a real scan of the deploy package, with only the module's external collaborators
 * mocked.
 */
class DeployWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedDeployPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(DeployActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(DeployWorkflowService.class);
          // And nothing touches Mongo either, for the reason CveFixIndexInitializer documents:
          // an unreachable Mongo must not fail the context and take the webhook receiver and the
          // code-review worker down with it.
          assertThat(context).doesNotHaveBean(DeployIndexInitializer.class);
        });
  }

  @Test
  void theExecutorIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.deploy.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DeployActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentOnlyWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.deploy.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DeployActivitiesImpl.class);
              assertThat(context).hasSingleBean(DeployIndexInitializer.class);
            });
  }

  @Test
  void theTriggerIsAbsentWhenItsOwnFlagIsOff() {
    // Separate from the executor flag on purpose: a broken deployer must be silenceable without
    // silencing code review, and the executor has to be enabled and rehearsed before the trigger.
    runner
        .withPropertyValues("factory.deploy.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(DeployWorkflowService.class));
  }

  @Test
  void theTriggerIsPresentOnlyWhenItsOwnFlagIsTrue() {
    runner
        .withPropertyValues("factory.deploy.trigger-enabled=true")
        .run(context -> assertThat(context).hasSingleBean(DeployWorkflowService.class));
  }

  @Test
  void theDeployerShapeExecutesButNeverTriggers() {
    runner
        .withPropertyValues("factory.deploy.enabled=true", "factory.deploy.trigger-enabled=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DeployActivitiesImpl.class);
              assertThat(context).doesNotHaveBean(DeployWorkflowService.class);
            });
  }

  @Test
  void theWebhookReceiverShapeTriggersButHoldsNoDeployStep() {
    // The combination that matters most: the container reachable from the internet must hold no
    // implementation of any side-effecting deploy step.
    runner
        .withPropertyValues("factory.deploy.enabled=false", "factory.deploy.trigger-enabled=true")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DeployActivitiesImpl.class);
              assertThat(context).hasSingleBean(DeployWorkflowService.class);
            });
  }

  /**
   * A real component scan of the deploy package, with the module's external collaborators mocked.
   * Everything inside {@code com.simonrowe.factory.deploy} is discovered exactly as it is in
   * production, so the conditions are evaluated for real.
   */
  @Configuration
  @ComponentScan("com.simonrowe.factory.deploy")
  @EnableConfigurationProperties(DeployProperties.class)
  static class ScannedDeployPackage {

    @Bean
    ProcessRunner processRunner() {
      return mock(ProcessRunner.class);
    }

    @Bean
    DeployRunRepository deployRunRepository() {
      return mock(DeployRunRepository.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    ClaudeCliRunner claudeCliRunner() {
      return mock(ClaudeCliRunner.class);
    }

    @Bean
    GitHubCredentials gitHubCredentials() {
      return mock(GitHubCredentials.class);
    }

    @Bean
    MongoTemplate mongoTemplate() {
      return mock(MongoTemplate.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    CodeReviewProperties codeReviewProperties() {
      return new CodeReviewProperties(
          new CodeReviewProperties.Github(
              "https://api.github.com", "", "secret", "", "", Duration.ofSeconds(30)),
          null,
          new CodeReviewProperties.Api(""),
          "https://temporal.test");
    }
  }
}
