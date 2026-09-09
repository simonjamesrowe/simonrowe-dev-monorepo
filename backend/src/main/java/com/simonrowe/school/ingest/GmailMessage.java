package com.simonrowe.school.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;

/**
 * One school email, reduced to what Term Time needs.
 *
 * @param id the Gmail message id
 * @param subject the subject line
 * @param fromAddress the bare sender address, display name stripped
 * @param fromDisplayName the display name, kept only so it can be logged when it lies
 * @param receivedAt when Gmail received it
 * @param body plain text, converted from HTML when that is all the message carries
 * @param attachments any attachments, with the ids needed to download them
 * @param links hyperlinks found in the body. Recorded only — nothing here is fetched
 */
public record GmailMessage(
    String id,
    String subject,
    String fromAddress,
    String fromDisplayName,
    Instant receivedAt,
    String body,
    List<Attachment> attachments,
    List<Link> links
) {

  /**
   * Pulls the bare address out of a {@code From} header.
   *
   * <p>This is the single most important line in the ingest path. {@code From} looks like
   * {@code "Kilmorie Primary School" <system@insighttracking.com>} — a real example from this
   * mailbox — and any allowlist applied to the whole header, or to the display name, admits a
   * third party sending under the school's name.
   */
  private static final Pattern ANGLE_ADDRESS = Pattern.compile("<([^>]+)>");

  /**
   * Builds a message from Gmail's JSON.
   *
   * @param node a {@code users.messages.get} response with {@code format=full}
   * @return the reduced message
   */
  public static GmailMessage from(final JsonNode node) {
    final JsonNode payload = node.path("payload");
    String subject = "";
    String from = "";
    for (JsonNode header : payload.path("headers")) {
      final String name = header.path("name").asString().toLowerCase(Locale.ROOT);
      if ("subject".equals(name)) {
        subject = header.path("value").asString();
      } else if ("from".equals(name)) {
        from = header.path("value").asString();
      }
    }

    final Matcher matcher = ANGLE_ADDRESS.matcher(from);
    final String address = matcher.find()
        ? matcher.group(1).trim().toLowerCase(Locale.ROOT)
        // A header with no angle brackets is the bare address itself.
        : from.trim().toLowerCase(Locale.ROOT);
    final String displayName = matcher.reset().find()
        ? from.substring(0, from.indexOf('<')).replace("\"", "").trim()
        : "";

    final StringBuilder text = new StringBuilder();
    final List<Attachment> attachments = new ArrayList<>();
    final java.util.LinkedHashMap<String, String> links = new java.util.LinkedHashMap<>();
    collect(payload, text, attachments, links);

    final long millis = node.path("internalDate").asLong(0L);

    return new GmailMessage(
        node.path("id").asString(),
        subject,
        address,
        displayName,
        Instant.ofEpochMilli(millis),
        text.toString().trim(),
        List.copyOf(attachments),
        links.entrySet().stream().map(e -> new Link(e.getKey(), e.getValue())).toList());
  }

  /**
   * Walks the MIME tree, preferring {@code text/plain} and falling back to stripped HTML.
   *
   * <p>An attachment is identified by the <b>presence of an {@code attachmentId}</b>, never by a
   * size threshold — small attachments are inlined and large ones are not, and there is no
   * documented boundary between the two.
   */
  private static void collect(
      final JsonNode part, final StringBuilder text, final List<Attachment> attachments,
      final java.util.Map<String, String> links) {
    final String mimeType = part.path("mimeType").asString("");
    final String filename = part.path("filename").asString("");
    final JsonNode body = part.path("body");

    if (!filename.isEmpty() && body.has("attachmentId")) {
      attachments.add(new Attachment(
          filename, body.path("attachmentId").asString(), mimeType, body.path("size").asLong(0)));
      return;
    }

    if ("text/plain".equals(mimeType)) {
      text.append(GmailClient.decode(body.path("data").asString(""))).append('\n');
    } else if ("text/html".equals(mimeType)) {
      final String html = GmailClient.decode(body.path("data").asString(""));
      if (!html.isEmpty()) {
        final org.jsoup.nodes.Document parsed = Jsoup.parse(html);

        // The HTML is ALWAYS parsed for anchors, even when a plain-text part has already
        // supplied the body. This is the only point at which the markup still exists —
        // everything downstream sees plain text — and gating the whole branch on
        // text.isEmpty(), as this originally did, meant that for an ordinary
        // multipart/alternative message (plain part first) no link was ever recorded.
        for (org.jsoup.nodes.Element anchor : parsed.select("a[href]")) {
          final String href = anchor.attr("href").trim();
          if (href.startsWith("http://") || href.startsWith("https://")) {
            links.putIfAbsent(href, anchor.text().trim());
          }
        }

        // The TEXT, though, is only taken when no plain part was seen. Many school newsletters
        // send both, and appending the HTML alternative would duplicate every sentence in the
        // embedding.
        if (text.isEmpty()) {
          text.append(parsed.text()).append('\n');
        }
      }
    }

    for (JsonNode child : part.path("parts")) {
      collect(child, text, attachments, links);
    }
  }

  /**
   * One attachment on a message.
   *
   * <p>Carries the {@code attachmentId} rather than only the filename, because the filename is
   * not enough to fetch it — and the weekly newsletter's content is very often entirely inside
   * a PDF rather than in the message body.
   *
   * @param filename as the sender named it
   * @param attachmentId the id {@code users.messages.attachments.get} needs
   * @param mimeType the declared type, which senders get wrong often enough to be worth
   *     re-checking against the actual bytes
   * @param size the declared size in bytes
   */
  public record Attachment(String filename, String attachmentId, String mimeType, long size) {

    /**
     * Whether this looks like a PDF worth downloading.
     *
     * @return true when either the declared type or the filename says PDF
     */
    public boolean looksLikePdf() {
      return "application/pdf".equalsIgnoreCase(mimeType)
          || filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
  }

  /**
   * A hyperlink found in a message body.
   *
   * @param url the absolute URL
   * @param text the anchor's visible text, often the only description of where it goes
   */
  public record Link(String url, String text) {
  }
}
