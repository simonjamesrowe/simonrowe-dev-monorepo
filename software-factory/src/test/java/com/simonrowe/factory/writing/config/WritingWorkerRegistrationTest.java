package com.simonrowe.factory.writing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.writing.api.ContentWritingController;
import com.simonrowe.factory.writing.workflow.ContentWritingActivitiesImpl;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the gate on the content-writing executor.
 *
 * <p>{@link ContentWritingActivitiesImpl}'s {@code @ConditionalOnProperty} is what keeps the
 * {@code content-writing} activity poller off the {@code deployer}, which runs the same image.
 * Component-scanned for real, because a class-level condition is evaluated by the scanner and an
 * explicit {@code @Bean} declaration would silently ignore it.
 */
class WritingWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedWritingPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    // The deployer's shape: no FACTORY_WRITING_ENABLED, so application.yml's `false` applies.
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(ContentWritingActivitiesImpl.class);
          // The API stays up so a caller gets an honest 503 rather than a 404.
          assertThat(context).hasSingleBean(ContentWritingController.class);
        });
  }

  @Test
  void theExecutorIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.writing.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ContentWritingActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.writing.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ContentWritingActivitiesImpl.class));
  }

  /** A real component scan of the writing package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.writing")
  @EnableConfigurationProperties(WritingProperties.class)
  static class ScannedWritingPackage {

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    ClaudeCliRunner claudeCliRunner() {
      return mock(ClaudeCliRunner.class);
    }

    @Bean
    FactoryTokenAuthenticator factoryTokenAuthenticator() {
      return new FactoryTokenAuthenticator(
          new CodeReviewProperties(null, null, new CodeReviewProperties.Api("", null), null));
    }
  }
}
