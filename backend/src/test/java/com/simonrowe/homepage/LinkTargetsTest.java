package com.simonrowe.homepage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkTargetsTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "/", "/about", "/about#roles", "/blogs?tag=kafka", "/portfolio/term-time",
      "https://term-time.simonrowe.dev", "https://example.com/a/b?c=d#e", "HTTPS://example.com"
  })
  void allowsSitePathsAndHttpsUrls(final String href) {
    assertThat(LinkTargets.isAllowed(href)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "//evil.example", "//evil.example/path", "/\\evil.example", "\\\\evil.example",
      "http://example.com", "javascript:alert(1)", "data:text/html,hi", "mailto:a@b.c",
      "about", "https://", "https:///path", "https://user:pw@example.com",
      "/about us", "/about\tus", "/about\nus", " /about", "ftp://example.com"
  })
  void refusesAnythingElse(final String href) {
    assertThat(LinkTargets.isAllowed(href)).isFalse();
  }

  @Test
  void refusesNull() {
    assertThat(LinkTargets.isAllowed(null)).isFalse();
  }

  @Test
  void treatsSiteOriginSuffixAsAnyOtherHttpsHost() {
    // Allowed as an https URL like any other, and never mistaken for a site path: the check
    // parses the value, it does not compare prefixes against the site's origin.
    assertThat(LinkTargets.isAllowed("https://simonrowe.dev.evil.example/phish")).isTrue();
    assertThat(LinkTargets.isAllowed("/https://evil.example")).isTrue();
  }

  @Test
  void boundsWorkOnHugeInputWithoutBacktracking() {
    String huge = "/" + "a/".repeat(60_000);
    long start = System.nanoTime();
    assertThat(LinkTargets.isAllowed(huge)).isFalse();
    assertThat(LinkTargets.isAllowed("https://" + "a".repeat(100_000) + ".example")).isFalse();
    assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
  }

  @Test
  void acceptsLinksUpToTheLengthLimit() {
    String atLimit = "/" + "a".repeat(LinkTargets.MAX_LENGTH - 1);
    assertThat(LinkTargets.isAllowed(atLimit)).isTrue();
    assertThat(LinkTargets.isAllowed(atLimit + "a")).isFalse();
  }
}
