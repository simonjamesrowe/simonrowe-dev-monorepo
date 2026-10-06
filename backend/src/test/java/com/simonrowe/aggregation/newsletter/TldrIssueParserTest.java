package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the parser over two real TLDR issues from 2026-10-06, captured from the mailbox with
 * every subscriber-identifying link redacted.
 */
class TldrIssueParserTest {

  private static String fixture(final String name) throws IOException {
    try (InputStream in = TldrIssueParserTest.class
        .getResourceAsStream("/newsletter/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void readsEveryStoryFromDevIssueAndDropsTheSponsoredSlots() throws IOException {
    List<NewsletterStory> stories = TldrIssueParser.parse(fixture("tldr-dev-2026-10-06.html"));

    // 15 labelled headlines, three of them (Sponsor).
    assertThat(stories).hasSize(12);
    assertThat(stories).extracting(NewsletterStory::title)
        .doesNotContain("Build serverless microservices on AWS")
        .noneMatch(title -> title.contains("Sponsor"));

    NewsletterStory first = stories.getFirst();
    assertThat(first.title()).isEqualTo("Build an agent loop a small model can finish");
    assertThat(first.label()).isEqualTo("11 minute read");
    assertThat(first.section()).isEqualTo("Articles & Tutorials");
    assertThat(first.url()).isEqualTo("""
        https://www.builder.io/blog/build-an-agent-loop-a-small-model-can-finish\
        ?utm_source=tldrdev""");
    assertThat(first.summary()).startsWith("A reliable agent loop depends less on model size");
  }

  @Test
  void picksUpTheSectionEachStorySitsUnder() throws IOException {
    List<NewsletterStory> stories = TldrIssueParser.parse(fixture("tldr-dev-2026-10-06.html"));

    assertThat(stories).filteredOn(s -> s.title().equals("TanStack Charts 1.0"))
        .singleElement()
        .extracting(NewsletterStory::section).isEqualTo("Launches & Tools");
    assertThat(stories).filteredOn(s -> s.section().equals("Quick Links")).isNotEmpty();
  }

  @Test
  void neverReturnsTheNewslettersOwnLinks() throws IOException {
    for (String issue : List.of("tldr-dev-2026-10-06.html", "tldr-tech-2026-10-06.html")) {
      assertThat(TldrIssueParser.parse(fixture(issue)))
          .extracting(NewsletterStory::url)
          .noneMatch(url -> url.contains("tracking.tldrnewsletter.com"))
          .noneMatch(url -> url.contains("a.tldrnewsletter.com"))
          .noneMatch(url -> url.contains("refer.tldr.tech"))
          .noneMatch(url -> url.contains("sparklp"));
    }
  }

  @Test
  void keepsShortLinksForTheResolverAndOtherLabels() throws IOException {
    List<NewsletterStory> stories = TldrIssueParser.parse(fixture("tldr-tech-2026-10-06.html"));

    assertThat(stories).hasSize(14);
    assertThat(stories).extracting(NewsletterStory::url)
        .contains("https://links.tldrnewsletter.com/XjUHT3");
  }

  @Test
  void messageWithNoLabelledHeadlinesHasNoStories() {
    String confirmation = """
        <html><body><h1>Confirm your signup</h1>
        <a href="https://onboarding.tldr.tech/confirm?id=1"><strong>Confirm</strong></a>
        <a href="https://onboarding.tldr.tech/unsubscribe">Unsubscribe</a>
        </body></html>""";

    assertThat(TldrIssueParser.parse(confirmation)).isEmpty();
    assertThat(TldrIssueParser.parse("")).isEmpty();
    assertThat(TldrIssueParser.parse(null)).isEmpty();
  }

  @Test
  void ignoresHeadlinesWithoutUsableLink() {
    String html = """
        <a href="mailto:jobs@tldr.tech"><strong>Work with us (2 minute read)</strong></a>
        <a href="javascript:alert(1)"><strong>Nope (1 minute read)</strong></a>
        <a href="https://example.com/a"><strong>(1 minute read)</strong></a>""";

    assertThat(TldrIssueParser.parse(html)).isEmpty();
  }
}
