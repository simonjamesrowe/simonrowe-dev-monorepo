package com.simonrowe.aggregation;

import com.simonrowe.factoryadmin.FactoryAdminProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads an article in the self-hosted headless browser, through software-factory.
 *
 * <p>openai.com puts every article behind a Cloudflare challenge that a scraper never passes
 * and a real Chromium passes at once, as long as its user agent does not say
 * {@code HeadlessChrome}. The browser runs in its own {@code page-reader} container behind an
 * egress proxy, never in this one: Constitution Principle II keeps process launches out of the
 * container serving public traffic. The factory orchestrates the read as a Temporal workflow and
 * answers when it finishes, so this is one blocking call.
 *
 * <p>Asked before {@link ReaderFallbackClient} (Jina), so the third party is used only when the
 * browser here could not read the page. Never throws: anything but a read article is an empty
 * result, and the factory refuses at once (503) when no page-reader is polling, so a stopped
 * container costs a round trip, not a timeout.
 */
@Component
@EnableConfigurationProperties(PageReaderProperties.class)
public class PageReaderClient {

  private static final Logger LOG = LoggerFactory.getLogger(PageReaderClient.class);

  private static final String TOKEN_HEADER = "X-Factory-Token";
  private static final String PATH = "/api/page-reads";

  private final PageReaderProperties properties;
  private final RestClient factory;
  private final String token;

  public PageReaderClient(
      final PageReaderProperties properties, final FactoryAdminProperties factoryProperties) {
    this.properties = properties;
    this.factory = RestClient.builder()
        .baseUrl(factoryProperties.factoryBaseUrl())
        .requestFactory(new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(properties.timeout())
            .buildRequestFactory())
        .build();
    this.token = factoryProperties.triggerToken();
  }

  /**
   * The article's text, as the browser read it.
   *
   * @param articleUrl the article's own address
   * @return the text, or empty when switched off, not an article URL, or not read
   */
  public Optional<String> read(final String articleUrl) {
    if (!properties.enabled() || !ReaderFallbackClient.isReadable(articleUrl)) {
      return Optional.empty();
    }
    try {
      Response response = factory.post().uri(PATH)
          .header(TOKEN_HEADER, token)
          .header(HttpHeaders.CONTENT_TYPE, "application/json")
          .body(Map.of("url", articleUrl))
          .retrieve()
          .body(Response.class);
      return textOf(response, articleUrl);
    } catch (Exception e) {
      LOG.info("Page reader could not read {}: {}", articleUrl, e.toString());
      return Optional.empty();
    }
  }

  static Optional<String> textOf(final Response response, final String articleUrl) {
    // A null body or result is checked though the factory never sends one: the cost of the
    // check is nothing, and a factory version that did would otherwise throw here.
    if (response == null || !"COMPLETED".equals(response.state()) || response.result() == null) {
      LOG.info("Page reader did not finish {}: {}", articleUrl,
          response == null ? "no answer" : response.state());
      return Optional.empty();
    }
    Result result = response.result();
    if (!"READ".equals(result.outcome()) || result.text() == null || result.text().isBlank()) {
      LOG.info("Page reader could not read {}: {} (HTTP {})",
          articleUrl, result.outcome(), result.httpStatus());
      return Optional.empty();
    }
    return Optional.of(result.text().strip());
  }

  /** The factory's {@code PageReadResponse}. Strings, so a new state or outcome reads as "no". */
  record Response(String state, Result result) {
  }

  /** The factory's {@code PageReadResult}. */
  record Result(String outcome, Integer httpStatus, String finalUrl, String title, String text) {
  }
}
