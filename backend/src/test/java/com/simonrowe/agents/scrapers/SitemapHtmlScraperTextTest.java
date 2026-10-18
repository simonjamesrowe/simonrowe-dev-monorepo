package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The string helpers that replaced backtracking regexes in {@link SitemapHtmlScraper}. Each is
 * checked against the regex it replaced, then against an input shaped to make that regex
 * quadratic.
 */
class SitemapHtmlScraperTextTest {

  private static final Duration LINEAR = Duration.ofSeconds(1);

  static List<String> urlish() {
    return List.of("", "/", "///", "/blog", "/blog/", "/blog///", "a/b/", "x?y", "x#y",
        "x?y#z", "x#y?z", "?", "#", "/a/?q=1/", "https://e.com/p/?a=b#c", "no-delims");
  }

  @ParameterizedTest
  @MethodSource("urlish")
  void stripTrailingSlashesAgreesWithTheOldRegex(final String value) {
    assertThat(SitemapHtmlScraper.stripTrailingSlashes(value))
        .isEqualTo(value.replaceAll("/+$", ""));
  }

  @ParameterizedTest
  @MethodSource("urlish")
  void stripQueryAndFragmentAgreesWithTheOldRegex(final String value) {
    assertThat(SitemapHtmlScraper.stripQueryAndFragment(value))
        .isEqualTo(value.replaceAll("[?#].*$", ""));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Post | Claude by Anthropic", "Post|Claude by Anthropic", "Post  |  claude  BY  anthropic",
      "Post | Claude", "Post |Claude", "Post | Claude | Claude by Anthropic", "Post",
      "Post | Claude extra", "| Claude", "Post | Anthropic", "A | B | Claude", "Claudeby",
      "Post \t| Claudeby Anthropic"})
  void stripTitleSuffixAgreesWithTheOldRegexes(final String title) {
    final String expected = title
        .replaceAll("(?i)\\s*\\|\\s*Claude\\s*by\\s*Anthropic$", "")
        .replaceAll("(?i)\\s*\\|\\s*Claude$", "");

    final String withoutLong =
        SitemapHtmlScraper.stripTitleSuffix(title, SitemapHtmlScraper.CLAUDE_BY_ANTHROPIC_SUFFIX);
    final String actual =
        SitemapHtmlScraper.stripTitleSuffix(withoutLong, SitemapHtmlScraper.CLAUDE_SUFFIX);

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void normalizeUrlFallbackKeepsItsRules() {
    // Not a parseable URI, so this takes the fallback path.
    assertThat(SitemapHtmlScraper.normalizeUrl("not a uri/path///?q=1#f")).isEqualTo(
        "not a uri/path");
    assertThat(SitemapHtmlScraper.normalizeUrl("https://Example.com/blog/post///?x=1"))
        .isEqualTo("https://example.com/blog/post");
  }

  @Test
  void helpersAreLinearOnHostileInput() {
    final String slashes = "/".repeat(100_000) + "x";
    final String marks = "?".repeat(100_000);
    final String spaces = "x" + " ".repeat(100_000) + "y";
    final String pipes = "|  Claude by ".repeat(10_000) + "Anthropi";

    assertTimeoutPreemptively(LINEAR, () -> {
      SitemapHtmlScraper.stripTrailingSlashes(slashes);
      SitemapHtmlScraper.stripQueryAndFragment(marks);
      SitemapHtmlScraper.normalizeUrl(slashes);
      SitemapHtmlScraper.stripTitleSuffix(spaces, SitemapHtmlScraper.CLAUDE_BY_ANTHROPIC_SUFFIX);
      SitemapHtmlScraper.stripTitleSuffix(pipes, SitemapHtmlScraper.CLAUDE_BY_ANTHROPIC_SUFFIX);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "1 May 2025", "May 1, 2025", "12 January 2024", "12 Jan 2024", "June 3 2023",
      "3 Jun 2023", "July 4, 2022", "9 Sep 2021", "9 September 2021", "Dec 25, 2020"})
  void textDatesStillMatchEachMonthForm(final String text) {
    final Matcher matcher = SitemapHtmlScraper.TEXT_DATE_PATTERN.matcher("Posted " + text + ".");

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo(text);
  }
}
