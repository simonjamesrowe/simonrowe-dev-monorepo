package com.simonrowe.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ShareDocumentRendererTest {

  private static final String BASE = "https://simonrowe.dev";

  private final ShareDocumentRenderer renderer =
      new ShareDocumentRenderer(new ShortLinkProperties(BASE));

  private static ShareTarget target(final String title, final String description,
      final String imageUrl) {
    return new ShareTarget(title, description, imageUrl, "/blogs/abc123");
  }

  // ---------------------------------------------------------------- image resolution

  @Test
  void makesAnUploadPathAbsolute() {
    assertThat(renderer.resolveImageUrl("/uploads/kafka-large.png"))
        .isEqualTo(BASE + "/uploads/kafka-large.png");
  }

  @Test
  void passesAnAlreadyAbsoluteUrlThrough() {
    // News hotlinks the publisher's image. Acceptable: it is the image the card already
    // shows, so nothing new is being exposed.
    assertThat(renderer.resolveImageUrl("https://cdn.example.com/story.jpg"))
        .isEqualTo("https://cdn.example.com/story.jpg");
    assertThat(renderer.resolveImageUrl("http://cdn.example.com/story.jpg"))
        .isEqualTo("http://cdn.example.com/story.jpg");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "images/local.png", "./relative.png", "data:image/png;base64,aa"})
  void fallsBackToTheCommittedShareCardForAnythingElse(final String stored) {
    assertThat(renderer.resolveImageUrl(stored))
        .isEqualTo(BASE + ShareDocumentRenderer.FALLBACK_IMAGE_PATH);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"/uploads/a.png", "https://cdn.example.com/a.jpg", "nonsense"})
  void neverEmitsRelativeImageUrl(final String stored) {
    // The failure this guards is silent: a crawler drops a relative og:image without
    // complaining, so the feature looks broken with no error anywhere.
    assertThat(renderer.resolveImageUrl(stored)).startsWith("http");
  }

  // ---------------------------------------------------------------- escaping

  @Test
  void escapesEveryHtmlMetacharacter() {
    assertThat(ShareDocumentRenderer.escapeHtml("a & b < c > d \" e ' f"))
        .isEqualTo("a &amp; b &lt; c &gt; d &quot; e &#39; f");
  }

  @Test
  void escapesQuotesSoTitlesCannotBreakOutOfAttributes() {
    String html = renderer.shareDocument(
        target("Kafka: \"exactly once\" explained", "A post", null));

    assertThat(html).doesNotContain("content=\"Kafka: \"exactly");
    assertThat(html).contains("Kafka: &quot;exactly once&quot; explained");
  }

  @Test
  void escapesAngleBracketsInBothTheTitleAndTheDescription() {
    String html = renderer.shareDocument(
        target("<script>alert(1)</script>", "<img src=x onerror=alert(2)>", null));

    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).doesNotContain("<img src=x");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(html).contains("&lt;img src=x onerror=alert(2)&gt;");
  }

  @Test
  void leavesExactlyOneScriptElementInTheDocument() {
    // A title containing </script> would otherwise close the redirect block early and put
    // the rest of the document into executable position.
    String html = renderer.shareDocument(
        target("Why </script> matters", "Ending a block early", null));

    assertThat(countOccurrences(html, "<script")).isEqualTo(1);
    assertThat(countOccurrences(html, "</script>")).isEqualTo(1);
  }

  @Test
  void escapesTheAmpersandThatSeparatesQueryParameters() {
    ShareTarget articleTarget = new ShareTarget(
        "A story", "About things", null, "/news-events?article=abc&ref=x");

    String html = renderer.shareDocument(articleTarget);

    assertThat(html).contains("article=abc&amp;ref=x");
  }

  @Test
  void escapesTheScriptRedirectSeparatelyFromTheHtml() {
    // HTML entities are not quotes to a JavaScript parser, so the script literal needs
    // its own escaping rather than the HTML one.
    String html = renderer.shareDocument(target("A post", "About things", null));

    assertThat(html).contains("location.replace(\"" + BASE + "/blogs/abc123\")");
    assertThat(html).doesNotContain("location.replace(\"" + BASE + "&#");
  }

  @Test
  void escapeScriptNeutralisesClosingScriptTags() {
    assertThat(ShareDocumentRenderer.escapeScript("</script>"))
        .doesNotContain("</script>")
        .isEqualTo("\\u003c/script\\u003e");
  }

  @Test
  void escapeHtmlTreatsNullAsEmpty() {
    assertThat(ShareDocumentRenderer.escapeHtml(null)).isEmpty();
    assertThat(ShareDocumentRenderer.escapeScript(null)).isEmpty();
  }

  // ---------------------------------------------------------------- document shape

  @Test
  void carriesEveryTagCrawlersRead() {
    String html = renderer.shareDocument(
        target("Exactly-once semantics", "What the guarantee buys you",
            "/uploads/kafka.png"));

    assertThat(html)
        .contains("<meta property=\"og:type\" content=\"article\">")
        .contains("<meta property=\"og:title\" content=\"Exactly-once semantics\">")
        .contains("<meta property=\"og:description\" "
            + "content=\"What the guarantee buys you\">")
        .contains("<meta property=\"og:image\" content=\"" + BASE + "/uploads/kafka.png\">")
        .contains("<meta property=\"og:url\" content=\"" + BASE + "/blogs/abc123\">")
        .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
        .contains("<link rel=\"canonical\" href=\"" + BASE + "/blogs/abc123\">");
  }

  @Test
  void pointsTheCanonicalAtTheDestinationNotAtTheShareAddress() {
    String html = renderer.shareDocument(target("A post", "About things", null));

    assertThat(html).contains("rel=\"canonical\" href=\"" + BASE + "/blogs/abc123\"");
    assertThat(html).doesNotContain("/s/");
  }

  @Test
  void redirectsThreeWaysSoEveryKindOfClientGetsThere() {
    String html = renderer.shareDocument(target("A post", "About things", null));

    // A browser acting on either of these never sees the body.
    assertThat(html).contains("<meta http-equiv=\"refresh\" content=\"0;url=");
    assertThat(html).contains("location.replace(");
    // A text browser or a client with scripting off still gets a working link.
    assertThat(html).contains("<a href=\"" + BASE + "/blogs/abc123\">Continue to A post</a>");
  }

  @Test
  void survivesContentWithNoTitleOrDescription() {
    String html = renderer.shareDocument(new ShareTarget(null, null, null, "/blogs/abc123"));

    assertThat(html).contains("<title>simonrowe.dev</title>");
    assertThat(html).contains("<meta property=\"og:description\" content=\"\">");
    assertThat(html).contains(ShareDocumentRenderer.FALLBACK_IMAGE_PATH);
  }

  @Test
  void staysSmallEnoughToBeCheapForEveryUnfurler() {
    String html = renderer.shareDocument(
        target("Exactly-once semantics", "What the guarantee buys you", null));

    assertThat(html.length()).isLessThan(2048);
  }

  // ---------------------------------------------------------------- not found

  @Test
  void notFoundIsSelfContainedWithNoExternalAsset() {
    // The same constraint the production maintenance pages carry: this is what a client
    // sees when the normal path did not work, so it cannot depend on the normal path.
    String html = renderer.notFoundDocument();

    assertThat(html).contains("<style>");
    assertThat(html).doesNotContain("<link rel=\"stylesheet\"");
    assertThat(html).doesNotContain("<img");
    assertThat(html).doesNotContain("<script");
    assertThat(html).doesNotContain("fonts.googleapis.com");
  }

  @Test
  void notFoundOffersTheHomePageAsLinkRatherThanRedirecting() {
    // A typo that silently lands on the home page looks like a working link, and whoever
    // shared it never finds out it was wrong.
    String html = renderer.notFoundDocument();

    assertThat(html).contains("<a href=\"" + BASE + "/\">");
    assertThat(html).doesNotContain("http-equiv=\"refresh\"");
    assertThat(html).contains("noindex");
  }

  private static int countOccurrences(final String haystack, final String needle) {
    int count = 0;
    int index = haystack.indexOf(needle);
    while (index >= 0) {
      count++;
      index = haystack.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
