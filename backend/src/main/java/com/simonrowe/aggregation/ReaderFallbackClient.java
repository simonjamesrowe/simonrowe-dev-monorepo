package com.simonrowe.aggregation;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads an article's text through a reader service (Jina Reader by default) when the page
 * will not serve it to the scrapers.
 *
 * <p>It exists because some publishers put every article behind a bot challenge. openai.com
 * answers each {@code /index/<slug>} page with {@code 403} and {@code cf-mitigated: challenge}
 * whatever the user agent, so all the scrapers ever have is the RSS description, a sentence
 * or two. On 2026-11-04 "Scaling Git at OpenAI" could not be summarised at all (196 characters,
 * under the hard floor), and the one OpenAI summary that did complete was written from two
 * sentences and said so. Jina renders the page in a browser and returned the whole 32,000
 * character article.
 *
 * <p>Only {@link ArticleSourceTextProvider} calls this, and only after a direct scrape came
 * back thin, so ordinary sources never reach the third party. Every request goes to the one
 * configured host, so no article URL can point this client at our own network, and redirects
 * are not followed. The text that comes back is as untrusted as a scraped page, and is
 * treated the same way.
 *
 * <p>Never throws: a failed read is an empty result, and the caller falls back to the stored
 * text exactly as it did before this existed.
 */
@Component
@EnableConfigurationProperties(ReaderFallbackProperties.class)
public class ReaderFallbackClient {

  private static final Logger LOG = LoggerFactory.getLogger(ReaderFallbackClient.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The response carries the page's metadata beside its text; 41KB for a long article. */
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  private final ReaderFallbackProperties properties;
  private final HttpClient httpClient;

  public ReaderFallbackClient(final ReaderFallbackProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.timeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /**
   * The article's text as the reader rendered it.
   *
   * @param articleUrl the article's own address
   * @return the text, or empty when the fallback is off, the address is not an http(s) URL
   *     with a host, or the reader could not produce the page
   */
  public Optional<String> read(final String articleUrl) {
    if (!properties.enabled() || !isReadable(articleUrl)) {
      return Optional.empty();
    }
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(
              URI.create(properties.baseUrl() + articleUrl))
          .timeout(properties.timeout())
          .header("Accept", "application/json")
          .header("X-Retain-Images", "none")
          .GET();
      if (properties.hasApiKey()) {
        request.header("Authorization", "Bearer " + properties.apiKey());
      }
      HttpResponse<InputStream> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200) {
          LOG.info("Reader could not read {}: HTTP {}", articleUrl, response.statusCode());
          return Optional.empty();
        }
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          LOG.warn("Reader response over {} bytes, ignoring it: {}",
              MAX_RESPONSE_BYTES, articleUrl);
          return Optional.empty();
        }
        return contentOf(new String(bytes, StandardCharsets.UTF_8), articleUrl);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      LOG.warn("Reader could not read {}: {}", articleUrl, e.toString());
      return Optional.empty();
    }
  }

  /**
   * The page text from Jina's JSON envelope. {@code data.httpStatus} is the status the
   * publisher gave Jina: anything but 2xx means the text is an error or challenge page, not
   * the article.
   */
  static Optional<String> contentOf(final String json, final String articleUrl) {
    JsonNode data = MAPPER.readTree(json).path("data");
    JsonNode originStatus = data.path("httpStatus");
    if (originStatus.isNumber()
        && (originStatus.asInt() < 200 || originStatus.asInt() >= 300)) {
      LOG.info("Reader was refused by {}: HTTP {}", articleUrl, originStatus.asInt());
      return Optional.empty();
    }
    String content = data.path("content").asString("");
    return content.isBlank() ? Optional.empty() : Optional.of(content.strip());
  }

  /** An absolute http(s) URL with a host; anything else is not an article address. */
  static boolean isReadable(final String articleUrl) {
    if (articleUrl == null || articleUrl.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(articleUrl);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return ("https".equals(scheme) || "http".equals(scheme))
          && uri.getHost() != null && !uri.getHost().isBlank();
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
