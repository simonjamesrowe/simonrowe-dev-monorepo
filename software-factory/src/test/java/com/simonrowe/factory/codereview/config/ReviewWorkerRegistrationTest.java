package com.simonrowe.factory.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.codereview.agent.ReviewEngine;
import com.simonrowe.factory.codereview.github.CheckRunGateway;
import com.simonrowe.factory.codereview.github.GitHubGateway;
import com.simonrowe.factory.codereview.workflow.ReviewActivitiesImpl;
import io.temporal.spring.boot.ActivityImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the gate that keeps {@code deployer} out of the {@code code-review} activity queue.
 *
 * <p><strong>Do not delete these tests.</strong> Before {@link ReviewActivitiesImpl} carried a
 * {@code @ConditionalOnProperty} it was the only activity implementation in the module without
 * one, so both containers registered a code-review <em>activity</em> poller and Temporal split
 * the work between them. The {@code deployer} holds no GitHub App credential, so its share died
 * at {@code GitHub App token request failed} wrapping a bare {@code UnresolvedAddressException}.
 *
 * <p>Removing the condition again fails in the least diagnosable way available: intermittently
 * (routing is per activity, so one run can clear {@code REVIEWING} and the next fail in {@code
 * PUBLISHING}), in the other container's log, and looking exactly like flaky DNS.
 *
 * <p>This component-scans rather than declaring the beans directly, for the reason {@code
 * DeployWorkerRegistrationTest} documents at length: a class-level {@code @ConditionalOnProperty}
 * is evaluated by the component scanner, not the bean factory, so a harness that declares the
 * class through an explicit {@code @Bean} method registers it unconditionally and tests nothing
 * about production.
 */
class ReviewWorkerRegistrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ScannedReviewWorkflowPackage.class);

  @Test
  void theExecutorIsPresentByDefault() {
    // The opposite default to factory.deploy.enabled, deliberately. That flag guards the Docker
    // socket, so opt-in is the safe default. This one guards no credential, and the repository's
    // merge gate requires the `Code Review` check — so defaulting off would turn one missing
    // variable into "no pull request can merge", with nothing logged anywhere.
    runner.run(context -> assertThat(context).hasSingleBean(ReviewActivitiesImpl.class));
  }

  @Test
  void theExecutorIsPresentWhenTheFlagIsExplicitlyTrue() {
    runner
        .withPropertyValues("factory.codereview.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ReviewActivitiesImpl.class));
  }

  @Test
  void theDeployerShapeHoldsNoReviewImplementation() {
    // The combination that matters: the container that holds the Docker socket and no GitHub App
    // credential must hold no implementation of any review step, so Temporal can never route a
    // review activity to it.
    runner
        .withPropertyValues("factory.codereview.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ReviewActivitiesImpl.class));
  }

  @Test
  void anyValueOtherThanTrueLeavesTheExecutorAbsent() {
    // havingValue = "true" means a typo'd or unexpected value disables rather than enables. Worth
    // pinning: on `deployer` that is the safe direction, and it is the direction a reader of
    // matchIfMissing = true is least likely to assume.
    runner
        .withPropertyValues("factory.codereview.enabled=yes")
        .run(context -> assertThat(context).doesNotHaveBean(ReviewActivitiesImpl.class));
  }

  @Test
  void theActivityImplementationDeclaresTheCodeReviewTaskQueue() {
    // Nothing else pins this: retargeting @ActivityImpl would leave the reviews queue unpolled
    // and every review would sit accepted in Temporal until an activity timeout.
    ActivityImpl annotation = ReviewActivitiesImpl.class.getAnnotation(ActivityImpl.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.taskQueues()).containsExactly(CodeReviewTaskQueues.REVIEWS);
  }

  /**
   * A real component scan of the code-review workflow package, with the activity's external
   * collaborators mocked, so the condition is evaluated exactly as it is in production.
   */
  @Configuration
  @ComponentScan("com.simonrowe.factory.codereview.workflow")
  static class ScannedReviewWorkflowPackage {

    @Bean
    GitHubGateway gitHubGateway() {
      return mock(GitHubGateway.class);
    }

    @Bean
    CheckRunGateway checkRunGateway() {
      return mock(CheckRunGateway.class);
    }

    @Bean
    ReviewEngine reviewEngine() {
      return mock(ReviewEngine.class);
    }
  }
}
