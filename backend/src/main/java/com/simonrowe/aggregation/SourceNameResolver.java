package com.simonrowe.aggregation;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Names the source of an ad-hoc imported article, reusing an existing content source
 * wherever the URL belongs to one.
 *
 * <p>Manual imports used to be attributed to their bare host, so every import of a new
 * site minted another filter pill on the news page — eight pills covering ten articles.
 * Matching the host against the sources we already track keeps a Tessl podcast under
 * "Tessl Blog" instead of a near-duplicate "tessl.io" chip.
 *
 * <p>Genuinely new publishers keep their host name. There is deliberately no catch-all
 * bucket: the news page already hides low-volume sources behind a "More" overflow, and a
 * bucket would only re-hide them while destroying accurate per-card attribution.
 */
@Component
public class SourceNameResolver {

  /**
   * Hosts that belong to a tracked source but do not share its {@code baseUrl} host.
   * Anthropic publishes across three domains; only claude.com is a seeded source.
   */
  private static final Map<String, String> HOST_ALIASES = Map.of(
      "anthropic.com", "claude.com",
      "code.claude.com", "claude.com");

  private static final String UNKNOWN = "Manual Import";

  private final ContentSourceRepository sourceRepository;

  public SourceNameResolver(final ContentSourceRepository sourceRepository) {
    this.sourceRepository = sourceRepository;
  }

  /**
   * The name to attribute an article at {@code url} to.
   *
   * <p>Event sources are excluded because this names articles, and a site may run both
   * (tessl.io hosts "Tessl Events" and "Tessl Blog"). Where two non-event sources still
   * share a host the host name is kept rather than guessing: an order-dependent pick
   * would mis-attribute silently, whereas an extra chip is visible and fixable.
   *
   * @param url the article URL
   * @return a tracked source's name, else the URL's host, else "Manual Import"
   */
  public String resolve(final String url) {
    String host = hostOf(url);
    if (host == null) {
      return UNKNOWN;
    }
    String canonical = HOST_ALIASES.getOrDefault(host, host);

    List<String> matches = sourceRepository.findAll().stream()
        .filter(source -> source.sourceType() != ContentSource.SourceType.EVENTS)
        .filter(source -> canonical.equals(hostOf(source.baseUrl())))
        .map(ContentSource::name)
        .distinct()
        .toList();

    return matches.size() == 1 ? matches.get(0) : host;
  }

  /**
   * The host of {@code url}, lowercased with any {@code www.} prefix removed.
   *
   * @param url any URL, possibly null or malformed
   * @return the normalised host, or {@code null} when there is not one
   */
  public static String hostOf(final String url) {
    if (url == null) {
      return null;
    }
    try {
      String host = URI.create(url).getHost();
      if (host == null) {
        return null;
      }
      return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    } catch (Exception e) {
      return null;
    }
  }
}
