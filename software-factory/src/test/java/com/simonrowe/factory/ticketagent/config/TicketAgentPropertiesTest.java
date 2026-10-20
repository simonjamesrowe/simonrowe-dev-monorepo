package com.simonrowe.factory.ticketagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.git.PathGlobs;
import java.util.List;
import org.junit.jupiter.api.Test;

class TicketAgentPropertiesTest {

  private static final List<String> DENIED = TicketAgentProperties.DEFAULT_DENIED_PATHS;

  /** What the agent may never push, near-misses included. */
  @Test
  void theDenylistCatchesInfrastructureAndSecrets() {
    List<String> denied =
        List.of(
            ".env",
            "backend/.env",
            ".env.production",
            "docker-compose.prod.yml",
            "config/nginx/nginx-proxy.conf",
            "scripts/restart-prod.sh",
            ".github/workflows/ci.yml",
            ".github/rulesets/main.json",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "Dockerfile.software-factory",
            "frontend/Dockerfile");
    assertThat(PathGlobs.matching(denied, DENIED)).containsExactlyElementsOf(denied);
  }

  @Test
  void ordinaryCodeAndConfigurationArePushable() {
    List<String> allowed =
        List.of(
            "backend/src/main/resources/config.yml",
            "backend/src/main/resources/application.yml",
            "backend/src/main/java/com/simonrowe/A.java",
            "software-factory/src/main/resources/application.yml",
            "frontend/src/App.tsx",
            "docs/runbooks/logwatch.md",
            "CLAUDE.md");
    assertThat(PathGlobs.matching(allowed, DENIED)).isEmpty();
  }

  @Test
  void buildAndDependencyFilesArePushableButRequireHumanMerge() {
    List<String> dependencies = List.of(
        "gradle/libs.versions.toml", "build.gradle.kts", "backend/build.gradle.kts",
        "software-factory/build.gradle.kts", "settings.gradle.kts", "frontend/package.json",
        "frontend/package-lock.json");

    assertThat(PathGlobs.matching(dependencies, DENIED)).isEmpty();
    assertThat(PathGlobs.matching(dependencies, TicketAgentProperties.MANUAL_MERGE_PATHS))
        .containsExactlyElementsOf(dependencies);
    assertThat(PathGlobs.matching(
        List.of("backend/src/A.java", "frontend/src/App.tsx", "docs/build.gradle.kts.md"),
        TicketAgentProperties.MANUAL_MERGE_PATHS)).isEmpty();
  }

  @Test
  void defaultsAreTheDocumentedOnes() {
    TicketAgentProperties defaults =
        new TicketAgentProperties(
            false, null, null, null, null, null, 0, 0, null, null, null, null, null, null, null,
            null, null, null, 0, null, null);

    assertThat(defaults.label()).isEqualTo("factory:agent");
    assertThat(defaults.stuckLabel()).isEqualTo("agent:stuck");
    assertThat(defaults.branchPrefix()).isEqualTo("agent/");
    assertThat(defaults.maxAttempts()).isEqualTo(3);
    assertThat(defaults.maxInFlight()).isEqualTo(1);
    assertThat(defaults.slug()).isEqualTo("simonjamesrowe/simonrowe-dev-monorepo");
    assertThat(defaults.agent().model()).isEqualTo("sonnet");
  }
}
