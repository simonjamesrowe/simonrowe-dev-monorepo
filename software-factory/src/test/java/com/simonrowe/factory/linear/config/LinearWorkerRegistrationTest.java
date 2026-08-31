package com.simonrowe.factory.linear.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIndexInitializer;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import com.simonrowe.factory.linear.service.FilingDecider;
import com.simonrowe.factory.linear.service.IssueFiler;
import com.simonrowe.factory.linear.workflow.LinearActivitiesImpl;
import io.temporal.spring.boot.ActivityImpl;
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
    // implementation that reads LINEAR_API_KEY. Note factory.deploy.enabled is declarative here:
    // this harness only scans com.simonrowe.factory.linear, so setting it is not itself proof of
    // anything about the deploy package — it is recorded to document intent, not to exercise a
    // cross-module interaction this scan cannot see. That makes this test behaviourally identical
    // to theSinkIsAbsentWhenTheFlagIsExplicitlyFalse; it is kept anyway as intent-documentation.
    runner
        .withPropertyValues("factory.deploy.enabled=true", "factory.linear.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(LinearActivitiesImpl.class));
  }

  @Test
  void theActivityImplementationDeclaresTheLinearTaskQueue() {
    // Nothing else pins this: removing or retargeting @ActivityImpl would leave the queue
    // unpolled with no other test noticing, and research item 7's finding that an
    // activity-only queue gets a worker was a one-off manual source read, not something any
    // test observes.
    ActivityImpl annotation = LinearActivitiesImpl.class.getAnnotation(ActivityImpl.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.taskQueues()).containsExactly(LinearTaskQueues.LINEAR);
  }

  @Test
  void noCollaboratorPerformsIoAtConstructionTime() {
    // Bean presence alone does not prove this: it only catches a throwing constructor. A
    // constructor that performs I/O and returns cleanly — a Mongo call against this test's mock,
    // which hands back Mockito defaults like 0L/null, or an HTTP call that happens to succeed —
    // would leave a presence-only assertion green while still doing real work at context-startup
    // time. So this asserts two things instead: that neither Mongo collaborator saw any
    // interaction, and that construction cannot have made a live HTTP call, by pointing
    // factory.linear.api-base-url at a closed local port so any attempt fails fast,
    // deterministically, and offline — rather than reaching the real api.linear.app (the
    // LinearProperties default) or hanging for up to the 30s connect timeout on a networked
    // runner.
    runner
        .withPropertyValues("factory.linear.api-base-url=http://127.0.0.1:1/graphql")
        .run(
            context -> {
              assertThat(context).hasSingleBean(LinearGateway.class);
              assertThat(context).hasSingleBean(FilingDecider.class);
              assertThat(context).hasSingleBean(IssueFiler.class);
              MongoTemplate mongoTemplate = context.getBean(MongoTemplate.class);
              // Spring's own ApplicationContextAwareProcessor calls setApplicationContext on
              // every ApplicationContextAware bean, including this mock, purely as container
              // wiring — that one call is not evidence of anything the linear module did, so it
              // is acknowledged rather than counted against the "no I/O at construction" claim.
              verify(mongoTemplate).setApplicationContext(any());
              verifyNoMoreInteractions(mongoTemplate);
              verifyNoInteractions(context.getBean(LinearIssueRepository.class));
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
