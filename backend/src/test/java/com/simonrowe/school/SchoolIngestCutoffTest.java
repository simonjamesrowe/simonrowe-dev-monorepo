package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the ingest cutoff's two declarations in step.
 *
 * <p>The date appears in {@code application.yml} and again in {@code docker-compose.prod.yml},
 * and it has to. Compose's {@code ${VAR:-}} form passes an empty <b>string</b>, and an empty
 * environment variable is resolvable — so Spring's {@code ${SCHOOL_INGEST_FROM_DATE:...}} would
 * resolve to {@code ""} rather than falling back to its default. That binds to a null
 * {@code LocalDate}, which means "no cutoff", which means silently reading the mailbox back to
 * 2020 with nothing anywhere reporting a problem.
 */
class SchoolIngestCutoffTest {

  private static final Pattern YAML =
      Pattern.compile("ingest-from-date:\\s*\\$\\{SCHOOL_INGEST_FROM_DATE:([^}]*)}");
  private static final Pattern COMPOSE =
      Pattern.compile("SCHOOL_INGEST_FROM_DATE:\\s*\\$\\{SCHOOL_INGEST_FROM_DATE:-([^}]*)}");

  @Test
  @DisplayName("application.yml and docker-compose.prod.yml declare the same cutoff")
  void cutoffsMatch() throws IOException {
    final String yaml = find(YAML, Path.of("src/main/resources/application.yml"));
    final String compose = find(COMPOSE, Path.of("..", "docker-compose.prod.yml"));

    assertThat(compose)
        .as("compose must repeat the yml default; an empty value there means no cutoff at all")
        .isEqualTo(yaml)
        .isNotBlank();
  }

  @Test
  @DisplayName("the cutoff is a real date, not left blank")
  void cutoffIsIsoFormatted() throws IOException {
    assertThat(find(YAML, Path.of("src/main/resources/application.yml")))
        .matches("\\d{4}-\\d{2}-\\d{2}");
  }

  private String find(final Pattern pattern, final Path file) throws IOException {
    final List<String> lines = Files.readAllLines(file);
    for (String line : lines) {
      final Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return matcher.group(1).trim();
      }
    }
    throw new AssertionError("No cutoff declaration found in " + file);
  }
}
