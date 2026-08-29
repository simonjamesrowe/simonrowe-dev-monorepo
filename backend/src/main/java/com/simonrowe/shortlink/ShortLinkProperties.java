package com.simonrowe.shortlink;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The public origin this site is reached on.
 *
 * <p>Backs two things that both have to be absolute: the share URL handed to the frontend
 * as {@code shortUrl}, and the {@code og:image} emitted by {@link ShortLinkController}.
 * The second is the one that fails quietly — crawlers drop a relative {@code og:image}
 * without complaining, so a link that looks fine in a browser unfurls with no picture and
 * nothing anywhere says why.
 *
 * @param baseUrl the site origin, without a trailing slash
 */
@ConfigurationProperties("site")
public record ShortLinkProperties(String baseUrl) {

  private static final String DEFAULT_BASE_URL = "https://simonrowe.dev";

  /**
   * Normalises the configured value so callers can concatenate a leading-slash path
   * without producing a double slash.
   *
   * @param baseUrl the configured origin, possibly null, blank or trailing-slashed
   */
  public ShortLinkProperties {
    baseUrl = normalise(baseUrl);
  }

  private static String normalise(final String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_BASE_URL;
    }
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isEmpty() ? DEFAULT_BASE_URL : trimmed;
  }

  /**
   * Prefixes a site-relative path with the base URL.
   *
   * @param path a path beginning with {@code /}
   * @return the absolute URL
   */
  public String absolute(final String path) {
    return baseUrl + path;
  }
}
