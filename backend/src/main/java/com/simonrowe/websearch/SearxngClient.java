package com.simonrowe.websearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client over a self-hosted <a href="https://docs.searxng.org/">SearXNG</a> metasearch
 * instance (no API key, no per-call cost). Kept as a separate injectable bean so it can be mocked
 * in tests and swapped independently of the chat tooling. The instance must have the JSON output
 * format enabled ({@code search.formats: [html, json]}).
 */
@Component
public class SearxngClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final RestClient restClient;
  private final String baseUrl;
  private final int maxResults;

  public SearxngClient(
      @Value("${web-search.searxng.base-url:}") final String baseUrl,
      @Value("${web-search.searxng.max-results:5}") final int maxResults) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.maxResults = maxResults;
    final ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(TIMEOUT)
            .withReadTimeout(TIMEOUT);
    this.restClient =
        RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
  }

  /**
   * Whether a SearXNG base URL is configured. When {@code false} the caller must not invoke
   * {@link #search(String)}.
   *
   * @return true when a base URL is present
   */
  public boolean isConfigured() {
    return !baseUrl.isBlank();
  }

  /**
   * Search the live web via SearXNG's JSON API.
   *
   * @param query the search query (assumed non-blank; callers guard blank queries)
   * @return matching results, capped at the configured maximum, or an empty list when SearXNG
   *     returns nothing usable
   */
  public List<WebSearchResult> search(final String query) {
    final SearxngResponse response =
        restClient
            .get()
            .uri(baseUrl + "/search?q={q}&format=json", query)
            .retrieve()
            .body(SearxngResponse.class);
    if (response == null || response.results() == null) {
      return List.of();
    }
    return response.results().stream()
        .filter(r -> r.title() != null && !r.title().isBlank())
        .filter(r -> r.url() != null && !r.url().isBlank())
        .limit(maxResults)
        .map(r -> new WebSearchResult(r.title(), r.url(), r.content()))
        .toList();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SearxngResponse(List<SearxngResult> results) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SearxngResult(String title, String url, String content) {
  }
}
