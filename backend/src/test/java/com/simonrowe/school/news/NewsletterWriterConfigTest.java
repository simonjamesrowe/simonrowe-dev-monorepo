package com.simonrowe.school.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the newsletter summary writer's two defaults, which differ on purpose.
 *
 * <p>{@code application.yml} defaults to OpenAI, so no test and no local run ever submits to
 * Simon's subscription. Production compose sets Claude Code. Flipping either one silently
 * changes who writes the summaries, so both are stated here.
 */
class NewsletterWriterConfigTest {

  private static final Pattern YAML = Pattern.compile(
      "writer:\\s*\\$\\{SCHOOL_NEWSLETTER_SUMMARY_WRITER:([^}]*)}");
  private static final Pattern COMPOSE = Pattern.compile(
      "SCHOOL_NEWSLETTER_SUMMARY_WRITER:\\s*\\$\\{SCHOOL_NEWSLETTER_SUMMARY_WRITER:-([^}]*)}");

  @Test
  @DisplayName("Spring defaults to OpenAI and production compose to Claude Code")
  void defaults() throws IOException {
    assertThat(find(YAML, Path.of("src/main/resources/application.yml")))
        .isEqualTo(SchoolNewsletterSummariser.WRITER_OPENAI);
    assertThat(find(COMPOSE, Path.of("..", "docker-compose.prod.yml")))
        .isEqualTo(SchoolNewsletterSummariser.WRITER_CLAUDE_CODE);
  }

  private static String find(final Pattern pattern, final Path file) throws IOException {
    final Matcher matcher = pattern.matcher(Files.readString(file));
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    throw new AssertionError("No newsletter summary writer declaration found in " + file);
  }
}
