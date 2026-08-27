package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIndexInitializer;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import com.simonrowe.factory.linear.service.FilingDecider;
import com.simonrowe.factory.linear.service.IssueFiler;
import com.simonrowe.factory.linear.workflow.LinearActivitiesImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the credential gate.
 *
 * <p><strong>Do not delete these tests.</strong> {@link LinearActivitiesImpl}'s
 * {@code @ConditionalOnProperty} is the only thing that keeps a tracker credential out of the
 * {@code deployer}, the container holding the Docker socket. Both containers run the same image.
 *
 * <p>This component-scans rather than declaring the beans directly, for the reason {@code
 * DeployWorkerRegistrationTest} documents at length: a class-level {@code @ConditionalOnProperty}
 * is evaluated by the component scanner, not the bean factory, so a harness that declares the
 * class through an explicit {@code @Bean} method tests nothing about production.
 */
class LinearWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedLinearPackage.class);

  @Test
  void theSinkIsAbsentByDefault() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class);
          // And nothing touches Mongo, for the reason CveFixIndexInitializer documents.
          assertThat(context).doesNotHaveBean(LinearIndexInitializer.class);
        });
  }

  @Test
  void theSinkIsAbsentWhenTheFlagIsExplicitlyFalse() {
    runner
        .withPropertyValues("factory.linear.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class));
  }

  @Test
  void theSinkIsPresentOnlyWhenTheFlagIsTrue() {
    runner
        .withPropertyValues("factory.linear.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(LinearActivitiesImpl.class);
              assertThat(context).hasSingleBean(LinearIndexInitializer.class);
            });
  }

  @Test
  void theDeployerShapeHoldsNoFilingImplementation() {
    // The combination that matters: the container with the Docker socket must hold no
    // implementation that reads LINEAR_API_KEY.
    runner
        .withPropertyValues("factory.deploy.enabled=true", "factory.linear.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class));
  }

  @Test
  void theUngatedCollaboratorsAreHarmlessWithTheFlagOff() {
    // LinearGateway, FilingDecider and IssueFiler are plain @Component beans with no
    // @ConditionalOnProperty of their own, so they are always instantiated regardless of the
    // flag. That is intended to be harmless because none of them performs I/O at construction
    // time (LinearGateway only builds an HttpClient and resolves its team lazily on first use).
    // This test exists so a future constructor-time network call on any of the three is caught
    // here rather than surfacing as a context-startup failure on the deployer.
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(LinearGateway.class);
          assertThat(context).hasSingleBean(FilingDecider.class);
          assertThat(context).hasSingleBean(IssueFiler.class);
        });
  }

  /** A real component scan of the linear package, with external collaborators mocked. */
  @Configuration
  @ComponentScan("com.simonrowe.factory.linear")
  @EnableConfigurationProperties(LinearProperties.class)
  static class ScannedLinearPackage {

    @Bean
    LinearIssueRepository linearIssueRepository() {
      return mock(LinearIssueRepository.class);
    }

    @Bean
    MongoTemplate mongoTemplate() {
      return mock(MongoTemplate.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
