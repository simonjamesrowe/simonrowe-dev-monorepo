package com.simonrowe.webfetch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches a public web page and extracts readable text, for the chat assistant's fetchUrl tool.
 * Guards against SSRF by allowing only http/https and rejecting hosts that resolve to loopback,
 * private, link-local, ULA, CGNAT, or otherwise non-public addresses. Redirects are followed
 * manually, re-validating every hop before it is requested, so a public URL cannot bounce the
 * fetch onto an internal address. Never throws to callers.
 *
 * <p>Accepted residual risk: a DNS-rebinding TOCTOU gap remains — {@link #isFetchableUrl(String)}
 * resolves the host, but jsoup re-resolves it on connect, so a hostile resolver could return a
 * public address at validation and a private one at fetch. This is accepted as low-risk for this
 * rate-limited, model-gated internal tool.
 */
public class UrlFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(UrlFetcher.class);
  private static final String USER_AGENT =
      "Mozilla/5.0 (compatible; SimonRoweBot/1.0; +https://simonrowe.dev)";
  private static final int MILLIS_PER_SECOND = 1000;
  private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
  private static final int MAX_REDIRECTS = 5;
  private static final int HTTP_OK = 200;
  private static final int HTTP_REDIRECT_MIN = 300;
  private static final int HTTP_REDIRECT_MAX = 399;

  private final int maxChars;
  private final int timeoutSeconds;

  public UrlFetcher(final int maxChars, final int timeoutSeconds) {
    this.maxChars = maxChars;
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Whether the URL is safe to fetch: http/https scheme and a host that resolves only to public
   * (non-loopback, non-private, non-link-local, non-ULA, non-CGNAT, non-multicast) addresses.
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
        if (isDisallowedAddress(address)) {
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
   * Whether an address is outside the public routable range and must not be fetched. Folds the
   * JDK's loopback/any-local/link-local/site-local/multicast checks together with raw-byte checks
   * for IPv6 Unique Local Addresses (RFC 4193, {@code fc00::/7}) and IPv4 CGNAT shared address
   * space (RFC 6598, {@code 100.64.0.0/10}), which the JDK helpers miss.
   *
   * @param address a resolved address
   * @return true when the address must be rejected
   */
  private static boolean isDisallowedAddress(final InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    final byte[] bytes = address.getAddress();
    // IPv6 ULA fc00::/7 — first byte fc or fd.
    if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
      return true;
    }
    // IPv4 CGNAT 100.64.0.0/10 (RFC 6598).
    return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40;
  }

  /**
   * Fetch and extract readable text from a public web page. Redirects are followed manually so the
   * next hop can be validated before it is requested.
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
      URI current = URI.create(url.trim());
      Connection.Response response = execute(current.toString());
      int hops = 0;
      while (isRedirect(response.statusCode()) && hops < MAX_REDIRECTS) {
        final String location = response.header("Location");
        if (location == null || location.isBlank()) {
          break;
        }
        // Validate the next hop BEFORE requesting it, so a redirect cannot reach an internal host.
        current = current.resolve(location.trim());
        final String next = current.toString();
        if (!isFetchableUrl(next)) {
          LOG.warn("Refusing content: redirect landed on a non-public URL");
          return null;
        }
        response = execute(next);
        hops++;
      }
      return readResponse(response, current.toString(), maxChars);
    } catch (Exception e) {
      LOG.warn("Failed to fetch URL: {}", url, e);
      return null;
    }
  }

  /**
   * Read a terminal (non-redirect) response into content, rejecting anything that is not a
   * successful HTML/text read so blocked pages (403/429, LinkedIn's 999) and binary bodies
   * (e.g. PDFs) degrade to the "couldn't read that page" path instead of feeding noise to the
   * model.
   *
   * @param response the terminal jsoup response
   * @param finalUrl the effective (post-redirect) URL to record
   * @param maxChars maximum number of characters of body text to keep
   * @return extracted content, or {@code null} if the response is not a readable HTML page
   * @throws IOException if parsing the response body fails
   */
  static WebPageContent readResponse(
      final Connection.Response response, final String finalUrl, final int maxChars)
      throws IOException {
    if (response.statusCode() != HTTP_OK) {
      return null;
    }
    final String contentType = response.contentType();
    if (contentType != null) {
      final String lower = contentType.toLowerCase(Locale.ROOT);
      if (!lower.contains("html") && !lower.contains("xml") && !lower.startsWith("text/")) {
        return null;
      }
    }
    final Document doc = response.parse();
    return extract(doc, finalUrl, maxChars);
  }

  private Connection.Response execute(final String url) throws IOException {
    return Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(timeoutSeconds * MILLIS_PER_SECOND)
        .maxBodySize(MAX_BODY_BYTES)
        .followRedirects(false)
        .ignoreHttpErrors(true)
        .execute();
  }

  private static boolean isRedirect(final int statusCode) {
    return statusCode >= HTTP_REDIRECT_MIN && statusCode <= HTTP_REDIRECT_MAX;
  }

  /**
   * Extract the title and truncated body text from a parsed document.
   *
   * @param doc the parsed document
   * @param finalUrl the effective (post-redirect) URL to record
   * @param maxChars maximum number of characters of body text to keep
   * @return the extracted, truncated content
   */
  static WebPageContent extract(final Document doc, final String finalUrl, final int maxChars) {
    final String title = doc.title();
    String text = doc.body() != null ? doc.body().text() : doc.text();
    if (text.length() > maxChars) {
      text = text.substring(0, maxChars);
    }
    return new WebPageContent(title, finalUrl, text);
  }
}
