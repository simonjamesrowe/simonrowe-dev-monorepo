package com.simonrowe.aggregation.newsletter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds where one of the newsletter's own short links points, without going there.
 *
 * <p>TLDR shortens some story links ({@code links.tldrnewsletter.com/XjUHT3}). The address
 * behind one is what the card should link to and what dedup must compare, so it has to be known.
 * Exactly one request is made, to the shortener itself, with redirects off; the {@code Location}
 * it answers is read and validated, never requested. Any other host is returned untouched.
 */
@Component
public class ShortLinkResolver {

  private static final Logger log = LoggerFactory.getLogger(ShortLinkResolver.class);
  private static final Set<String> SHORTENER_HOSTS = Set.of("links.tldrnewsletter.com");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(TIMEOUT)
      .build();

  /**
   * The story's real address.
   *
   * @param url an address read from an issue
   * @return the same address, the shortener's target, or empty when a short link would not
   *     resolve to a usable address
   */
  public Optional<String> resolve(final String url) {
    final Optional<String> host = NewsletterLinks.host(url);
    if (host.isEmpty()) {
      return Optional.empty();
    }
    if (!SHORTENER_HOSTS.contains(host.get())) {
      return Optional.of(url);
    }
    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(TIMEOUT)
          .GET()
          .build();
      final HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.headers().firstValue("Location")
          .flatMap(NewsletterLinks::unwrap)
          // A shortener pointing at itself or its tracker is not a story.
          .filter(target -> NewsletterLinks.host(target)
              .filter(h -> !SHORTENER_HOSTS.contains(h)
                  && !NewsletterLinks.TRACKING_HOST.equals(h))
              .isPresent());
    } catch (IOException | IllegalArgumentException e) {
      log.info("Could not resolve newsletter short link {}: {}", url, e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
