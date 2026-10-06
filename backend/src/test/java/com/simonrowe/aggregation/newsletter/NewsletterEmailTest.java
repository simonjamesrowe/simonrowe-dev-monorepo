package com.simonrowe.aggregation.newsletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NewsletterEmailTest {

  private static final String GOOGLE_RESULTS = """
      mx.google.com;       dkim=pass header.i=@tldrnewsletter.com header.s=4fjb header.b=gk55;\
             dkim=pass header.i=@amazonses.com header.s=224i header.b=RcNx;\
             spf=pass smtp.mailfrom=dailyupdate.tldrnewsletter.com""";

  private static JsonNode message(final String from, final String authResults,
      final String forgedResults) {
    String html = Base64.getUrlEncoder().encodeToString(
        "<p>issue</p>".getBytes(StandardCharsets.UTF_8));
    String forged = forgedResults == null ? "" : """
        {"name": "Authentication-Results", "value": "%s"},""".formatted(forgedResults);
    String json = """
        {"id": "m1", "internalDate": "1791285506000",
         "payload": {"mimeType": "multipart/alternative",
           "headers": [
             {"name": "Authentication-Results", "value": "%s"},
             %s
             {"name": "From", "value": "%s"},
             {"name": "Subject", "value": "Building good agent loops"}],
           "parts": [
             {"mimeType": "text/plain", "body": {"data": ""}},
             {"mimeType": "text/html", "body": {"data": "%s"}}]}}"""
        .formatted(authResults, forged, from.replace("\"", "\\\""), html);
    return new ObjectMapper().readTree(json);
  }

  @Test
  void readsTheEditionFromTheDisplayNameAndTheHtmlPart() {
    NewsletterEmail email = NewsletterEmail.from(
        message("TLDR Dev <dan@tldrnewsletter.com>", GOOGLE_RESULTS, null));

    assertThat(email.displayName()).isEqualTo("TLDR Dev");
    assertThat(email.fromAddress()).isEqualTo("dan@tldrnewsletter.com");
    assertThat(email.subject()).isEqualTo("Building good agent loops");
    assertThat(email.receivedAt()).isEqualTo(Instant.ofEpochMilli(1791285506000L));
    assertThat(email.html()).isEqualTo("<p>issue</p>");
    assertThat(email.signedBy("tldrnewsletter.com")).isTrue();
  }

  @Test
  void quotedDisplayNameIsUnquoted() {
    NewsletterEmail email = NewsletterEmail.from(
        message("\"TLDR\" <Dan@TLDRNewsletter.com>", GOOGLE_RESULTS, null));

    assertThat(email.displayName()).isEqualTo("TLDR");
    assertThat(email.fromAddress()).isEqualTo("dan@tldrnewsletter.com");
  }

  @Test
  void onlyGooglesOwnHeaderCounts() {
    // The sender's own claim sits below Google's and must be ignored.
    NewsletterEmail email = NewsletterEmail.from(message(
        "TLDR <dan@tldrnewsletter.com>",
        "mx.google.com; dkim=fail header.i=@tldrnewsletter.com; spf=softfail",
        "mx.google.com; dkim=pass header.i=@tldrnewsletter.com"));

    assertThat(email.signedBy("tldrnewsletter.com")).isFalse();
  }

  @Test
  void headerNotStampedByGoogleIsIgnored() {
    NewsletterEmail email = NewsletterEmail.from(message(
        "TLDR <dan@tldrnewsletter.com>",
        "attacker.example; dkim=pass header.i=@tldrnewsletter.com", null));

    assertThat(email.signedBy("tldrnewsletter.com")).isFalse();
  }

  @Test
  void signatureFromAnotherDomainDoesNotCount() {
    NewsletterEmail email = NewsletterEmail.from(message(
        "TLDR <dan@tldrnewsletter.com>",
        "mx.google.com; dkim=pass header.i=@amazonses.com", null));

    assertThat(email.signedBy("tldrnewsletter.com")).isFalse();
    assertThat(NewsletterEmail.dkimPassDomains("mx.google.com; dkim=pass header.d=Example.COM"))
        .containsExactly("example.com");
  }
}
