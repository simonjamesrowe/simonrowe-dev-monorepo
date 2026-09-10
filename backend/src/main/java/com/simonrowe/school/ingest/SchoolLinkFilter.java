package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides which discovered links are worth a human decision.
 *
 * <p>Email footers are almost entirely boilerplate: the school's homepage, an unsubscribe link,
 * a privacy notice, a mail-platform tracking address. Every one of those appears on every
 * message, and offering them turns the approval queue into a page of the same four rows repeated
 * per email — at which point nobody reads it, which defeats the point of having it.
 *
 * <p>Filtering happens at record time, so a boilerplate link never becomes a row at all. Nothing
 * here fetches anything: it is a decision about what to <i>show</i>, made from the address text.
 */
@Component
public class SchoolLinkFilter {

  /**
   * Path fragments identifying the school's own newsletters on its own parent portal.
   *
   * <p>These are the one exception to "record links, never follow them". The rule exists to stop
   * the ingester making requests to arbitrary addresses that arrived in a mail body; a newsletter
   * on the school's own site is neither arbitrary nor new — the website crawl already reads that
   * host wholesale, so fetching one discloses nothing and contacts nobody the crawler is not
   * contacting anyway. Every other link, on any other host, still waits for a person.
   */
  private static final List<String> NEWSLETTER_PATHS = List.of("/newsletter", "/newsletters");

  /**
   * Hosts whose links are never interesting. Mail platforms and click trackers — following one
   * tells the sender's analytics that a message was opened, which is a side effect this feature
   * should never cause.
   */
  private static final List<String> NOISE_HOSTS = List.of(
      "mailchi.mp", "list-manage.com", "sendgrid.net", "mailgun.org", "constantcontact.com",
      "doubleclick.net", "googletagmanager.com", "google-analytics.com", "facebook.com",
      "twitter.com", "x.com", "instagram.com", "linkedin.com");

  /**
   * Path or query fragments that mark a link as list-management or site furniture rather than
   * content.
   *
   * <p>{@code privacy} and {@code cookie} are deliberately broader than the
   * {@code privacy-policy}/{@code cookie-policy} they replaced: the school's own page is at
   * {@code /privacy-cookies}, which neither of the hyphenated forms matched, so the crawl
   * queued the cookie notice from every page that links to it — which is every page.
   */
  private static final List<String> NOISE_FRAGMENTS = List.of(
      "unsubscribe", "/preferences", "optout", "opt-out", "manage-subscription",
      "privacy", "cookie", "/accessibility");

  /**
   * Whether a link should be offered for a decision.
   *
   * @param url the absolute URL found in a message
   * @return true when it is worth showing
   */
  private final SchoolProperties properties;

  public SchoolLinkFilter(final SchoolProperties properties) {
    this.properties = properties;
  }

  /**
   * Whether a link may be fetched during ingest without waiting for a human.
   *
   * <p>Deliberately narrow: the school's own host, and a newsletter path on it. Widening this to
   * the whole school domain would be defensible on the same reasoning, but the parent portal also
   * serves per-family pages and a blanket rule would start fetching those.
   *
   * @param url the absolute URL found in a message
   * @return true when ingest may follow it immediately
   */
  public boolean isAutoFetchable(final String url) {
    if (url == null || url.isBlank() || !isWorthOffering(url)) {
      return false;
    }
    final URI uri = parse(url);
    if (uri == null || !isOnSchoolHost(uri)) {
      return false;
    }
    final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
    return NEWSLETTER_PATHS.stream().anyMatch(path::contains);
  }

  /**
   * Whether a link found on a crawled school web page may be crawled in turn.
   *
   * <p>This is a different question from {@link #isAutoFetchable}, which governs links arriving
   * in <b>email</b> — where the sender is hostile-by-assumption and the rule is deliberately
   * "the school's own newsletter path and nothing else". Here the link was found on a page the
   * crawler was already reading, on a host it is already reading wholesale, so the bar is
   * "anywhere on the school's own site" instead.
   *
   * <p>Same host is the whole of the security rule, and it is not decoration: the year-group
   * home-learning pages link out to {@code primaryhomeworkhelp.co.uk}, {@code natgeokids.com},
   * {@code dkfindout.com} and {@code kids.britannica.com} as suggested research for the
   * children. Following those would turn a school-information crawler into a general web
   * spider, and put third-party content into a corpus that answers as the school.
   *
   * <p>What is excluded beyond that is noise rather than danger, and it is shared with
   * {@link #isWorthOffering} so there is one list rather than two that drift: cookie and privacy
   * notices, the accessibility statement, the bare homepage. The calendar is excluded because
   * its date-parameterised URL space is effectively infinite — the same reason
   * {@code SchoolWebsiteCrawler} drops it from the sitemap. PDFs are excluded because they are
   * not pages: {@code SchoolIngestService} already discovers and extracts those separately, and
   * putting one on the page queue would try to read a binary as HTML.
   *
   * @param url the absolute URL found on a crawled page
   * @return true when the crawler may fetch it as a page
   */
  public boolean isCrawlableWebsitePage(final String url) {
    if (url == null || url.isBlank() || !isWorthOffering(url)) {
      return false;
    }
    final URI uri = parse(url);
    if (uri == null || !isOnSchoolHost(uri)) {
      return false;
    }
    final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      return false;
    }
    final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
    return !path.contains("/calendar") && !path.endsWith(".pdf");
  }

  private URI parse(final String url) {
    try {
      return URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Whether a URI is on the school's own host.
   *
   * <p>The configured host is compared with any leading {@code www.} removed, so
   * {@code kilmorieschool.co.uk} and {@code www.kilmorieschool.co.uk} are the same site and a
   * subdomain of it also qualifies. Note the suffix test is anchored on a dot —
   * {@code notkilmorieschool.co.uk} must not match.
   */
  private boolean isOnSchoolHost(final URI uri) {
    final URI school = parse(properties.websiteBaseUrl());
    if (school == null || uri.getHost() == null || school.getHost() == null) {
      return false;
    }
    final String host = uri.getHost().toLowerCase(Locale.ROOT);
    final String schoolHost = school.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    return host.equals(schoolHost) || host.endsWith("." + schoolHost);
  }

  public boolean isWorthOffering(final String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    final URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    final String path = uri.getPath() == null ? "" : uri.getPath();
    final String lower = url.toLowerCase(Locale.ROOT);

    if (NOISE_HOSTS.stream().anyMatch(host::endsWith)) {
      return false;
    }
    if (NOISE_FRAGMENTS.stream().anyMatch(lower::contains)) {
      return false;
    }
    // A bare homepage — the single most common footer link there is, and there is nothing on it
    // that the website crawl is not already reading. Deeper links into the same site are kept:
    // those point at a specific letter or page and are exactly what this queue is for.
    return !(path.isEmpty() || "/".equals(path)) || uri.getQuery() != null;
  }
}
