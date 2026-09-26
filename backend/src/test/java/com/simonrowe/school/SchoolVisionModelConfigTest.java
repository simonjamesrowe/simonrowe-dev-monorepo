package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps the switchable vision model's Spring and production-compose defaults in step. */
class SchoolVisionModelConfigTest {

  private static final Pattern YAML =
      Pattern.compile("vision-model:\\s*\\$\\{SCHOOL_VISION_MODEL:([^}]*)}");
  private static final Pattern COMPOSE =
      Pattern.compile("SCHOOL_VISION_MODEL:\\s*\\$\\{SCHOOL_VISION_MODEL:-([^}]*)}");

  @Test
  @DisplayName("application.yml and production compose use the same real vision model default")
  void defaultsMatch() throws IOException {
    final String yaml = find(YAML, Path.of("src/main/resources/application.yml"));
    final String compose = find(COMPOSE, Path.of("..", "docker-compose.prod.yml"));

    assertThat(compose)
        .as("compose must not pass a blank value that defeats the Spring-side default")
        .isEqualTo(yaml)
        .isNotBlank();
  }

  private String find(final Pattern pattern, final Path file) throws IOException {
    final Matcher matcher = pattern.matcher(Files.readString(file));
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    throw new AssertionError("No vision-model declaration found in " + file);
  }
}
