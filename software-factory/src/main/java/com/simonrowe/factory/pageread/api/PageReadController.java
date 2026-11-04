package com.simonrowe.factory.pageread.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.pageread.config.PageReadProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token-protected page-read API; never routed by nginx. The backend calls it when an article
 * page refuses its scraper.
 *
 * <p>Its own controller, like every trigger endpoint: {@link FactoryTokenAuthenticator} is a
 * plain component each controller calls for itself, not a Spring Security filter, so a mapping
 * added to an unauthenticated controller would silently inherit that controller's posture.
 *
 * <p>A visitor's click can lead here (Summarise on a blocked article), unlike the writing queue,
 * and that is fine: a read costs the Pi a browser for a few seconds, not anyone's subscription.
 * The backend only asks after its own scrape failed, and summaries are rate-limited and made
 * once per article.
 */
@RestController
@RequestMapping("/api/page-reads")
public class PageReadController {

  /** Longer than any article address; a URL past it is not one. */
  static final int MAX_URL_LENGTH = 2_048;

  private final FactoryTokenAuthenticator authenticator;
  private final PageReadProperties properties;
  private final PageReadService service;

  public PageReadController(
      final FactoryTokenAuthenticator authenticator,
      final PageReadProperties properties,
      final PageReadService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.service = service;
  }

  /**
   * Reads a page, waiting for it up to {@code factory.pageread.wait-for}.
   *
   * @param token the factory trigger token
   * @param request the address
   * @return the read's state, with its result once completed
   */
  @PostMapping
  public PageReadResponse read(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody(required = false) final PageReadRequest request) {
    authenticator.authenticate(token);
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Page reads are disabled");
    }
    if (request == null || !isArticleUrl(request.url())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A read needs an absolute http(s) URL with a host");
    }
    return service.read(request.url(), properties.waitFor());
  }

  /**
   * An absolute http(s) URL with a host. The page-reader checks this again, and its egress proxy
   * is what actually keeps a page from reaching anything inside the stack.
   */
  static boolean isArticleUrl(final String url) {
    if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      return ("https".equals(scheme) || "http".equals(scheme))
          && uri.getHost() != null && !uri.getHost().isBlank();
    } catch (URISyntaxException malformed) {
      return false;
    }
  }
}
