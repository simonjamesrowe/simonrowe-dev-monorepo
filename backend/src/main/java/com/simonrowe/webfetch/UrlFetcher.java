package com.simonrowe.webfetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches a public web page and extracts readable text, for the chat assistant's fetchUrl tool.
 * Guards against SSRF by allowing only http/https and rejecting hosts that resolve to loopback,
 * private, link-local, or otherwise non-public addresses. Never throws to callers.
 */
public class UrlFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(UrlFetcher.class);
  private static final String USER_AGENT =
      "Mozilla/5.0 (compatible; SimonRoweBot/1.0; +https://simonrowe.dev)";
  private static final int MILLIS_PER_SECOND = 1000;
  private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

  private final int maxChars;
  private final int timeoutSeconds;

  public UrlFetcher(final int maxChars, final int timeoutSeconds) {
    this.maxChars = maxChars;
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Whether the URL is safe to fetch: http/https scheme and a host that resolves only to public
   * (non-loopback, non-private, non-link-local, non-multicast) addresses.
   *
   * @param url candidate URL
   * @return true when the URL may be fetched
   */
  public static boolean isFetchableUrl(final String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    final URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    final String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return false;
    }
    final String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    if (host.equalsIgnoreCase("localhost")) {
      return false;
    }
    try {
      for (final InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isLoopbackAddress()
            || address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          return false;
        }
      }
    } catch (UnknownHostException e) {
      // Unresolvable host (e.g. an internal container name with no public DNS) — do not fetch.
      return false;
    }
    return true;
  }

  /**
   * Fetch and extract readable text from a public web page.
   *
   * @param url the page URL (callers should pre-check with {@link #isFetchableUrl(String)})
   * @return extracted content, or {@code null} if the URL is unsafe or the fetch fails
   */
  public WebPageContent fetch(final String url) {
    if (!isFetchableUrl(url)) {
      LOG.warn("Refusing to fetch non-public or invalid URL");
      return null;
    }
    try {
      final Document doc =
          Jsoup.connect(url.trim())
              .userAgent(USER_AGENT)
              .timeout(timeoutSeconds * MILLIS_PER_SECOND)
              .maxBodySize(MAX_BODY_BYTES)
              .followRedirects(true)
              .get();
      // Re-validate the effective URL after any redirects before using the content.
      final String finalUrl = doc.location() != null ? doc.location() : url.trim();
      if (!isFetchableUrl(finalUrl)) {
        LOG.warn("Refusing content: redirect landed on a non-public URL");
        return null;
      }
      final String title = doc.title();
      String text = doc.body() != null ? doc.body().text() : doc.text();
      if (text.length() > maxChars) {
        text = text.substring(0, maxChars);
      }
      return new WebPageContent(title, finalUrl, text);
    } catch (Exception e) {
      LOG.warn("Failed to fetch URL: {}", url, e);
      return null;
    }
  }
}
