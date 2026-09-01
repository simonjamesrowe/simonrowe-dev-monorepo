package com.simonrowe.factory.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.persistence.LearningIndexInitializer;
import com.simonrowe.factory.feedback.persistence.LearningRepository;
import com.simonrowe.factory.feedback.workflow.FeedbackActivitiesImpl;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import com.simonrowe.factory.linear.config.LinearProperties;
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
 * Pins the credential gate on the review-feedback executor.
 *
 * <p><strong>Do not delete these tests.</strong> {@link FeedbackActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is what keeps the {@code review-feedback} poller on the container
 * configured to run it. Both {@code software-factory} and {@code deployer} run the same image, and
 * {@code @ActivityImpl} alone is enough for Temporal's Spring Boot starter to create a worker for
 * the queue, so without the condition both containers poll it and whichever wins runs the task.
 *
 * <p>The deployer holds no {@code FACTORY_FEEDBACK_ENABLED} and no {@code LINEAR_API_KEY} by
 * design. The feedback workflow files its Linear issue <em>before</em> distilling and fails
 * non-retryably when it cannot, so a task landing there could only ever fail — the same shape as
 * the vulnerability-scan outage that {@code CveFixWorkerRegistrationTest} documents.
 *
 * <p>As in that module, {@link LearningIndexInitializer} already carried the condition; the
 * activity bean, the one that needs credentials, was the one missing it.
 *
 * <p><strong>Why this component-scans rather than declaring the beans directly.</strong> A
 * class-level {@code @ConditionalOnProperty} is evaluated by the component scanner, not the bean
 * factory, so declaring the class through an explicit {@code @Bean} method registers it
 * unconditionally and the annotation is silently ignored.
 */
class FeedbackWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedFeedbackPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    // The deployer's shape: FACTORY_FEEDBACK_ENABLED is absent from its compose block, so
    // factory.feedback.enabled falls back to application.yml's `false`.
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(FeedbackActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(LearningIndexInitializer.class);
        });
  }

  @Test
  void theExecutorIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.feedback.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(FeedbackActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentOnlyWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.feedback.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(FeedbackActivitiesImpl.class);
              assertThat(context).hasSingleBean(LearningIndexInitializer.class);
            });
  }

  /** A real component scan of the feedback package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.feedback")
  // LinearProperties too: FeedbackController reads factory.linear.enabled, because the feedback
  // workflow files its Linear issue before distilling and must refuse when the sink is off.
  @EnableConfigurationProperties({FeedbackProperties.class, LinearProperties.class})
  static class ScannedFeedbackPackage {

    @Bean
    LearningRepository learningRepository() {
      return mock(LearningRepository.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    MongoTemplate mongoTemplate() {
      return mock(MongoTemplate.class);
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
    RepositoryWorkspaceFactory repositoryWorkspaceFactory() {
      return mock(RepositoryWorkspaceFactory.class);
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

    @Bean
    FactoryTokenAuthenticator factoryTokenAuthenticator(
        final CodeReviewProperties codeReviewProperties) {
      return new FactoryTokenAuthenticator(codeReviewProperties);
    }
  }
}
