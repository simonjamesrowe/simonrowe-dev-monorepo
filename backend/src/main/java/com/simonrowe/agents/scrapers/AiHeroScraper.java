package com.simonrowe.agents.scrapers;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads Matt Pocock's posts from AI Hero (aihero.dev).
 *
 * <p>The site has a feed, but plain {@code RSS} would flood the news page. Of the feed's
 * 195 items on 2026-10-23 only 75 were posts: the rest were 71 AI Coding Dictionary
 * entries, 27 skill reference pages, skill changelogs (each at two addresses), lists,
 * workshops and landing pages such as {@code /newsletter}. The feed carries no
 * {@code <category>}, so {@code categoryFilter} cannot separate them. Nor can
 * {@code HTML_LISTING} read {@code /posts}: its links are bare {@code /<slug>} paths,
 * which {@code isArticleLink} rejects, and the page is ordered by popularity with no dates.
 *
 * <p>What does separate them is the site's own markdown twin. Every post answers at
 * {@code /<slug>.md} with {@code text/markdown} and a frontmatter {@code type}. Dictionary
 * entries and landing pages have no twin (404), lists and workshops say so in
 * {@code type}. Keeping only {@code type: post|article} is a constraint the site enforces,
 * rather than a list of sections to skip that the next new section would slip past. The
 * twin's body is also the full article, so the summary is written from the post rather
 * than from the feed's 150-character description.
 *
 * <p>Two rules run before any page is fetched, so a scheduled run costs the feed plus a
 * request or two:
 * <ul>
 *   <li>A post lives at a single-segment path on the feed's own origin. That drops the
 *       dictionary, the {@code /skills/<slug>} copy of each changelog and the workshops.
 *   <li>Skill reference pages ({@code /skills-<name>}) are left out by name. Their twin
 *       says {@code type: post} too, so nothing the site publishes tells them apart from
 *       a blog post; the slug prefix is the only signal. They are product documentation,
 *       not writing, and there are dozens of them.
 * </ul>
 * An address already held is skipped as well, as {@link LinkRoundupScraper} does: the
 * agent's own dedup stays the authority, this only saves the fetch.
 *
 * <p>{@link #PUBLISHED_FLOOR} applies on every run, not only at backfill.
 * {@code ContentAggregationAgent.runAggregation} has no date cutoff of its own, so without
 * it the first scheduled run would ingest every post back to 2024. The feed's dates are
 * last-edited rather than first-published (an edit in September re-dated a January post),
 * so an older post edited after the floor can still arrive, dated by its edit.
 */
@Component
public class AiHeroScraper {

  private static final Logger log = LoggerFactory.getLogger(AiHeroScraper.class);

  /** Posts the feed dates before this are never ingested. */
  public static final Instant PUBLISHED_FLOOR = Instant.parse("2026-01-01T00:00:00Z");

  static final String AUTHOR = "Matt Pocock";

  private static final Set<String> POST_TYPES = Set.of("post", "article");
  private static final String SKILL_PAGE_PREFIX = "skills-";
  private static final String FRONTMATTER_FENCE = "---";

  /** The longest post on 2026-10-23 was about 60 KB of markdown. */
  static final int MAX_TWIN_BYTES = 512 * 1024;

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String USER_AGENT =
      """
      Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
      AppleWebKit/537.36 (KHTML, like Gecko) \
      Chrome/131.0.0.0 Safari/537.36""";

  /** One feed entry, before anything about it has been fetched. */
  record FeedItem(String title, String url, Instant publishedDate) {
  }

  private final AggregatedArticleRepository articleRepository;
  private final HttpClient httpClient;
  private final long delayBetweenPostsMs;

  @Autowired
  public AiHeroScraper(final AggregatedArticleRepository articleRepository) {
    this(articleRepository, 1000);
  }

  AiHeroScraper(final AggregatedArticleRepository articleRepository,
      final long delayBetweenPostsMs) {
    this.articleRepository = articleRepository;
    this.delayBetweenPostsMs = delayBetweenPostsMs;
    // Redirects are not followed: every request goes to the feed's own origin, and a
    // post that has moved is picked up at its new address from the feed.
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public List<ScrapedContent> scrape(final String feedUrl) {
    try {
      SyndFeed feed = new SyndFeedInput().build(new XmlReader(URI.create(feedUrl).toURL()));
      return collect(feedUrl, feedItems(feed));
    } catch (Exception e) {
      log.error("Failed to read AI Hero feed: {}", feedUrl, e);
      return List.of();
    }
  }

  static List<FeedItem> feedItems(final SyndFeed feed) {
    List<FeedItem> items = new ArrayList<>();
    for (SyndEntry entry : feed.getEntries()) {
      Instant published = entry.getPublishedDate() != null
          ? entry.getPublishedDate().toInstant() : null;
      items.add(new FeedItem(entry.getTitle(), entry.getLink(), published));
    }
    return items;
  }

  List<ScrapedContent> collect(final String feedUrl, final List<FeedItem> items) {
    List<ScrapedContent> results = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int notPosts = 0;
    boolean firstFetch = true;
    for (FeedItem item : items) {
      if (!isCandidate(feedUrl, item)
          || !seen.add(item.url())
          || articleRepository.existsByOriginalUrl(item.url())) {
        continue;
      }
      if (!firstFetch && !pause()) {
        break;
      }
      firstFetch = false;
      String body = postBody(fetchTwin(twinUrl(item.url())));
      if (body == null) {
        notPosts++;
        continue;
      }
      results.add(new ScrapedContent(
          item.title(), item.url(), body, item.publishedDate(), AUTHOR,
          fetchOgImage(item.url()), false));
    }
    log.info("AI Hero: {} new posts from {} feed items ({} checked and not posts)",
        results.size(), items.size(), notPosts);
    return results;
  }

  /**
   * Whether a feed item could be a post we want, decided without fetching anything.
   * An item with no date is left out: it cannot be placed against the floor.
   */
  static boolean isCandidate(final String feedUrl, final FeedItem item) {
    if (item.url() == null || item.publishedDate() == null
        || item.publishedDate().isBefore(PUBLISHED_FLOOR)) {
      return false;
    }
    try {
      URI feed = URI.create(feedUrl);
      URI link = URI.create(item.url());
      if (!Objects.equals(link.getScheme(), feed.getScheme())
          || !Objects.equals(link.getHost(), feed.getHost())
          || link.getPort() != feed.getPort()
          || link.getRawQuery() != null
          || link.getRawFragment() != null) {
        return false;
      }
      String path = link.getPath();
      if (path == null || !path.startsWith("/")) {
        return false;
      }
      String slug = path.substring(1);
      return !slug.isEmpty()
          && slug.indexOf('/') < 0
          && !slug.toLowerCase(Locale.ROOT).startsWith(SKILL_PAGE_PREFIX);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static String twinUrl(final String postUrl) {
    return postUrl + ".md";
  }

  /**
   * The post's markdown body when {@code markdown} is a twin whose frontmatter declares a
   * post, otherwise {@code null}. Only top-level {@code key: value} lines are read; the
   * site writes every scalar there as a quoted string.
   */
  static String postBody(final String markdown) {
    if (markdown == null) {
      return null;
    }
    List<String> lines = markdown.replace("\r\n", "\n").lines().toList();
    if (lines.isEmpty() || !lines.get(0).trim().equals(FRONTMATTER_FENCE)) {
      return null;
    }
    String type = null;
    int close = -1;
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.trim().equals(FRONTMATTER_FENCE)) {
        close = i;
        break;
      }
      if (line.startsWith("type:")) {
        type = unquote(line.substring("type:".length()).trim());
      }
    }
    if (close < 0 || type == null || !POST_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
      return null;
    }
    String body = String.join("\n", lines.subList(close + 1, lines.size())).trim();
    return body.isEmpty() ? null : body;
  }

  private static String unquote(final String value) {
    if (value.length() >= 2
        && (value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /**
   * The twin's text, or {@code null} for anything but a 200 {@code text/markdown}
   * response no longer than {@link #MAX_TWIN_BYTES}. A page with no twin answers 404.
   */
  String fetchTwin(final String url) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(TIMEOUT)
          .header("User-Agent", USER_AGENT)
          .header("Accept", "text/markdown")
          .GET()
          .build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (response.statusCode() != 200
            || !contentType.toLowerCase(Locale.ROOT).startsWith("text/markdown")) {
          log.debug("No markdown twin at {} ({} {})", url, response.statusCode(), contentType);
          return null;
        }
        byte[] bytes = body.readNBytes(MAX_TWIN_BYTES + 1);
        if (bytes.length > MAX_TWIN_BYTES) {
          log.warn("Markdown twin over {} bytes, skipping: {}", MAX_TWIN_BYTES, url);
          return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      log.warn("Could not fetch markdown twin {}: {}", url, e.toString());
      return null;
    }
  }

  /**
   * The post's {@code og:image}: an uploaded cover for some posts, otherwise the site's
   * generated title card. {@code null} lets the agent illustrate the post itself.
   */
  String fetchOgImage(final String url) {
    try {
      Document doc = Jsoup.connect(url)
          .timeout((int) TIMEOUT.toMillis())
          .userAgent(USER_AGENT)
          .followRedirects(false)
          .get();
      return ogImage(doc);
    } catch (Exception e) {
      log.warn("Could not read og:image for {}: {}", url, e.toString());
      return null;
    }
  }

  static String ogImage(final Document doc) {
    Element meta = doc.selectFirst("meta[property=og:image]");
    if (meta == null) {
      return null;
    }
    String image = meta.absUrl("content");
    return image.isBlank() ? null : image;
  }

  private boolean pause() {
    if (delayBetweenPostsMs <= 0) {
      return true;
    }
    try {
      Thread.sleep(delayBetweenPostsMs);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
