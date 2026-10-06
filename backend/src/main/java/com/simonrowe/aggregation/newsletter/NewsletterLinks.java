package com.simonrowe.aggregation.newsletter;

import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Turns the links in a newsletter into the address a reader would end up at.
 *
 * <p>Pure string work: nothing here makes a request. In particular the click-tracking wrapper
 * is decoded, never followed, because following it would record a click on Simon's behalf for
 * every story in every issue.
 */
final class NewsletterLinks {

  /** TLDR's click tracker. The real address is the first path segment after {@code /CL0/}. */
  static final String TRACKING_HOST = "tracking.tldrnewsletter.com";
  private static final String TRACKING_PREFIX = "/CL0/";

  private NewsletterLinks() {
  }

  /**
   * The address a tracked link points at.
   *
   * <p>A link that is not wrapped is returned as it is, so a change in how the newsletter tracks
   * clicks degrades to direct links rather than to no stories at all. Only http and https with a
   * host survive, checked on the parsed URI rather than on a prefix.
   *
   * @param href the anchor's {@code href}
   * @return the story's own address, or empty when there is no usable one
   */
  static Optional<String> unwrap(final String href) {
    final Optional<URI> parsed = parse(href);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    final URI uri = parsed.get();
    if (!TRACKING_HOST.equalsIgnoreCase(uri.getHost())) {
      return Optional.of(uri.toString());
    }
    final String rawPath = uri.getRawPath();
    if (rawPath == null || !rawPath.startsWith(TRACKING_PREFIX)) {
      return Optional.empty();
    }
    final String rest = rawPath.substring(TRACKING_PREFIX.length());
    final int end = rest.indexOf('/');
    final String encoded = end < 0 ? rest : rest.substring(0, end);
    // URLDecoder reads '+' as a space; the tracker percent-encodes everything else, so a raw '+'
    // is a literal plus in the target address.
    final String decoded = URLDecoder.decode(
        encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
    return parse(decoded).map(URI::toString);
  }

  /**
   * The address a story is stored and de-duplicated under.
   *
   * <p>Drops {@code utm_*} parameters and the fragment, which differ between editions of the
   * same newsletter ({@code utm_source=tldrdev} against {@code utm_source=tldrnewsletter}) and
   * would otherwise make one article into two. Any other parameter is kept, because on some sites
   * the query is the article's identity. With no query left, the shared scraper rules apply, so a
   * story another source already holds lines up with that source's stored address.
   *
   * @param url an absolute http(s) address
   * @return the canonical address, or empty when it does not parse
   */
  static Optional<String> canonical(final String url) {
    final Optional<URI> parsed = parse(url);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    final URI uri = parsed.get();
    final StringJoiner kept = new StringJoiner("&");
    final String rawQuery = uri.getRawQuery();
    if (rawQuery != null) {
      for (String pair : rawQuery.split("&")) {
        if (!pair.isEmpty() && !pair.toLowerCase(Locale.ROOT).startsWith("utm_")) {
          kept.add(pair);
        }
      }
    }
    final String base = SitemapHtmlScraper.normalizeUrl(
        uri.getScheme() + "://" + uri.getRawAuthority()
            + (uri.getRawPath() == null ? "" : uri.getRawPath()));
    return Optional.of(kept.length() == 0 ? base : base + "?" + kept);
  }

  /**
   * The registrable host of an address, lower-cased.
   *
   * @param url an absolute address
   * @return its host, or empty when it has none
   */
  static Optional<String> host(final String url) {
    return parse(url).map(uri -> uri.getHost().toLowerCase(Locale.ROOT));
  }

  private static Optional<URI> parse(final String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      final URI uri = URI.create(value.trim());
      final String scheme = uri.getScheme();
      if (scheme == null
          || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          || uri.getHost() == null || uri.getHost().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(uri);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
