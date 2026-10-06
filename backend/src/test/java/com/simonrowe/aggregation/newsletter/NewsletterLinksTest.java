package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NewsletterLinksTest {

  @Test
  void unwrapsTheTrackerWithoutFollowingIt() {
    String tracked = """
        https://tracking.tldrnewsletter.com/CL0/\
        https:%2F%2Fwww.builder.io%2Fblog%2Fagent-loop%3Futm_source=tldrdev\
        /1/010001a1-abc/HcWPr0DgO9e=452""";

    assertThat(NewsletterLinks.unwrap(tracked))
        .contains("https://www.builder.io/blog/agent-loop?utm_source=tldrdev");
  }

  @Test
  void keepsLiteralPlusInTheTarget() {
    String tracked =
        "https://tracking.tldrnewsletter.com/CL0/https:%2F%2Fexample.com%2Fc++%2Fguide/1/x";

    assertThat(NewsletterLinks.unwrap(tracked)).contains("https://example.com/c++/guide");
  }

  @Test
  void passesAnUntrackedLinkThroughAndRejectsAnythingButHttp() {
    assertThat(NewsletterLinks.unwrap("https://example.com/post")).contains(
        "https://example.com/post");
    assertThat(NewsletterLinks.unwrap("mailto:dan@tldrnewsletter.com")).isEmpty();
    assertThat(NewsletterLinks.unwrap("javascript:alert(1)")).isEmpty();
    assertThat(NewsletterLinks.unwrap("https://tracking.tldrnewsletter.com/other")).isEmpty();
    assertThat(NewsletterLinks.unwrap(
        "https://tracking.tldrnewsletter.com/CL0/javascript:alert(1)/1/x")).isEmpty();
    assertThat(NewsletterLinks.unwrap("")).isEmpty();
  }

  @Test
  void canonicalDropsTrackingParametersSoTwoEditionsAgree() {
    assertThat(NewsletterLinks.canonical(
        "https://lemire.me/blog/2026/10/05/ephemeral-testing/?utm_source=tldrdev"))
        .contains("https://lemire.me/blog/2026/10/05/ephemeral-testing");
    assertThat(NewsletterLinks.canonical(
        "https://Lemire.me/blog/2026/10/05/ephemeral-testing/?utm_source=tldrnewsletter#top"))
        .contains("https://lemire.me/blog/2026/10/05/ephemeral-testing");
  }

  @Test
  void canonicalKeepsQueryThatIdentifiesTheResource() {
    assertThat(NewsletterLinks.canonical(
        "https://www.youtube.com/watch?v=abc123&utm_source=tldrai&utm_medium=newsletter"))
        .contains("https://www.youtube.com/watch?v=abc123");
  }
}
