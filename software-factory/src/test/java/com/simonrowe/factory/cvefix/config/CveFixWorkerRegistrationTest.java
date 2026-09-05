package com.simonrowe.factory.cvefix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.cvefix.persistence.CveFixIndexInitializer;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import com.simonrowe.factory.cvefix.schedule.CveFixScheduleInitializer;
import com.simonrowe.factory.cvefix.workflow.CveFixActivitiesImpl;
import com.simonrowe.factory.linear.config.LinearProperties;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the credential gate on the vulnerability scan's executor.
 *
 * <p><strong>Do not delete these tests.</strong> {@link CveFixActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is the only thing that keeps the {@code deployer} — the container
 * holding the Docker socket — from polling the {@code cve-fix} task queue. Both containers run the
 * same image, and {@code @ActivityImpl} alone is enough for Temporal's Spring Boot starter to
 * create a worker for a queue: {@code WorkersTemplate.configureActivityBeansByTaskQueue} walks
 * every activity bean's task queues and calls {@code tryGetWorker}.
 *
 * <p><strong>This is not hypothetical.</strong> Without the condition, both containers polled
 * {@code cve-fix} and the deployer won the task. It holds no {@code DEPENDENCYTRACK_API_KEY} by
 * design, so {@code fetchFindings} called Dependency-Track with an empty credential, got 401, and
 * the run failed with {@code RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED} against an activity that is
 * perfectly configured on the container that was supposed to run it. Nothing in the failure names
 * authentication or the deployer — the only clue is the worker identity in the Temporal event
 * history, which is a container id.
 *
 * <p>Note the sibling components in this package, {@link CveFixIndexInitializer} and {@link
 * CveFixScheduleInitializer}, already carried this condition. The activity bean — the only one of
 * the three that needs a credential — was the one missing it.
 *
 * <p><strong>Why this component-scans rather than declaring the beans directly.</strong> A
 * class-level {@code @ConditionalOnProperty} is evaluated by the component scanner, not by the
 * bean factory: declaring the same class through an explicit {@code @Bean} method registers it
 * <em>unconditionally</em> and the annotation is silently ignored. So this scans the real package
 * with only the module's external collaborators mocked.
 */
class CveFixWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedCveFixPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    // The deployer's shape: FACTORY_CVEFIX_ENABLED is deliberately absent from its compose block,
    // so factory.cvefix.enabled falls back to application.yml's `false`.
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(CveFixActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(CveFixIndexInitializer.class);
          assertThat(context).doesNotHaveBean(CveFixScheduleInitializer.class);
        });
  }

  @Test
  void theExecutorIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.cvefix.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(CveFixActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentOnlyWhenTheFlagIsTrue() {
    // The software-factory container's shape, which is where the Dependency-Track credential is.
    runner
        .withPropertyValues("factory.cvefix.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(CveFixActivitiesImpl.class);
              assertThat(context).hasSingleBean(CveFixIndexInitializer.class);
              assertThat(context).hasSingleBean(CveFixScheduleInitializer.class);
            });
  }

  /**
   * A real component scan of the cvefix package, with the module's external collaborators mocked.
   */
  @Configuration
  @ComponentScan("com.simonrowe.factory.cvefix")
  // LinearProperties too: the scan reads factory.linear.enabled to decide whether findings can be
  // filed, because a @WorkflowImpl cannot inject configuration of its own.
  @EnableConfigurationProperties({CveFixProperties.class, LinearProperties.class})
  static class ScannedCveFixPackage {

    @Bean
    CveFixRunRepository cveFixRunRepository() {
      return mock(CveFixRunRepository.class);
    }

    @Bean
    WorkflowClient workflowClient() {
      return mock(WorkflowClient.class);
    }

    @Bean
    ScheduleClient scheduleClient() {
      return mock(ScheduleClient.class);
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
          new CodeReviewProperties.Api("", null),
          "https://temporal.test");
    }

    @Bean
    FactoryTokenAuthenticator factoryTokenAuthenticator(
        final CodeReviewProperties codeReviewProperties) {
      return new FactoryTokenAuthenticator(codeReviewProperties);
    }
  }
}
