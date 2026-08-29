package com.simonrowe.shortlink;

import org.springframework.stereotype.Component;

/**
 * Builds the two HTML documents {@link ShortLinkController} serves.
 *
 * <p>String concatenation rather than a template engine, deliberately: this repository has
 * no view technology on the classpath, and pulling one in for a single one-kilobyte
 * document would fail the constitution's simplicity principle. The cost of that choice is
 * that escaping is manual, which is why {@link #escapeHtml} has its own test.
 */
@Component
public class ShareDocumentRenderer {

  /**
   * The Open Graph image used when the content has none of its own.
   *
   * <p>Lives in the frontend's {@code public/images/}, not the backend's classpath, because
   * in production nginx serves {@code /images/**} from the frontend bundle. Local
   * development proxies the same path to the backend, but that does not matter: the URL is
   * always absolute against the production base, and only a crawler ever fetches it.
   */
  static final String FALLBACK_IMAGE_PATH = "/images/share-card.png";

  private static final String UPLOADS_PREFIX = "/uploads/";

  private final ShortLinkProperties properties;

  public ShareDocumentRenderer(final ShortLinkProperties properties) {
    this.properties = properties;
  }

  /**
   * The share document, served with a {@code 200} to every client.
   *
   * <p>It carries three redirect mechanisms because it has three audiences. A crawler
   * reads the metadata and follows none of them. A browser acts on the meta-refresh or the
   * script and never sees the body. Anything else — a text browser, a stripped-down
   * preview client, a reader with scripting disabled — gets a visible working link.
   *
   * @param target the content being shared
   * @return a complete HTML document
   */
  public String shareDocument(final ShareTarget target) {
    String destination = properties.absolute(target.destinationPath());
    String title = target.title() == null ? "simonrowe.dev" : target.title();
    String description = target.description() == null ? "" : target.description();
    String image = resolveImageUrl(target.imageUrl());

    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>%1$s</title>
        <meta name="description" content="%2$s">
        <meta property="og:type" content="article">
        <meta property="og:site_name" content="Simon Rowe">
        <meta property="og:title" content="%1$s">
        <meta property="og:description" content="%2$s">
        <meta property="og:image" content="%3$s">
        <meta property="og:url" content="%4$s">
        <meta name="twitter:card" content="summary_large_image">
        <meta name="twitter:title" content="%1$s">
        <meta name="twitter:description" content="%2$s">
        <meta name="twitter:image" content="%3$s">
        <link rel="canonical" href="%4$s">
        <meta http-equiv="refresh" content="0;url=%4$s">
        <script>location.replace("%5$s")</script>
        </head>
        <body>
        <p><a href="%4$s">Continue to %1$s</a></p>
        </body>
        </html>
        """.formatted(
        escapeHtml(title),
        escapeHtml(description),
        escapeHtml(image),
        escapeHtml(destination),
        escapeScript(destination));
  }

  /**
   * The document served for a slug that does not exist.
   *
   * <p>A {@code 404}, never a redirect to the home page — a typo that lands somewhere
   * plausible looks like a working link, and whoever shared it never finds out it was
   * wrong.
   *
   * <p>Self-contained, with every rule inlined and no external asset. The same constraint
   * the production maintenance pages carry, for the same reason: this response is what a
   * client sees when the normal path did not work, so it cannot depend on the normal path
   * working.
   *
   * @return a complete HTML document
   */
  public String notFoundDocument() {
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Link not found | Simon Rowe</title>
        <meta name="robots" content="noindex">
        <style>
        :root{color-scheme:dark}
        body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
        background:#0f131c;color:#dfe2ef;
        font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif}
        main{max-width:32rem;padding:2rem;text-align:center}
        h1{font-family:'Space Grotesk','Inter',sans-serif;font-size:1.75rem;margin:0 0 .75rem;
        letter-spacing:-.02em}
        p{color:#bec8cf;line-height:1.6;margin:0 0 1.75rem}
        a{display:inline-block;padding:.7rem 1.4rem;border-radius:.5rem;background:#77d1ff;
        color:#0f131c;font-weight:600;text-decoration:none}
        a:hover{background:#a5e2ff}
        </style>
        </head>
        <body>
        <main>
        <h1>This link doesn't go anywhere</h1>
        <p>The short link you followed has expired or was mistyped. Nothing was moved &mdash;
        the address just isn't one we recognise.</p>
        <a href="%s">Go to simonrowe.dev</a>
        </main>
        </body>
        </html>
        """.formatted(escapeHtml(properties.absolute("/")));
  }

  /**
   * Resolves a stored image reference to something absolute.
   *
   * <p><b>Never returns a relative URL.</b> Crawlers drop a relative {@code og:image}
   * without saying so, which presents as "link previews don't work" with no error anywhere
   * — the single most likely way for this feature to look broken while every test passes.
   *
   * <p>Three rules, in order: a site-hosted upload path is made absolute against the site
   * origin; an already-absolute URL passes through, which for news hotlinks the
   * publisher's image and is fine because it is the image the card already shows; anything
   * else falls back to the committed share card.
   *
   * @param stored the image reference as held on the content, possibly null
   * @return an absolute URL
   */
  String resolveImageUrl(final String stored) {
    if (stored == null || stored.isBlank()) {
      return properties.absolute(FALLBACK_IMAGE_PATH);
    }
    String trimmed = stored.trim();
    if (trimmed.startsWith(UPLOADS_PREFIX)) {
      return properties.absolute(trimmed);
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return trimmed;
    }
    return properties.absolute(FALLBACK_IMAGE_PATH);
  }

  /**
   * Escapes text for an HTML attribute or text node.
   *
   * <p>All five entities, not only the three that matter for a text node: every
   * interpolation in {@link #shareDocument} except one sits inside a double-quoted
   * attribute, where an unescaped {@code "} ends the attribute and lets a crafted title
   * inject markup.
   */
  static String escapeHtml(final String value) {
    if (value == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  /**
   * Escapes a value for a JavaScript string literal inside a {@code <script>} element.
   *
   * <p>HTML escaping is the wrong tool here and using it would be a bug in both
   * directions: {@code &quot;} is not a quote to the JavaScript parser, and the one
   * sequence that genuinely matters — a literal {@code </script>} — passes HTML escaping
   * untouched inside a script element, where the HTML tokeniser ends the block early.
   *
   * <p>Only the destination URL is interpolated here, and it is built from a slug and an
   * id rather than from free text, so this is defence in depth rather than a live hole.
   */
  static String escapeScript(final String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "")
        .replace("\r", "")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e");
  }
}
