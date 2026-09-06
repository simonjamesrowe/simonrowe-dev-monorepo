package com.simonrowe.factory.logwatch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.logwatch.persistence.LogWatchIndexInitializer;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRepository;
import com.simonrowe.factory.logwatch.schedule.LogWatchScheduleInitializer;
import com.simonrowe.factory.logwatch.workflow.LogWatchActivitiesImpl;
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
 * Pins the credential gate on the log-watch executor.
 *
 * <p><strong>Do not delete these tests.</strong> {@link LogWatchActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is what keeps the {@code logwatch} poller — and with it the
 * Grafana Cloud read credential — on {@code software-factory}. Both that container and
 * {@code deployer} run the same image, and {@code @ActivityImpl} alone is enough for Temporal's
 * Spring Boot starter to create a worker for the queue, so without the condition both containers
 * poll it and whichever wins runs the task. {@code deployer} holds {@code /var/run/docker.sock},
 * which is root-equivalent on the host, and must hold as few other credentials as possible.
 *
 * <p><strong>Why this component-scans rather than declaring the beans directly.</strong> A
 * class-level {@code @ConditionalOnProperty} is evaluated by the component scanner, not the bean
 * factory, so declaring the class through an explicit {@code @Bean} method registers it
 * unconditionally and the annotation is silently ignored.
 *
 * <p>What this test does <em>not</em> assert, deliberately: that only one container registers a
 * <em>workflow</em> poller on the queue. Classpath scanning for {@code @WorkflowImpl} is
 * unconditional, so both containers will — and that is harmless, because a workflow
 * implementation only schedules activities. It is the same shape as the {@code deploy} queue and
 * should not be "fixed".
 */
class LogWatchWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedLogWatchPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    // The deployer's shape: FACTORY_LOGWATCH_ENABLED is absent from its compose block, so
    // factory.logwatch.enabled falls back to application.yml's `false`.
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(LogWatchActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(LogWatchIndexInitializer.class);
          assertThat(context).doesNotHaveBean(LogWatchScheduleInitializer.class);
        });
  }

  @Test
  void theExecutorIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.logwatch.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LogWatchActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.logwatch.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(LogWatchActivitiesImpl.class);
              assertThat(context).hasSingleBean(LogWatchIndexInitializer.class);
              assertThat(context).hasSingleBean(LogWatchScheduleInitializer.class);
            });
  }

  /** A real component scan of the log-watch package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.logwatch")
  // LinearProperties too: the workflow service copies factory.linear.enabled onto the request,
  // because a @WorkflowImpl cannot inject Spring properties.
  @EnableConfigurationProperties({LogWatchProperties.class, LinearProperties.class})
  static class ScannedLogWatchPackage {

    @Bean
    LogWatchRunRepository logWatchRunRepository() {
      return mock(LogWatchRunRepository.class);
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
