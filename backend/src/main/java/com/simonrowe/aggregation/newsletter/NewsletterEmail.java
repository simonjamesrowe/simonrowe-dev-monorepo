package com.simonrowe.aggregation.newsletter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One newsletter message, reduced to what the ingest needs.
 *
 * <p>Separate from the school's {@code GmailMessage}, which keeps plain text and discards the
 * markup and the authentication headers, and those are the two things this path depends on.
 *
 * @param id the Gmail message id
 * @param fromAddress the bare sender address, lower-cased
 * @param displayName the sender's display name, which is how one address's editions differ
 * @param subject the subject line
 * @param receivedAt when Gmail received it
 * @param html the {@code text/html} part, empty when there is none
 * @param dkimPassDomains domains Google's own MX verified a DKIM signature for
 */
record NewsletterEmail(
    String id,
    String fromAddress,
    String displayName,
    String subject,
    Instant receivedAt,
    String html,
    Set<String> dkimPassDomains
) {

  /** The receiving MX that stamps the trusted Authentication-Results header. */
  private static final String GOOGLE_MX = "mx.google.com";

  /**
   * Builds a message from a {@code users.messages.get} response with {@code format=full}.
   *
   * @param node Gmail's JSON
   * @return the reduced message
   */
  static NewsletterEmail from(final JsonNode node) {
    final JsonNode payload = node.path("payload");
    String from = "";
    String subject = "";
    String authResults = null;
    for (JsonNode header : payload.path("headers")) {
      final String name = header.path("name").asString("").toLowerCase(Locale.ROOT);
      final String value = header.path("value").asString("");
      if ("from".equals(name)) {
        from = value;
      } else if ("subject".equals(name)) {
        subject = value;
      } else if ("authentication-results".equals(name) && authResults == null
          && value.trim().startsWith(GOOGLE_MX)) {
        // Only the first header Google's own MX stamped. A sender can add any number of
        // Authentication-Results headers of its own below it, claiming whatever it likes.
        authResults = value;
      }
    }
    final int open = from.indexOf('<');
    final int close = from.indexOf('>', open + 1);
    final String address = open >= 0 && close > open
        ? from.substring(open + 1, close)
        : from;
    final String displayName = open > 0 ? from.substring(0, open).replace("\"", "") : "";
    return new NewsletterEmail(
        node.path("id").asString(""),
        address.trim().toLowerCase(Locale.ROOT),
        displayName.trim(),
        subject,
        Instant.ofEpochMilli(node.path("internalDate").asLong(0L)),
        findHtml(payload),
        dkimPassDomains(authResults));
  }

  /**
   * Whether the sender's own domain signed this message.
   *
   * <p>Gmail search's {@code from:} also matches display names, and anyone can put any address
   * in a {@code From} header, so the address alone proves nothing about who sent a message. A
   * passing DKIM signature for the sender's domain, as recorded by Google on receipt, does.
   *
   * @param senderDomain the domain of the configured sender address
   * @return true when Google verified a signature for that domain or a parent of it
   */
  boolean signedBy(final String senderDomain) {
    final String domain = senderDomain.toLowerCase(Locale.ROOT);
    return dkimPassDomains.stream()
        .anyMatch(signer -> domain.equals(signer) || domain.endsWith("." + signer));
  }

  static Set<String> dkimPassDomains(final String authResults) {
    final Set<String> domains = new LinkedHashSet<>();
    if (authResults == null) {
      return domains;
    }
    for (String clause : authResults.split(";")) {
      final String trimmed = clause.trim();
      if (!trimmed.startsWith("dkim=pass")) {
        continue;
      }
      for (String token : trimmed.split("\\s+")) {
        if (token.startsWith("header.i=@")) {
          domains.add(token.substring("header.i=@".length()).toLowerCase(Locale.ROOT));
        } else if (token.startsWith("header.d=")) {
          domains.add(token.substring("header.d=".length()).toLowerCase(Locale.ROOT));
        }
      }
    }
    return domains;
  }

  private static String findHtml(final JsonNode part) {
    if ("text/html".equals(part.path("mimeType").asString(""))) {
      final String data = part.path("body").path("data").asString("");
      if (!data.isEmpty()) {
        try {
          return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          return "";
        }
      }
    }
    for (JsonNode child : part.path("parts")) {
      final String html = findHtml(child);
      if (!html.isEmpty()) {
        return html;
      }
    }
    return "";
  }
}
