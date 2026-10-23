package com.simonrowe.factory.staticanalysis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisIndexInitializer;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRepository;
import com.simonrowe.factory.staticanalysis.schedule.StaticAnalysisScheduleInitializer;
import com.simonrowe.factory.staticanalysis.workflow.StaticAnalysisActivitiesImpl;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the gate on the module's executor, the same way {@code CveFixWorkerRegistrationTest}
 * does: {@code @ActivityImpl} alone makes the Temporal starter poll the queue, so without the
 * condition the {@code deployer} - same image, often an older build - would poll
 * {@code static-analysis} too. Component-scans the real package, because a class-level
 * {@code @ConditionalOnProperty} is evaluated by the scanner and silently ignored on an explicit
 * {@code @Bean}.
 */
class StaticAnalysisWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedPackage.class);

  @Test
  void theExecutorIsAbsentByDefault() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(StaticAnalysisActivitiesImpl.class);
          assertThat(context).doesNotHaveBean(StaticAnalysisIndexInitializer.class);
          assertThat(context).doesNotHaveBean(StaticAnalysisScheduleInitializer.class);
        });
  }

  @Test
  void theExecutorIsPresentOnlyWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.staticanalysis.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(StaticAnalysisActivitiesImpl.class);
              assertThat(context).hasSingleBean(StaticAnalysisIndexInitializer.class);
              assertThat(context).hasSingleBean(StaticAnalysisScheduleInitializer.class);
            });
  }

  /** A real component scan of the module, with its external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.staticanalysis")
  @EnableConfigurationProperties({StaticAnalysisProperties.class, LinearProperties.class})
  static class ScannedPackage {

    @Bean
    StaticAnalysisRunRepository staticAnalysisRunRepository() {
      return mock(StaticAnalysisRunRepository.class);
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
