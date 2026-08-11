package com.simonrowe.factory.cvefix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CveFixPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsDefaultsWithTheFeatureDisabled() {
    runner.run(
        context -> {
          CveFixProperties properties = context.getBean(CveFixProperties.class);
          assertThat(properties.enabled()).isFalse();
          assertThat(properties.branch()).isEqualTo("chore/dependency-cve-fixes");
          assertThat(properties.baseBranch()).isEqualTo("main");
          assertThat(properties.ci().repairBudget()).isEqualTo(3);
          assertThat(properties.ci().pollInterval()).isEqualTo(Duration.ofMinutes(3));
          // 3h, not 45m: one repair iteration costs up to agent.timeout (15m) plus a full
          // CI cycle, so a 45m cap would truncate the documented budget of 3 repairs.
          assertThat(properties.ci().maxWait()).isEqualTo(Duration.ofHours(3));
          // The promptfoo evals job is continue-on-error advisory; it must never be able to
          // decide the build is RED.
          assertThat(properties.ci().advisoryChecks()).containsExactly("evaluate");
          assertThat(properties.dependencyTrack().projects())
              .containsExactly("simonrowe-dev/backend", "simonrowe-dev/frontend");
        });
  }

  @Test
  void overridesBindFromProperties() {
    runner
        .withPropertyValues(
            "factory.cvefix.enabled=true",
            "factory.cvefix.ci.repair-budget=5",
            "factory.cvefix.ci.poll-interval=90s",
            "factory.cvefix.dependency-track.base-url=http://dt:8080",
            "factory.cvefix.dependency-track.api-key=secret")
        .run(
            context -> {
              CveFixProperties properties = context.getBean(CveFixProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.ci().repairBudget()).isEqualTo(5);
              assertThat(properties.ci().pollInterval()).isEqualTo(Duration.ofSeconds(90));
              assertThat(properties.dependencyTrack().baseUrl()).isEqualTo("http://dt:8080");
            });
  }

  @Test
  void projectsListIsUnmodifiable() {
    CveFixProperties.DependencyTrack dependencyTrack =
        new CveFixProperties.DependencyTrack(
            "http://dt:8080", "k", List.of("a"), Duration.ofSeconds(30));
    assertThat(dependencyTrack.projects()).isUnmodifiable();
  }

  @EnableConfigurationProperties(CveFixProperties.class)
  static class TestConfig {
  }
}
