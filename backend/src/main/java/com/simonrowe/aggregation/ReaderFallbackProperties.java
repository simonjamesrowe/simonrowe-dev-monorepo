package com.simonrowe.aggregation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reader service {@link ArticleSourceTextProvider} asks for an article's text when the
 * page will not serve it to the scrapers directly.
 *
 * @param enabled whether to ask at all. Off, a blocked page falls back to the stored feed text
 *     exactly as it did before the fallback existed.
 * @param baseUrl the reader's address; the article URL is appended to it. Jina Reader
 *     ({@code https://r.jina.ai/}) by default.
 * @param apiKey optional. Jina answers without one at a lower rate limit, which on-demand
 *     summaries and the weekly digest stay well inside.
 * @param timeout how long one read may take. Jina renders the page in a headless browser, so a
 *     read takes seconds rather than milliseconds.
 */
@ConfigurationProperties("aggregation.reader-fallback")
public record ReaderFallbackProperties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    Duration timeout
) {

  /** Applies defaults for anything unset. */
  public ReaderFallbackProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://r.jina.ai/";
    }
    if (!baseUrl.endsWith("/")) {
      baseUrl = baseUrl + "/";
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      timeout = Duration.ofSeconds(30);
    }
  }

  /** Whether a key was configured. */
  boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  /** Never prints the key: a record's default {@code toString} would. */
  @Override
  public String toString() {
    return "ReaderFallbackProperties[enabled=%s, baseUrl=%s, apiKey=%s, timeout=%s]"
        .formatted(enabled, baseUrl, hasApiKey() ? "<set>" : "<unset>", timeout);
  }
}
