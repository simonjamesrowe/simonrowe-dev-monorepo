package com.simonrowe.factory.deploy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeployPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bothFlagsDefaultOffSoMergingChangesNothing() {
    runner.run(
        context -> {
          DeployProperties properties = context.getBean(DeployProperties.class);
          // The whole rollout story rests on these two: merging this feature must not deploy
          // anything until an operator opts in, twice, in order.
          assertThat(properties.enabled()).isFalse();
          assertThat(properties.triggerEnabled()).isFalse();
        });
  }

  @Test
  void bindsDefaults() {
    runner.run(
        context -> {
          DeployProperties properties = context.getBean(DeployProperties.class);
          assertThat(properties.owner()).isEqualTo("simonjamesrowe");
          assertThat(properties.repository()).isEqualTo("simonrowe-dev-monorepo");
          assertThat(properties.slug()).isEqualTo("simonjamesrowe/simonrowe-dev-monorepo");
          assertThat(properties.workflowName()).isEqualTo("Publish");
          assertThat(properties.branch()).isEqualTo("main");
          assertThat(properties.repoDir()).isEqualTo("/workspace/repo");
          assertThat(properties.stateDir()).isEqualTo("/var/run/deploy-state");
          // Default ON, which is why these two are boxed in the record: a primitive boolean
          // would default false and silently lose rollback and config sync.
          assertThat(properties.rollbackEnabled()).isTrue();
          assertThat(properties.syncConfig()).isTrue();
          assertThat(properties.phaseTimeout()).isEqualTo(Duration.ofMinutes(30));
          assertThat(properties.agent().maxTurns()).isEqualTo(12);
        });
  }

  @Test
  void repoUrlIsPinnedRatherThanReadFromTheCheckout() {
    runner.run(
        context -> {
          // Pinned in configuration on purpose: sync-config validates the target commit against
          // this URL, so a tampered remote on the host must not be able to redirect the fetch.
          assertThat(context.getBean(DeployProperties.class).repoUrl())
              .isEqualTo("https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git");
        });
  }

  @Test
  void servicesDefaultToTheThreeImagesCiPublishes() {
    runner.run(
        context ->
            assertThat(context.getBean(DeployProperties.class).services())
                .containsExactly("backend", "frontend", "software-factory"));
  }

  @Test
  void recreateAllowlistHoldsTheEightApprovedServices() {
    runner.run(
        context -> {
          DeployProperties properties = context.getBean(DeployProperties.class);
          assertThat(properties.recreatable())
              .containsExactly(
                  "backend",
                  "frontend",
                  "software-factory",
                  "nginx",
                  "alloy",
                  "searxng",
                  "temporal-ui",
                  "dependencytrack-frontend");
        });
  }

  @Test
  void theDeployerExcludesItselfAndTheDataServicesAreNeverRecreatable() {
    runner.run(
        context -> {
          DeployProperties properties = context.getBean(DeployProperties.class);
          // Recreating the container that is mid-orchestration is how the backend's old
          // redeploy path went wrong.
          assertThat(properties.mayRecreate("deployer")).isFalse();
          // The data, and the three services with known recreation hazards.
          assertThat(properties.mayRecreate("mongodb")).isFalse();
          assertThat(properties.mayRecreate("elasticsearch")).isFalse();
          assertThat(properties.mayRecreate("kafka")).isFalse();
          assertThat(properties.mayRecreate("pinggy")).isFalse();
          assertThat(properties.mayRecreate("langfuse-clickhouse")).isFalse();
          assertThat(properties.mayRecreate("dependencytrack-apiserver")).isFalse();
          // And a service nobody has classified yet is held for a human by default, which is
          // the whole reason this is an allowlist and not a denylist.
          assertThat(properties.mayRecreate("some-service-added-next-year")).isFalse();
          assertThat(properties.mayRecreate("backend")).isTrue();
        });
  }

  @Test
  void overridesBindFromProperties() {
    runner
        .withPropertyValues(
            "factory.deploy.enabled=true",
            "factory.deploy.trigger-enabled=true",
            "factory.deploy.sync-config=false",
            "factory.deploy.rollback-enabled=false",
            "factory.deploy.services=backend,frontend",
            "factory.deploy.recreatable=backend,nginx",
            "factory.deploy.phase-timeout=45m",
            "factory.deploy.state-dir=/tmp/ds")
        .run(
            context -> {
              DeployProperties properties = context.getBean(DeployProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.triggerEnabled()).isTrue();
              // Explicitly false must be honoured, not overwritten by the default-on rule.
              assertThat(properties.syncConfig()).isFalse();
              assertThat(properties.rollbackEnabled()).isFalse();
              assertThat(properties.services()).containsExactly("backend", "frontend");
              // Widening the allowlist is a config change, not a code change.
              assertThat(properties.mayRecreate("nginx")).isTrue();
              assertThat(properties.mayRecreate("software-factory")).isFalse();
              assertThat(properties.phaseTimeout()).isEqualTo(Duration.ofMinutes(45));
              assertThat(properties.stateDir()).isEqualTo("/tmp/ds");
            });
  }

  @Test
  void listsAreUnmodifiable() {
    runner.run(
        context -> {
          DeployProperties properties = context.getBean(DeployProperties.class);
          assertThat(properties.services()).isUnmodifiable();
          assertThat(properties.recreatable()).isUnmodifiable();
        });
  }

  @EnableConfigurationProperties(DeployProperties.class)
  static class TestConfig {
  }
}
