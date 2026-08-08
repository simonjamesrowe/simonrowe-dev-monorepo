# AI4JVM Ingest Source and News Source-Pill Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest `ai4jvm.com`'s curated JVM-AI headlines as news articles, and clean up the source filter pills on `/news-events` so manual imports stop minting a new pill each time.

**Architecture:** A sixth scrape strategy, `LINK_ROUNDUP`, reads AI4JVM's hand-maintained `.news-bar` link list and then *follows* each external link through the existing article scraper, falling back to AI4JVM's own curated title and summary when a target blocks us. Separately, a shared `SourceNameResolver` maps a URL's host onto an existing `ContentSource` name so both the manual-import path and a one-off Mongock cleanup attribute articles consistently, and the news page's filter row splits into view modes and volume-sorted source pills with a `More` overflow.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data MongoDB, jsoup, Mongock, JUnit 5 + Mockito + AssertJ; TypeScript, React 19, Vite, Vitest + Testing Library.

## Global Constraints

- Backend follows the Google Java Style Guide, enforced by Checkstyle via `backend/config/checkstyle/google_checks.xml`: **100-character lines, 2-space indent**. A style violation fails the build.
- Every data change ships as a **Mongock change unit**, never an ad-hoc script.
- New change units take the next free order after `017`. Use `018` and `019`.
- A backfill inside a change unit must be wrapped in a `try/catch` that logs and swallows. A failed pre-population must never break application boot.
- All new backend classes live under `com.simonrowe.*` and mirror the package layout of the class they sit beside.
- The `AI4JVM` source name is the string `"AI4JVM"` exactly — the unique index on `content_sources.name` makes it a one-shot decision.
- Do not relax `SitemapHtmlScraper.isArticleLink`. Its same-host rule is shared by every `HTML_LISTING` source.
- Backend gate: `cd backend && ../gradlew test` (runs Checkstyle). Frontend gate: `cd frontend && npm test`.

---

### Task 1: `LINK_ROUNDUP` strategy and curated-link extraction

Adds the strategy and a scraper that parses AI4JVM's link list into items built from AI4JVM's own curated title and summary. No link-following yet — that is Task 2. This task alone produces a working (if shallow) source.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/aggregation/ContentSource.java:28-34` (the `ScrapeStrategy` enum)
- Create: `backend/src/main/java/com/simonrowe/agents/scrapers/LinkRoundupScraper.java`
- Modify: `backend/src/main/java/com/simonrowe/agents/scrapers/ScraperFactory.java`
- Modify: `backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java:246` (widen `normalizeUrl` visibility)
- Test: `backend/src/test/java/com/simonrowe/agents/scrapers/LinkRoundupScraperTest.java`
- Test: `backend/src/test/java/com/simonrowe/agents/scrapers/ScraperFactoryTest.java`

**Interfaces:**
- Consumes: `ScrapedContent` (existing record, `com.simonrowe.agents.scrapers`), `ContentSource.ScrapeStrategy` (existing enum).
- Produces:
  - `ContentSource.ScrapeStrategy.LINK_ROUNDUP`
  - `LinkRoundupScraper.scrape(String listingUrl) : List<ScrapedContent>`
  - `LinkRoundupScraper.extractLinks(Document doc) : List<LinkRoundupScraper.RoundupLink>` (package-private, the test seam)
  - `LinkRoundupScraper.RoundupLink(String title, String url, String summary)` (package-private nested record)
  - `LinkRoundupScraper.MAX_ITEMS = 40` (package-private constant)
  - `SitemapHtmlScraper.normalizeUrl(String url) : String` becomes package-private `static` so Task 2 can reuse it

---

- [ ] **Step 1: Widen `SitemapHtmlScraper.normalizeUrl` to package-private**

Task 2 must normalise roundup URLs with *exactly* the same rules the article scraper applies to the URLs it returns, or dedup will miss on a trailing slash. Do it now so the two tasks cannot drift.

In `backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java`, change line 246 from:

```java
  private static String normalizeUrl(String url) {
```

to:

```java
  // Package-private so LinkRoundupScraper can normalise roundup target URLs with
  // exactly the rules scrapeArticlePage applies to the URLs it returns. If the two
  // differed by so much as a trailing slash, dedup on originalUrl would miss.
  static String normalizeUrl(String url) {
```

Change nothing else in that method.

- [ ] **Step 2: Add the `LINK_ROUNDUP` enum constant**

In `backend/src/main/java/com/simonrowe/aggregation/ContentSource.java`, extend the `ScrapeStrategy` enum:

```java
  public enum ScrapeStrategy {
    RSS,
    SITEMAP_HTML,
    HTML,
    HTML_LISTING,
    LUMA,
    LINK_ROUNDUP
  }
```

- [ ] **Step 3: Write the failing extraction tests**

Create `backend/src/test/java/com/simonrowe/agents/scrapers/LinkRoundupScraperTest.java`:

```java
package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class LinkRoundupScraperTest {

  private final LinkRoundupScraper scraper = new LinkRoundupScraper();

  @Test
  void extractLinks_readsTitleUrlAndSummaryFromNewsBarItems() {
    Document doc = Jsoup.parse("""
        <html><body>
        <div class="news-bar"><ul>
          <li><a href="https://www.infoq.com/news/2026/08/java-news-roundup-jul27-2026/">\
        InfoQ Java News Roundup</a> &mdash; weekly Java ecosystem roundup covering \
        two new JDK 28 JEPs</li>
          <li><a href="https://foojay.io/today/spring-boot-fraud/">\
        How to Create a Spring Boot Fraud Scoring Service</a> &mdash; builds a \
        production credit-card fraud detection REST API</li>
        </ul></div>
        </body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(2);
    assertThat(links.get(0).title()).isEqualTo("InfoQ Java News Roundup");
    assertThat(links.get(0).url())
        .isEqualTo("https://www.infoq.com/news/2026/08/java-news-roundup-jul27-2026");
    assertThat(links.get(0).summary())
        .isEqualTo("weekly Java ecosystem roundup covering two new JDK 28 JEPs");
    assertThat(links.get(1).title())
        .isEqualTo("How to Create a Spring Boot Fraud Scoring Service");
  }

  @Test
  void extractLinks_keepsCrossHostLinks() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li><a href="https://github.com/embabel/embabel-agent/releases/tag/v1.0.0">\
        Embabel 1.0.0 Reaches GA</a> &mdash; the JVM agent framework's first stable release</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).url())
        .isEqualTo("https://github.com/embabel/embabel-agent/releases/tag/v1.0.0");
  }

  @Test
  void extractLinks_ignoresLinksOutsideTheNewsBar() {
    Document doc = Jsoup.parse("""
        <html><body>
        <nav><a href="https://ai4jvm.com/#frameworks">Agent Frameworks</a></nav>
        <div class="news-bar"><ul>
          <li><a href="https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA">\
        Spring AI 2.0.0 GA Available Now</a> &mdash; the release announcement</li>
        </ul></div>
        <section id="frameworks"><ul>
          <li><a href="https://github.com/langchain4j/langchain4j">LangChain4j</a> &mdash; a library</li>
        </ul></section>
        </body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).title()).isEqualTo("Spring AI 2.0.0 GA Available Now");
  }

  @Test
  void extractLinks_fallsBackToNewsSectionWhenNewsBarClassIsAbsent() {
    Document doc = Jsoup.parse("""
        <html><body><section id="news"><ul>
          <li><a href="https://quarkus.io/blog/introducing-voting-pattern/">\
        Parallel Voting and Adaptive Model Selection in Quarkus</a> &mdash; a new pattern</li>
        </ul></section></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).title())
        .isEqualTo("Parallel Voting and Adaptive Model Selection in Quarkus");
  }

  @Test
  void extractLinks_skipsItemsWithoutExactlyOneAnchor() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li>No link at all, just prose</li>
          <li><a href="https://a.example/one">One</a> and <a href="https://b.example/two">Two</a></li>
          <li><a href="https://good.example/post">Good Item Title</a> &mdash; the only valid one</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).title()).isEqualTo("Good Item Title");
  }

  @Test
  void extractLinks_deduplicatesRepeatedTargetUrls() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li><a href="https://spring.io/blog/post">First Framing</a> &mdash; summary one</li>
          <li><a href="https://spring.io/blog/post/">Second Framing</a> &mdash; summary two</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).title()).isEqualTo("First Framing");
  }

  @Test
  void extractLinks_skipsNonHttpAnchors() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li><a href="mailto:hello@ai4jvm.com">Email us</a> &mdash; not an article</li>
          <li><a href="https://real.example/post">Real Article</a> &mdash; is an article</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).title()).isEqualTo("Real Article");
  }

  @Test
  void extractLinks_capsAtMaxItems() {
    StringBuilder html = new StringBuilder("<html><body><div class=\"news-bar\"><ul>");
    for (int i = 0; i < LinkRoundupScraper.MAX_ITEMS + 5; i++) {
      html.append("<li><a href=\"https://example.com/post-").append(i)
          .append("\">Title ").append(i).append("</a> &mdash; summary ").append(i)
          .append("</li>");
    }
    html.append("</ul></div></body></html>");

    List<LinkRoundupScraper.RoundupLink> links =
        scraper.extractLinks(Jsoup.parse(html.toString(), "https://ai4jvm.com"));

    assertThat(links).hasSize(LinkRoundupScraper.MAX_ITEMS);
  }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd backend && ../gradlew test --tests '*LinkRoundupScraperTest'`
Expected: FAIL — compilation error, `LinkRoundupScraper` does not exist.

- [ ] **Step 5: Implement `LinkRoundupScraper`**

Create `backend/src/main/java/com/simonrowe/agents/scrapers/LinkRoundupScraper.java`:

```java
package com.simonrowe.agents.scrapers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scrapes a curated link roundup: a page whose value is the list of <em>external</em>
 * articles it points at, rather than any article of its own.
 *
 * <p>Built for ai4jvm.com, whose news lives in a hand-maintained {@code div.news-bar}
 * holding one {@code li} per headline: a single anchor to another site, an em-dash, and
 * a hand-written summary. There is no feed, and the sitemap lists only the homepage.
 *
 * <p>The existing {@code HTML_LISTING} strategy cannot be used: {@code isArticleLink}
 * requires each link's host to equal the listing page's host, so every headline here is
 * rejected and the scrape returns zero items with no error. That rule is load-bearing for
 * the other listing sources and is deliberately left alone.
 */
@Component
public class LinkRoundupScraper {

  private static final Logger log = LoggerFactory.getLogger(LinkRoundupScraper.class);

  private static final int TIMEOUT_MS = 15000;
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/131.0.0.0 Safari/537.36";

  private static final String PRIMARY_SELECTOR = ".news-bar li";
  private static final String FALLBACK_SELECTOR = "#news li";

  /**
   * Deliberately above {@code SitemapHtmlScraper.MAX_ARTICLES} (20). The roundup holds 29
   * items ordered newest-first, and dedup happens after scraping — so with a cap of 20 the
   * tail would be re-skipped on every run and never ingested at all.
   */
  static final int MAX_ITEMS = 40;

  /** One curated headline: where it points, and how the curator framed it. */
  record RoundupLink(String title, String url, String summary) {
  }

  public List<ScrapedContent> scrape(final String listingUrl) {
    List<RoundupLink> links = fetchLinks(listingUrl);
    return links.stream().map(LinkRoundupScraper::toCuratedContent).toList();
  }

  List<RoundupLink> fetchLinks(final String listingUrl) {
    try {
      Document doc = Jsoup.connect(listingUrl)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();
      List<RoundupLink> links = extractLinks(doc);
      log.info("Found {} roundup links on {}", links.size(), listingUrl);
      return links;
    } catch (Exception e) {
      log.error("Failed to scrape link roundup: {}", listingUrl, e);
      return List.of();
    }
  }

  /**
   * An item with no anchor, or with more than one, is skipped rather than guessed at:
   * the roundup's own format is exactly one link per headline, so anything else is
   * either prose or a structure this scraper does not understand.
   */
  List<RoundupLink> extractLinks(final Document doc) {
    Elements items = doc.select(PRIMARY_SELECTOR);
    if (items.isEmpty()) {
      items = doc.select(FALLBACK_SELECTOR);
    }

    List<RoundupLink> links = new ArrayList<>();
    Set<String> seenUrls = new LinkedHashSet<>();
    for (Element item : items) {
      if (links.size() >= MAX_ITEMS) {
        break;
      }
      Elements anchors = item.select("a[href]");
      if (anchors.size() != 1) {
        continue;
      }
      Element anchor = anchors.first();
      String rawUrl = anchor.absUrl("href");
      if (!rawUrl.startsWith("http")) {
        continue;
      }
      // Normalised with the article scraper's own rules so the URL recorded here
      // matches the one it would return, and dedup on originalUrl lines up.
      String url = SitemapHtmlScraper.normalizeUrl(rawUrl);
      String title = anchor.text().trim();
      if (title.isEmpty() || !seenUrls.add(url)) {
        continue;
      }
      links.add(new RoundupLink(title, url, extractSummary(item, title)));
    }
    return links;
  }

  /** The item's text with the anchor text and the separating em-dash removed. */
  private String extractSummary(final Element item, final String title) {
    String text = item.text().trim();
    if (text.startsWith(title)) {
      text = text.substring(title.length());
    }
    return text.replaceFirst("^[\\s\\u2013\\u2014-]+", "").trim();
  }

  private static ScrapedContent toCuratedContent(final RoundupLink link) {
    return new ScrapedContent(
        link.title(), link.url(), link.summary(), null, null, null, false);
  }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ../gradlew test --tests '*LinkRoundupScraperTest'`
Expected: PASS, 8 tests.

- [ ] **Step 7: Write the failing factory test**

Add to `backend/src/test/java/com/simonrowe/agents/scrapers/ScraperFactoryTest.java` — a `@Mock private LinkRoundupScraper linkRoundupScraper;` field alongside the existing mocks, and this test. Also add `linkRoundupScraper` to the `verifyNoInteractions(...)` argument list of all five existing tests.

```java
  @Test
  void scrape_delegatesToLinkRoundupScraperForLinkRoundupStrategy() {
    ContentSource source = contentSource(
        null, "https://ai4jvm.com", null, SourceType.NEWS, ScrapeStrategy.LINK_ROUNDUP);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Embabel 1.0.0 Reaches GA",
            "https://github.com/embabel/embabel-agent/releases/tag/v1.0.0",
            "First stable release", null, null, null, false));
    when(linkRoundupScraper.scrape("https://ai4jvm.com")).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(linkRoundupScraper).scrape("https://ai4jvm.com");
    verifyNoInteractions(rssScraper, sitemapHtmlScraper, lumaApiScraper);
  }
```

- [ ] **Step 8: Run it to verify it fails**

Run: `cd backend && ../gradlew test --tests '*ScraperFactoryTest'`
Expected: FAIL — compilation error, `ScraperFactory` has no `LinkRoundupScraper` constructor parameter.

- [ ] **Step 9: Wire the factory**

In `backend/src/main/java/com/simonrowe/agents/scrapers/ScraperFactory.java`, add the field, the constructor parameter and the switch arm:

```java
  private final LinkRoundupScraper linkRoundupScraper;

  public ScraperFactory(RssScraper rssScraper,
      SitemapHtmlScraper sitemapHtmlScraper,
      LumaApiScraper lumaApiScraper,
      LinkRoundupScraper linkRoundupScraper) {
    this.rssScraper = rssScraper;
    this.sitemapHtmlScraper = sitemapHtmlScraper;
    this.lumaApiScraper = lumaApiScraper;
    this.linkRoundupScraper = linkRoundupScraper;
  }
```

and inside `scrape`:

```java
      case LINK_ROUNDUP -> linkRoundupScraper.scrape(source.baseUrl());
```

- [ ] **Step 10: Run the scraper tests to verify they pass**

Run: `cd backend && ../gradlew test --tests '*ScraperFactoryTest' --tests '*LinkRoundupScraperTest' --tests '*SitemapHtmlScraperTest'`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/scrapers/LinkRoundupScraper.java \
        backend/src/main/java/com/simonrowe/agents/scrapers/ScraperFactory.java \
        backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java \
        backend/src/main/java/com/simonrowe/aggregation/ContentSource.java \
        backend/src/test/java/com/simonrowe/agents/scrapers/LinkRoundupScraperTest.java \
        backend/src/test/java/com/simonrowe/agents/scrapers/ScraperFactoryTest.java
git commit -m "feat: add LINK_ROUNDUP scrape strategy for curated link indexes"
```

---

### Task 2: Follow roundup links to the real articles

Turns the shallow curated items into real articles by fetching each target page, with the curated text as fallback. Adds a pre-fetch dedup check so scheduled runs stop re-requesting articles we already hold.

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/agents/scrapers/LinkRoundupScraper.java`
- Test: `backend/src/test/java/com/simonrowe/agents/scrapers/LinkRoundupScraperTest.java`

**Interfaces:**
- Consumes: `SitemapHtmlScraper.scrapeArticlePagePublic(String url) : ScrapedContent` (returns `null` on failure), `AggregatedArticleRepository.existsByOriginalUrl(String) : boolean`, and everything Task 1 produced.
- Produces: `LinkRoundupScraper(SitemapHtmlScraper, AggregatedArticleRepository)` constructor — the no-arg construction used in Task 1's tests is replaced.

---

- [ ] **Step 1: Write the failing link-following tests**

In `LinkRoundupScraperTest.java`, replace the field declaration

```java
  private final LinkRoundupScraper scraper = new LinkRoundupScraper();
```

with a Mockito setup. Add these imports: `org.junit.jupiter.api.extension.ExtendWith`, `org.mockito.InjectMocks`, `org.mockito.Mock`, `org.mockito.junit.jupiter.MockitoExtension`, `com.simonrowe.aggregation.AggregatedArticleRepository`, and the static `org.mockito.Mockito.when` / `verify` / `never` / `verifyNoInteractions`. Annotate the class `@ExtendWith(MockitoExtension.class)` and declare:

```java
  @Mock private SitemapHtmlScraper htmlScraper;
  @Mock private AggregatedArticleRepository articleRepository;
  @InjectMocks private LinkRoundupScraper scraper;
```

The eight `extractLinks` tests from Task 1 are unchanged — they never touch the mocks.

Then add these four tests at the **top level** of the class (not in a `@Nested` class — nested classes and `@InjectMocks` on an outer field interact badly). `scrape` fetches over the network, so these drive the seams below it, `follow` and `toContent`:

```java
  private static final LinkRoundupScraper.RoundupLink EMBABEL =
      new LinkRoundupScraper.RoundupLink(
          "Embabel 1.0.0 Reaches GA",
          "https://github.com/embabel/embabel-agent/releases/tag/v1.0.0",
          "the JVM agent framework's first stable release");

  @Test
  void toContent_usesTheTargetPageWhenItScrapesSuccessfully() {
    ScrapedContent detail = new ScrapedContent(
        "Release v1.0.0 · embabel/embabel-agent",
        "https://github.com/embabel/embabel-agent/releases/tag/v1.0.0",
        "Full release notes body text",
        Instant.parse("2026-07-20T00:00:00Z"), "embabel",
        "https://opengraph.githubassets.com/card.png", false);
    when(htmlScraper.scrapeArticlePagePublic(EMBABEL.url())).thenReturn(detail);

    assertThat(scraper.toContent(EMBABEL)).isSameAs(detail);
  }

  @Test
  void toContent_fallsBackToTheCuratedTextWhenTheTargetBlocksUs() {
    when(htmlScraper.scrapeArticlePagePublic(EMBABEL.url())).thenReturn(null);

    ScrapedContent result = scraper.toContent(EMBABEL);

    assertThat(result.title()).isEqualTo("Embabel 1.0.0 Reaches GA");
    assertThat(result.url()).isEqualTo(EMBABEL.url());
    assertThat(result.content())
        .isEqualTo("the JVM agent framework's first stable release");
    assertThat(result.publishedDate()).isNull();
    assertThat(result.imageUrl()).isNull();
    assertThat(result.isEvent()).isFalse();
  }

  @Test
  void follow_skipsTargetsAlreadyHeldWithoutFetchingThem() {
    LinkRoundupScraper.RoundupLink fresh = new LinkRoundupScraper.RoundupLink(
        "Koog 1.0 Is Out", "https://blog.jetbrains.com/ai/koog-1-0", "stable core");
    when(articleRepository.existsByOriginalUrl(EMBABEL.url())).thenReturn(true);
    when(articleRepository.existsByOriginalUrl(fresh.url())).thenReturn(false);
    when(htmlScraper.scrapeArticlePagePublic(fresh.url())).thenReturn(null);

    List<ScrapedContent> results = scraper.follow(List.of(EMBABEL, fresh));

    assertThat(results).hasSize(1);
    assertThat(results.get(0).title()).isEqualTo("Koog 1.0 Is Out");
    verify(htmlScraper, never()).scrapeArticlePagePublic(EMBABEL.url());
  }

  @Test
  void follow_returnsEmptyWhenEveryTargetIsAlreadyHeld() {
    when(articleRepository.existsByOriginalUrl(EMBABEL.url())).thenReturn(true);

    assertThat(scraper.follow(List.of(EMBABEL))).isEmpty();

    verifyNoInteractions(htmlScraper);
  }
```

Add the import `java.time.Instant`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ../gradlew test --tests '*LinkRoundupScraperTest'`
Expected: FAIL — compilation error, no `toContent`/`follow` methods and no matching constructor.

- [ ] **Step 3: Implement following**

In `LinkRoundupScraper.java`, add the imports `com.simonrowe.aggregation.AggregatedArticleRepository`, then add the fields and constructor above `scrape`:

```java
  private static final long DELAY_BETWEEN_REQUESTS_MS = 1000;

  private final SitemapHtmlScraper htmlScraper;
  private final AggregatedArticleRepository articleRepository;

  /**
   * Takes a repository, unlike every other scraper. Without a pre-fetch dedup check the
   * 40-item cap means every scheduled run would re-request all forty linked pages across
   * a dozen third-party sites purely to discover we already hold them. The agent's own
   * dedup remains the authority; this is an optimisation only.
   */
  public LinkRoundupScraper(
      final SitemapHtmlScraper htmlScraper,
      final AggregatedArticleRepository articleRepository) {
    this.htmlScraper = htmlScraper;
    this.articleRepository = articleRepository;
  }
```

Replace the body of `scrape` with:

```java
  public List<ScrapedContent> scrape(final String listingUrl) {
    return follow(fetchLinks(listingUrl));
  }

  List<ScrapedContent> follow(final List<RoundupLink> links) {
    List<ScrapedContent> results = new ArrayList<>();
    boolean firstFetch = true;
    for (RoundupLink link : links) {
      if (articleRepository.existsByOriginalUrl(link.url())) {
        continue;
      }
      if (!firstFetch) {
        try {
          Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      firstFetch = false;
      results.add(toContent(link));
    }
    log.info("Followed {} of {} roundup links", results.size(), links.size());
    return results;
  }

  /**
   * The target page's own article where it can be scraped, otherwise an item built from
   * the curator's title and summary. Those summaries run 150-250 characters, comfortably
   * over the 50-character floor below which classification is skipped, so a fallback item
   * is still summarised and indexed — it simply has no image or date of its own.
   */
  ScrapedContent toContent(final RoundupLink link) {
    ScrapedContent detail = htmlScraper.scrapeArticlePagePublic(link.url());
    if (detail != null) {
      return detail;
    }
    log.info("Target page unavailable, using curated text for: {}", link.url());
    return new ScrapedContent(
        link.title(), link.url(), link.summary(), null, null, null, false);
  }
```

Delete the now-unused `private static ScrapedContent toCuratedContent(...)` method.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests '*LinkRoundupScraperTest' --tests '*ScraperFactoryTest'`
Expected: PASS, 12 tests in `LinkRoundupScraperTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/agents/scrapers/LinkRoundupScraper.java \
        backend/src/test/java/com/simonrowe/agents/scrapers/LinkRoundupScraperTest.java
git commit -m "feat: follow roundup links to their source articles"
```

---

### Task 3: Seed and backfill the AI4JVM source

**Files:**
- Create: `backend/src/main/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4Jvm.java`
- Test: `backend/src/test/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4JvmTest.java`

**Interfaces:**
- Consumes: `ContentSourceRepository.findByName(String) : Optional<ContentSource>`, `ContentSourceRepository.save(ContentSource) : ContentSource`, `ContentAggregationAgent.backfillSource(ContentSource, Instant) : void`, `ContentSource.ScrapeStrategy.LINK_ROUNDUP` (Task 1).
- Produces: an active `content_sources` document named `"AI4JVM"` with `baseUrl = "https://ai4jvm.com"`.

---

- [ ] **Step 1: Write the failing change-unit test**

Create `backend/src/test/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4JvmTest.java`:

```java
package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V018SeedAndBackfillAi4JvmTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V018SeedAndBackfillAi4Jvm changeUnit = new V018SeedAndBackfillAi4Jvm();

  private static ContentSource ai4jvm() {
    return new ContentSource(
        "ai4jvm-1", "AI4JVM", "https://ai4jvm.com", null, null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.LINK_ROUNDUP, true, null, null);
  }

  @Test
  void seedsSourceAndBackfillsWhenAbsent() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    ContentSource saved = ai4jvm();
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("AI4JVM");
    assertThat(captor.getValue().baseUrl()).isEqualTo("https://ai4jvm.com");
    assertThat(captor.getValue().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.LINK_ROUNDUP);
    assertThat(captor.getValue().sourceType()).isEqualTo(ContentSource.SourceType.NEWS);
    assertThat(captor.getValue().active()).isTrue();
    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void backfillsOverA120DayWindow() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(ai4jvm());
    Instant before = Instant.now();

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(aggregationAgent).backfillSource(any(), since.capture());
    // The curated list spans months; a 30-day window would discard most of it on the
    // one run that can ever see it, since the page only ever grows at the top.
    assertThat(since.getValue())
        .isBetween(before.minus(121, ChronoUnit.DAYS), before.minus(119, ChronoUnit.DAYS));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.of(ai4jvm()));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    ContentSource saved = ai4jvm();
    when(sourceRepository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("LLM unavailable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate — a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void rollbackDeletesTheSource() {
    ContentSource existing = ai4jvm();
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.of(existing));

    changeUnit.rollback(sourceRepository);

    verify(sourceRepository).delete(existing);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ../gradlew test --tests '*V018*'`
Expected: FAIL — compilation error, `V018SeedAndBackfillAi4Jvm` does not exist.

- [ ] **Step 3: Implement the change unit**

Create `backend/src/main/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4Jvm.java`:

```java
package com.simonrowe.migration.changeunits;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds ai4jvm.com as a content source and pre-populates its curated headlines.
 *
 * <p>AI4JVM is an index rather than a publisher: its news block links out to InfoQ,
 * foojay, javapro, GitHub releases and others, so it is scraped with the
 * {@code LINK_ROUNDUP} strategy, which follows each link to the real article. Every
 * ingested item is attributed to {@code AI4JVM} — the card's outbound link still goes to
 * the original publisher, and per-publisher source names would add a dozen one-off
 * filter pills to the news page.
 *
 * <p>The backfill window is 120 days rather than the 30 used for Dan Vega: the curated
 * list spans several months and the page only grows at the top, so anything cut here is
 * never offered again. The backfill is wrapped so that any failure (LLM, network, Kafka)
 * is logged but never rethrown — a failed pre-population must not block application boot.
 * Seeding is idempotent via the {@code findByName} guard, and article dedup on
 * {@code originalUrl} makes the backfill safe to re-run.
 */
@ChangeUnit(id = "seed-and-backfill-ai4jvm", order = "018", author = "simonrowe")
public class V018SeedAndBackfillAi4Jvm {

  private static final Logger log = LoggerFactory.getLogger(V018SeedAndBackfillAi4Jvm.class);

  private static final String SOURCE_NAME = "AI4JVM";
  private static final long BACKFILL_WINDOW_DAYS = 120;

  @Execution
  public void execution(
      final ContentSourceRepository contentSourceRepository,
      final ContentAggregationAgent aggregationAgent) {
    if (contentSourceRepository.findByName(SOURCE_NAME).isPresent()) {
      log.info("AI4JVM source already present; skipping seed and backfill");
      return;
    }

    ContentSource saved = contentSourceRepository.save(new ContentSource(
        null,
        SOURCE_NAME,
        "https://ai4jvm.com",
        null,
        null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.LINK_ROUNDUP,
        true,
        null,
        null));
    log.info("Seeded AI4JVM content source");

    Instant since = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS);
    try {
      aggregationAgent.backfillSource(saved, since);
    } catch (Exception e) {
      log.error("AI4JVM backfill failed; leaving source for scheduled run", e);
    }
  }

  @RollbackExecution
  public void rollback(final ContentSourceRepository contentSourceRepository) {
    contentSourceRepository.findByName(SOURCE_NAME)
        .ifPresent(contentSourceRepository::delete);
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests '*V018*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4Jvm.java \
        backend/src/test/java/com/simonrowe/migration/changeunits/V018SeedAndBackfillAi4JvmTest.java
git commit -m "feat: seed and backfill the AI4JVM content source"
```

---

### Task 4: Resolve manual-import source names against known sources

Stops `importFromUrl` minting a fresh pill for every new host. This is the fix that makes Task 5's data cleanup durable.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/aggregation/SourceNameResolver.java`
- Modify: `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java:113` and `:146-153`
- Test: `backend/src/test/java/com/simonrowe/aggregation/SourceNameResolverTest.java`

**Interfaces:**
- Consumes: `ContentSourceRepository.findAll() : List<ContentSource>` (inherited from `MongoRepository`), `ContentSource.baseUrl()`, `ContentSource.name()`, `ContentSource.sourceType()`.
- Produces:
  - `SourceNameResolver.resolve(String url) : String` — a known source's name, else the bare host, else `"Manual Import"`.
  - `SourceNameResolver.hostOf(String url) : String` — public static, lowercased and `www.`-stripped, `null` when unparseable. Task 5 uses it.

---

- [ ] **Step 1: Write the failing resolver test**

Create `backend/src/test/java/com/simonrowe/aggregation/SourceNameResolverTest.java`:

```java
package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceNameResolverTest {

  @Mock private ContentSourceRepository sourceRepository;

  @InjectMocks private SourceNameResolver resolver;

  private static ContentSource source(
      String name, String baseUrl, ContentSource.SourceType type) {
    return new ContentSource(
        name.toLowerCase(), name, baseUrl, null, null, type,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
  }

  @Test
  void resolve_reusesTheNameOfASourceOnTheSameHost() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Tessl Blog", "https://tessl.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
  }

  @Test
  void resolve_ignoresAWwwPrefixOnEitherSide() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Dan Vega", "https://www.danvega.dev/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://danvega.dev/blog/some-post")).isEqualTo("Dan Vega");
  }

  @Test
  void resolve_mapsAliasedHostsOntoTheirCanonicalSource() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Claude Blog", "https://claude.com/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://www.anthropic.com/news/claude-opus-4-7"))
        .isEqualTo("Claude Blog");
    assertThat(resolver.resolve("https://code.claude.com/docs/en/routines"))
        .isEqualTo("Claude Blog");
  }

  @Test
  void resolve_fallsBackToTheHostWhenNoSourceMatches() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Spring Blog", "https://spring.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://blog.cloudflare.com/ai-code-review"))
        .isEqualTo("blog.cloudflare.com");
  }

  @Test
  void resolve_ignoresEventSourcesWhenNamingAnArticle() {
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Tessl Events", "https://tessl.io/events", ContentSource.SourceType.EVENTS),
        source("Tessl Blog", "https://tessl.io/blog", ContentSource.SourceType.BLOG)));

    assertThat(resolver.resolve("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
  }

  @Test
  void resolve_fallsBackToTheHostWhenTwoSourcesShareIt() {
    // Ambiguity must not become an order-dependent guess: keeping the host is wrong
    // in a visible, fixable way, whereas picking the wrong source name is silent.
    when(sourceRepository.findAll()).thenReturn(List.of(
        source("Foo One", "https://shared.example/a", ContentSource.SourceType.BLOG),
        source("Foo Two", "https://shared.example/b", ContentSource.SourceType.NEWS)));

    assertThat(resolver.resolve("https://shared.example/post")).isEqualTo("shared.example");
  }

  @Test
  void resolve_returnsManualImportForAnUnparseableUrl() {
    assertThat(resolver.resolve("not a url at all")).isEqualTo("Manual Import");
  }

  @Test
  void hostOf_lowercasesAndStripsWww() {
    assertThat(SourceNameResolver.hostOf("https://WWW.Example.COM/x")).isEqualTo("example.com");
    assertThat(SourceNameResolver.hostOf("nonsense")).isNull();
    assertThat(SourceNameResolver.hostOf(null)).isNull();
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ../gradlew test --tests '*SourceNameResolverTest'`
Expected: FAIL — compilation error, `SourceNameResolver` does not exist.

- [ ] **Step 3: Implement the resolver**

Create `backend/src/main/java/com/simonrowe/aggregation/SourceNameResolver.java`:

```java
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests '*SourceNameResolverTest'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Use the resolver in `importFromUrl`**

In `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java`:

Add the import `com.simonrowe.aggregation.SourceNameResolver`, add the field alongside the others:

```java
  private final SourceNameResolver sourceNameResolver;
```

add a final constructor parameter `final SourceNameResolver sourceNameResolver` with the matching assignment `this.sourceNameResolver = sourceNameResolver;`, then replace line 113:

```java
    String sourceName = extractHostName(normalizedUrl);
```

with:

```java
    String sourceName = sourceNameResolver.resolve(normalizedUrl);
```

and delete the now-unused `extractHostName` method (lines 146-153) together with its `java.net.URI` usage there — keep the class-level `java.net.URI` import, which `normalizeUrl` still needs.

- [ ] **Step 6: Update the agent test's construction**

`backend/src/test/java/com/simonrowe/agents/ContentAggregationAgentTest.java` builds the agent by hand at line 85. Add the mock beside the existing `@Mock` fields:

```java
  @Mock private SourceNameResolver sourceNameResolver;
```

import `com.simonrowe.aggregation.SourceNameResolver`, and extend the constructor call to match the new parameter order:

```java
    agent = new ContentAggregationAgent(
        sourceRepository, articleRepository,
        eventRepository, scraperFactory, htmlScraper, ai,
        changePublisher, imageDownloader, blogImageGenerationService,
        mediaVariantResolver, sourceNameResolver);
```

Run: `cd backend && ../gradlew test --tests '*ContentAggregationAgentTest'`
Expected: PASS — existing tests unchanged in behaviour.

- [ ] **Step 7: Add a test that manual imports reuse a known source name**

Add to `ContentAggregationAgentTest`. The scraped content is deliberately under 50 characters so `classifyAndSummarize` short-circuits before touching the `ai` mock — this test is about attribution, not classification, and staying off the LLM path keeps it independent of that file's `promptRunner`/`creating` setup:

```java
  @Test
  void importFromUrl_attributesTheArticleToTheResolvedSourceName() {
    String url = "https://tessl.io/podcast/116";
    when(articleRepository.existsByOriginalUrl(url)).thenReturn(false);
    when(eventRepository.existsByOriginalUrl(url)).thenReturn(false);
    when(htmlScraper.scrapeArticlePagePublic(url)).thenReturn(new ScrapedContent(
        "Inside the Dark Factory", url, "Short body.",
        Instant.parse("2026-07-01T00:00:00Z"), null, null, false));
    when(sourceNameResolver.resolve(url)).thenReturn("Tessl Blog");
    when(articleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    agent.importFromUrl(url);

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    assertThat(captor.getValue().sourceName()).isEqualTo("Tessl Blog");
  }
```

- [ ] **Step 8: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests '*ContentAggregationAgentTest' --tests '*SourceNameResolverTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/simonrowe/aggregation/SourceNameResolver.java \
        backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java \
        backend/src/test/java/com/simonrowe/aggregation/SourceNameResolverTest.java \
        backend/src/test/java/com/simonrowe/agents/ContentAggregationAgentTest.java
git commit -m "fix: attribute manual imports to known sources instead of bare hosts"
```

---

### Task 5: Fold existing duplicate source names

**Files:**
- Create: `backend/src/main/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNames.java`
- Test: `backend/src/test/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNamesTest.java`

**Interfaces:**
- Consumes: `SourceNameResolver.resolve(String) : String` (Task 4), `MongoTemplate.getCollection(String) : MongoCollection<Document>`.
- Produces: nothing consumed downstream; a data-only migration of `aggregated_articles.sourceName`.

---

- [ ] **Step 1: Write the failing change-unit test**

Create `backend/src/test/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNamesTest.java`. It runs against the shared Mongo Testcontainer via `com.simonrowe.AbstractIntegrationTest`, the same base class `NewsControllerTest` and `V011SeedAndBackfillDanVegaBlogIntegrationTest` use. That base class disables Mongock (`mongock.enabled=false`), so V018 does not run here and cannot pollute the shared database — do **not** re-enable it, the change unit is invoked directly.

`SourceNameResolver` is a real `@Component` after Task 4, so it is replaced in the context with `@MockitoBean` (the repo's chosen Spring Boot 3.5 annotation — not the removed `@MockBean`).

```java
package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.SourceNameResolver;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class V019NormaliseManualImportSourceNamesTest extends AbstractIntegrationTest {

  @MockitoBean private SourceNameResolver sourceNameResolver;
  @Autowired private MongoTemplate mongoTemplate;

  private final V019NormaliseManualImportSourceNames changeUnit =
      new V019NormaliseManualImportSourceNames();

  @BeforeEach
  @AfterEach
  void clearCollection() {
    mongoTemplate.getCollection("aggregated_articles").deleteMany(new Document());
  }

  private void insert(String sourceName, String originalUrl) {
    mongoTemplate.getCollection("aggregated_articles").insertOne(new Document()
        .append("sourceName", sourceName)
        .append("originalUrl", originalUrl)
        .append("title", "Some Title")
        .append("visible", true));
  }

  private String sourceNameOf(String originalUrl) {
    Document found = mongoTemplate.getCollection("aggregated_articles")
        .find(new Document("originalUrl", originalUrl)).first();
    return found == null ? null : found.getString("sourceName");
  }

  @Test
  void rewritesSourceNamesThatResolveToAKnownSource() {
    insert("tessl.io", "https://tessl.io/podcast/116");
    insert("anthropic.com", "https://www.anthropic.com/news/claude-opus-4-7");
    when(sourceNameResolver.resolve("https://tessl.io/podcast/116"))
        .thenReturn("Tessl Blog");
    when(sourceNameResolver.resolve("https://www.anthropic.com/news/claude-opus-4-7"))
        .thenReturn("Claude Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
    assertThat(sourceNameOf("https://www.anthropic.com/news/claude-opus-4-7"))
        .isEqualTo("Claude Blog");
  }

  @Test
  void leavesArticlesWhoseHostMatchesNoKnownSource() {
    insert("blog.cloudflare.com", "https://blog.cloudflare.com/ai-code-review");
    when(sourceNameResolver.resolve("https://blog.cloudflare.com/ai-code-review"))
        .thenReturn("blog.cloudflare.com");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://blog.cloudflare.com/ai-code-review"))
        .isEqualTo("blog.cloudflare.com");
  }

  @Test
  void leavesArticlesAlreadyAttributedToTheirSource() {
    insert("Spring Blog", "https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA");
    when(sourceNameResolver.resolve("https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA"))
        .thenReturn("Spring Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA"))
        .isEqualTo("Spring Blog");
  }

  @Test
  void isANoOpOnASecondRun() {
    insert("tessl.io", "https://tessl.io/podcast/116");
    when(sourceNameResolver.resolve("https://tessl.io/podcast/116"))
        .thenReturn("Tessl Blog");

    changeUnit.execution(mongoTemplate, sourceNameResolver);
    changeUnit.execution(mongoTemplate, sourceNameResolver);

    assertThat(sourceNameOf("https://tessl.io/podcast/116")).isEqualTo("Tessl Blog");
    List<Document> all = mongoTemplate.getCollection("aggregated_articles")
        .find().into(new ArrayList<>());
    assertThat(all).hasSize(1);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ../gradlew test --tests '*V019*'`
Expected: FAIL — compilation error, `V019NormaliseManualImportSourceNames` does not exist.

- [ ] **Step 3: Implement the change unit**

Create `backend/src/main/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNames.java`:

```java
package com.simonrowe.migration.changeunits;

import com.mongodb.client.MongoCollection;
import com.simonrowe.aggregation.SourceNameResolver;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Folds manually imported articles onto the source they actually belong to.
 *
 * <p>{@code importFromUrl} used to attribute every import to its bare host, so production
 * accumulated eight source names covering ten articles — including "tessl.io" next to
 * "Tessl Blog", and two Anthropic hosts next to "Claude Blog". Each of those is a filter
 * pill on the news page.
 *
 * <p>Only articles whose host resolves to a source we already track are rewritten;
 * genuinely separate publishers keep their host name, which is accurate attribution on
 * the card badge, and the news page hides the low-volume ones behind a "More" overflow.
 *
 * <p>{@code sourceUrl} is intentionally left alone: nothing filters or displays it, and
 * rewriting it would claim the article came from a page it did not.
 *
 * <p>Idempotent: an article whose {@code sourceName} already equals the resolved name is
 * skipped, so a re-run is a no-op. Works at the raw {@link Document} level to avoid a
 * record round-trip rewriting fields this migration has no business touching.
 */
@ChangeUnit(id = "normalise-manual-import-source-names", order = "019", author = "simonrowe")
public class V019NormaliseManualImportSourceNames {

  private static final Logger log =
      LoggerFactory.getLogger(V019NormaliseManualImportSourceNames.class);

  private static final String ARTICLES = "aggregated_articles";
  private static final String SOURCE_NAME = "sourceName";
  private static final String ORIGINAL_URL = "originalUrl";

  @Execution
  public void execution(
      final MongoTemplate mongoTemplate,
      final SourceNameResolver sourceNameResolver) {
    final MongoCollection<Document> articles = mongoTemplate.getCollection(ARTICLES);

    int rewritten = 0;
    for (final Document article : articles.find()) {
      final String originalUrl = article.getString(ORIGINAL_URL);
      final String currentName = article.getString(SOURCE_NAME);
      if (originalUrl == null || currentName == null) {
        continue;
      }
      final String resolved = sourceNameResolver.resolve(originalUrl);
      if (resolved.equals(currentName)) {
        continue;
      }
      articles.updateOne(
          new Document("_id", article.get("_id")),
          new Document("$set", new Document(SOURCE_NAME, resolved)));
      log.info("Re-attributed '{}' from {} to {}", originalUrl, currentName, resolved);
      rewritten++;
    }
    log.info("Re-attributed {} articles to their known source", rewritten);
  }

  /**
   * Deliberately empty. The pre-migration source name was derived from each article's
   * host and is not recorded anywhere, so it cannot be restored; re-deriving it would
   * simply undo the fix. Rolling back leaves the corrected names in place, which is
   * harmless — the names are display metadata, not identity.
   */
  @RollbackExecution
  public void rollback() {
    log.info("No rollback for source-name normalisation; corrected names are kept");
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ../gradlew test --tests '*V019*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNames.java \
        backend/src/test/java/com/simonrowe/migration/changeunits/V019NormaliseManualImportSourceNamesTest.java
git commit -m "fix: fold duplicate manual-import source names onto known sources"
```

---

### Task 6: Return article counts from the sources endpoint

The frontend cannot sort pills by volume or hide low-volume ones while the endpoint returns a bare list of names.

**Files:**
- Create: `backend/src/main/java/com/simonrowe/aggregation/SourceSummary.java`
- Modify: `backend/src/main/java/com/simonrowe/aggregation/NewsController.java:69-80`
- Test: `backend/src/test/java/com/simonrowe/aggregation/NewsControllerTest.java:96-157` — **five existing tests hit `/api/news/sources` and three of them assert the old string-array shape.** They must be updated in the same commit, not left failing.

**Interfaces:**
- Consumes: `MongoTemplate.aggregate(...)`, `AggregatedArticle` (the `@Document` mapping supplies the collection).
- Produces: `SourceSummary(String name, long count)` — consumed by Task 7's TypeScript type. `GET /api/news/sources` returns `List<SourceSummary>` ordered by count descending, then name ascending.

---

- [ ] **Step 1: Update the three existing `/api/news/sources` tests to the new shape**

In `NewsControllerTest.java`, the response is now an array of objects, so `$[0]` becomes `$[0].name`. Make exactly these three edits, using the file's existing `sampleArticleWithSource(id, title, sourceName, visible)` helper:

Replace `getSources_returnsNamesInAlphabeticalOrder` (lines 111-127) — alphabetical order no longer holds, so the test is rewritten for the new contract rather than patched:

```java
  @Test
  void getSources_returnsNamesByArticleCountThenAlphabetically() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "One", "InfoQ", true),
        sampleArticleWithSource("a-2", "Two", "InfoQ", true),
        sampleArticleWithSource("a-3", "Three", "InfoQ", true),
        sampleArticleWithSource("a-4", "Four", "Zebra Blog", true),
        sampleArticleWithSource("a-5", "Five", "Ars Technica", true)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].name").value("InfoQ"))
        .andExpect(jsonPath("$[0].count").value(3))
        // One article each, so the tie breaks alphabetically.
        .andExpect(jsonPath("$[1].name").value("Ars Technica"))
        .andExpect(jsonPath("$[2].name").value("Zebra Blog"));
  }
```

In `getSources_excludesSourcesOnlyHiddenArticlesHave` (line 139), change:

```java
        .andExpect(jsonPath("$[0]").value("InfoQ"));
```

to:

```java
        .andExpect(jsonPath("$[0].name").value("InfoQ"))
        .andExpect(jsonPath("$[0].count").value(1));
```

In `getSources_isNotShadowedByTheByIdMapping` (line 156), change:

```java
        .andExpect(jsonPath("$[0]").value("Tech Blog"));
```

to:

```java
        .andExpect(jsonPath("$[0].name").value("Tech Blog"));
```

`getSources_returnsDistinctNamesFromDuplicatedSources` and `getSources_returnsEmptyArrayWhenNoArticles` assert only length and emptiness, so they stand unchanged.

- [ ] **Step 2: Add the new count test**

```java
  @Test
  void getSources_countsOnlyVisibleArticlesPerSource() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "One", "Spring Blog", true),
        sampleArticleWithSource("a-2", "Two", "Spring Blog", true),
        sampleArticleWithSource("a-3", "Three", "Spring Blog", false)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Spring Blog"))
        .andExpect(jsonPath("$[0].count").value(2));
  }
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && ../gradlew test --tests '*NewsControllerTest'`
Expected: FAIL — the endpoint still returns plain strings, so every `$[n].name` assertion is unmatched.

- [ ] **Step 4: Create the `SourceSummary` record**

Create `backend/src/main/java/com/simonrowe/aggregation/SourceSummary.java`:

```java
package com.simonrowe.aggregation;

/**
 * A news source and how many visible articles it holds.
 *
 * <p>The count drives the news page's filter pills: sources sort by volume, and the
 * long tail of one- and two-article sources collapses behind a "More" overflow rather
 * than crowding the row.
 *
 * @param name  the source name as stored on each article
 * @param count how many visible articles carry that name
 */
public record SourceSummary(String name, long count) {
}
```

- [ ] **Step 5: Implement the aggregation**

In `backend/src/main/java/com/simonrowe/aggregation/NewsController.java`, add the imports:

```java
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
```

and replace `listSources` (lines 57-80, javadoc included) with:

```java
  /**
   * Every source across the visible articles with its article count, busiest first.
   *
   * <p>Backs the news filter pills, which must list every source the site holds rather
   * than only those appearing on the first page of results. The count lets the page sort
   * by volume and collapse low-volume sources into a "More" overflow, which is what keeps
   * one-off manual imports from crowding the row.
   *
   * <p>Declared before the {@code /{id}} mapping for readability only — Spring matches the
   * literal {@code /sources} path ahead of the {@code {id}} template regardless of order.
   *
   * @return the source summaries, empty when there are no visible articles
   */
  @GetMapping("/sources")
  public List<SourceSummary> listSources() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("visible").is(true)),
        Aggregation.group("sourceName").count().as("count"),
        Aggregation.project("count").and("_id").as("name"),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "count")
            .and(Sort.by(Sort.Direction.ASC, "name"))));

    return mongoTemplate
        .aggregate(aggregation, AggregatedArticle.class, SourceSummary.class)
        .getMappedResults()
        .stream()
        .filter(summary -> summary.name() != null)
        .toList();
  }
```

Remove the now-unused `java.util.Objects` import if nothing else in the file uses it.

- [ ] **Step 6: Run to verify they pass**

Run: `cd backend && ../gradlew test --tests '*NewsControllerTest'`
Expected: PASS, 6 `getSources_*` tests among them.

- [ ] **Step 7: Run the whole backend gate**

Run: `cd backend && ../gradlew test`
Expected: PASS, Checkstyle clean.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/simonrowe/aggregation/SourceSummary.java \
        backend/src/main/java/com/simonrowe/aggregation/NewsController.java \
        backend/src/test/java/com/simonrowe/aggregation/
git commit -m "feat: return article counts from the news sources endpoint"
```

---

### Task 7: Adapt the frontend to the new sources shape

Keeps the page working and every test green against `SourceSummary[]`, with no visual change. Task 8 does the redesign.

**Files:**
- Modify: `frontend/src/types/news.ts`
- Modify: `frontend/src/services/newsApi.ts:23-31`
- Modify: `frontend/src/pages/NewsEventsPage.tsx:37` and `:179-182`
- Test: `frontend/tests/pages/NewsEventsPage.test.tsx` (lines 84, 135, 158, 171 mock the old shape)

**Interfaces:**
- Consumes: `GET /api/news/sources` returning `SourceSummary[]` (Task 6).
- Produces:
  - `SourceSummary { name: string; count: number }` exported from `types/news.ts`
  - `fetchNewsSources() : Promise<SourceSummary[]>`
  - `sourceSummaries: SourceSummary[]` local to `NewsEventsPage`, replacing `sourceNames: string[]` — Task 8 consumes it.

---

- [ ] **Step 1: Update the tests to the new shape and watch them fail**

In `frontend/tests/pages/NewsEventsPage.test.tsx`, add a helper next to the existing `article`/`newsPage` helpers:

```tsx
function source(name: string, count = 5): SourceSummary {
  return { name, count }
}
```

import the type: `import type { ArticlePage, ArticleResponse, SourceSummary } from '../../src/types/news'`

then replace each `fetchNewsSources` mock. Give every source a count of 5 so all remain visible — Task 8 adds the threshold tests:

- line 84: `vi.mocked(fetchNewsSources).mockResolvedValue([source('InfoQ')])`
- line 135: `vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])`
- line 158: `vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ'), source('The Pragmatic Engineer')])`
- line 171: `vi.mocked(fetchNewsSources).mockResolvedValue([source('Ars Technica'), source('InfoQ')])`

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npm test -- NewsEventsPage`
Expected: FAIL — TypeScript error, `SourceSummary` is not exported from `types/news`.

- [ ] **Step 3: Add the type**

In `frontend/src/types/news.ts`, add:

```ts
/** A news source and how many visible articles it holds, busiest first from the API. */
export interface SourceSummary {
  name: string
  count: number
}
```

- [ ] **Step 4: Update the API client**

In `frontend/src/services/newsApi.ts`, add `SourceSummary` to the type import and replace `fetchNewsSources`:

```ts
/**
 * Every source the site holds with its article count, busiest first, so the filter
 * pills can list a source even when it has no article in the first page of results
 * and can collapse the low-volume tail.
 */
export async function fetchNewsSources(): Promise<SourceSummary[]> {
  return fetchWithRetry<SourceSummary[]>(`${NEWS_ENDPOINT}/sources`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}
```

- [ ] **Step 5: Update the page to hold summaries**

In `frontend/src/pages/NewsEventsPage.tsx`, add `SourceSummary` to the type import from `../types/news`, change line 37 to:

```tsx
  const [sources, setSources] = useState<SourceSummary[]>([])
```

and replace the `sourceNames` derivation (lines 179-182) with:

```tsx
  // Every source the site holds (FR-039), so a source with no article on page 0 is
  // still selectable. Falls back to counting the loaded articles if that request failed.
  const sourceSummaries: SourceSummary[] =
    sources.length > 0
      ? sources
      : Object.entries(
          articles.reduce<Record<string, number>>((counts, a) => {
            counts[a.sourceName] = (counts[a.sourceName] ?? 0) + 1
            return counts
          }, {}),
        ).map(([name, count]) => ({ name, count }))
```

Then change the render loop at line 215 from `{sourceNames.map(source => (` to:

```tsx
        {sourceSummaries.map(({ name }) => (
```

and inside it replace the three uses of `source` with `name`: `sourceFilter === name`, `key={name}`, `onClick={() => handleSourceSelect(name)}` and the button text `{name}`.

- [ ] **Step 6: Run to verify they pass**

Run: `cd frontend && npm test -- NewsEventsPage`
Expected: PASS, 8 tests.

- [ ] **Step 7: Run the full frontend gate**

Run: `cd frontend && npm test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/types/news.ts frontend/src/services/newsApi.ts \
        frontend/src/pages/NewsEventsPage.tsx frontend/tests/pages/NewsEventsPage.test.tsx
git commit -m "refactor: carry article counts through the news sources client"
```

---

### Task 8: Split the filter row and collapse low-volume sources

**Files:**
- Modify: `frontend/src/pages/NewsEventsPage.tsx` (the `feed__filters` block, currently lines 206-243)
- Modify: `frontend/src/styles.css:7958-7993` and `:8387-8400`
- Test: `frontend/tests/pages/NewsEventsPage.test.tsx`

**Interfaces:**
- Consumes: `sourceSummaries: SourceSummary[]` (Task 7), `sourceFilter`, `handleSourceSelect`, `favouritesOnly`, `handleFavouritesToggle`, `allEvents` (all existing in the component).
- Produces: no exported interface; a self-contained UI change.

---

- [ ] **Step 1: Write the failing UI tests**

Add to `frontend/tests/pages/NewsEventsPage.test.tsx`:

```tsx
  it('orders source pills by article count, busiest first', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Dan Vega', 16),
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    const pills = screen.getAllByRole('button').map(b => b.textContent)
    expect(pills.indexOf('Rundown AI')).toBeLessThan(pills.indexOf('Spring Blog'))
    expect(pills.indexOf('Spring Blog')).toBeLessThan(pills.indexOf('Dan Vega'))
  })

  it('hides sources with fewer than three articles behind the More menu', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('blog.cloudflare.com', 2),
      source('ssntpl.com', 1),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    // Regex, not an exact string: a menu row's accessible name is its source name
    // followed by its article count ("blog.cloudflare.com 2").
    expect(screen.queryByRole('button', { name: /blog\.cloudflare\.com/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'More (2)' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'More (2)' }))

    expect(screen.getByRole('button', { name: /blog\.cloudflare\.com/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /ssntpl\.com/ })).toBeInTheDocument()
  })

  it('filters by a source chosen from the More menu and shows it as active', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('ssntpl.com', 1),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'More (1)' }))
    await userEvent.click(screen.getByRole('button', { name: /ssntpl\.com/ }))

    await waitFor(() =>
      expect(fetchNews).toHaveBeenCalledWith(0, 24, 'ssntpl.com'),
    )
    // The menu has closed, so this now matches the toggle itself: the active filter
    // must stay visible even though the source is collapsed out of the main row.
    expect(screen.getByRole('button', { name: 'ssntpl.com' })).toBeInTheDocument()
  })

  it('renders no More button when every source clears the threshold', async () => {
    vi.mocked(fetchNews).mockResolvedValue(newsPage([article('1', 'One')], 0, true))
    vi.mocked(fetchNewsSources).mockResolvedValue([
      source('Rundown AI', 298),
      source('Spring Blog', 81),
    ])
    renderPage()

    await waitFor(() => expect(screen.getByText('Rundown AI')).toBeInTheDocument())

    expect(screen.queryByRole('button', { name: /^More/ })).toBeNull()
  })
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npm test -- NewsEventsPage`
Expected: FAIL — no `More (2)` button exists, and pill order follows the API's order rather than being enforced by the page.

- [ ] **Step 3: Add the derivation and menu state**

In `frontend/src/pages/NewsEventsPage.tsx`, add `ChevronDown` to the lucide import:

```tsx
import { Calendar, ChevronDown, ExternalLink, Heart, MapPin } from 'lucide-react'
```

Add the constant beside `NEWS_PAGE_SIZE`:

```tsx
/**
 * Below this, a source is a long-tail one-off — usually a single manually imported
 * article — and goes in the "More" menu instead of costing a pill in the main row.
 */
const MIN_ARTICLES_FOR_PILL = 3
```

Add state and a ref beside the other `useState` calls:

```tsx
  const [moreOpen, setMoreOpen] = useState(false)
  const moreMenuRef = useRef<HTMLDivElement>(null)
```

Add the click-outside effect beside the other effects, matching the pattern in `components/admin/TagInput.tsx`:

```tsx
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (moreMenuRef.current && !moreMenuRef.current.contains(e.target as Node)) {
        setMoreOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])
```

Add the derivation just after `sourceSummaries`:

```tsx
  // Sorted here rather than trusted from the API so the order holds for the
  // article-derived fallback too.
  const sortedSources = [...sourceSummaries].sort(
    (a, b) => b.count - a.count || a.name.localeCompare(b.name),
  )
  const pillSources = sortedSources.filter(s => s.count >= MIN_ARTICLES_FOR_PILL)
  const menuSources = sortedSources.filter(s => s.count < MIN_ARTICLES_FOR_PILL)
  // A collapsed source that is the active filter has to surface somewhere, or the
  // page looks unfiltered while showing one source's articles.
  const activeMenuSource = menuSources.find(s => s.name === sourceFilter)
```

- [ ] **Step 4: Replace the filter markup**

Replace the whole `<div className="feed__filters">…</div>` block (currently lines 207-243) with:

```tsx
      {/* View modes, kept apart from sources so "Events" doesn't read as a publisher. */}
      <div className="feed__modes">
        {allEvents.length > 0 && (
          <button
            className={`feed__pill feed__pill--events${sourceFilter === 'events' ? ' feed__pill--active' : ''}`}
            onClick={() => handleSourceSelect('events')}
            type="button"
          >
            Events
          </button>
        )}
        <button
          aria-pressed={favouritesOnly}
          className={`feed__pill feed__favourites-toggle${favouritesOnly ? ' feed__pill--active' : ''}`}
          onClick={handleFavouritesToggle}
          type="button"
        >
          <Heart aria-hidden="true" fill={favouritesOnly ? 'currentColor' : 'none'} size={14} />
          <span>Show favourites only</span>
        </button>
      </div>

      {/* Source filter pills, busiest first, long tail collapsed. */}
      <div className="feed__filters">
        <button
          className={`feed__pill${sourceFilter === 'all' ? ' feed__pill--active' : ''}`}
          onClick={() => handleSourceSelect('all')}
          type="button"
        >
          All
        </button>
        {pillSources.map(({ name }) => (
          <button
            className={`feed__pill${sourceFilter === name ? ' feed__pill--active' : ''}`}
            key={name}
            onClick={() => handleSourceSelect(name)}
            type="button"
          >
            {name}
          </button>
        ))}
        {menuSources.length > 0 && (
          <div className="feed__more" ref={moreMenuRef}>
            <button
              aria-expanded={moreOpen}
              aria-haspopup="true"
              className={`feed__pill feed__more-toggle${activeMenuSource ? ' feed__pill--active' : ''}`}
              onClick={() => setMoreOpen(open => !open)}
              type="button"
            >
              <span>{activeMenuSource ? activeMenuSource.name : `More (${menuSources.length})`}</span>
              <ChevronDown aria-hidden="true" size={14} />
            </button>
            {moreOpen && (
              /* Plain buttons, not role="menu"/"menuitem": an explicit menuitem role
                 would stop these matching getByRole('button'), and the popover is a
                 list of filters rather than an application menu. */
              <div className="feed__more-menu">
                {menuSources.map(({ name, count }) => (
                  <button
                    className={`feed__more-item${sourceFilter === name ? ' feed__more-item--active' : ''}`}
                    key={name}
                    onClick={() => {
                      handleSourceSelect(name)
                      setMoreOpen(false)
                    }}
                    type="button"
                  >
                    <span>{name}</span>
                    <span className="feed__more-count">{count}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
```

Note the `More (n)` button carries an accessible name of just `More (n)` because the `ChevronDown` is `aria-hidden`. The test above relies on that. The selected-source case renders the source name as the button text, which is what makes the third test's final assertion pass.

- [ ] **Step 5: Add the styles**

In `frontend/src/styles.css`, immediately after the `.feed__filters` rule (line 7964), add:

```css
.feed__modes {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.feed__more {
  position: relative;
  display: inline-flex;
}

.feed__more-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.feed__more-menu {
  position: absolute;
  top: calc(100% + 0.4rem);
  left: 0;
  z-index: 20;
  min-width: 14rem;
  max-height: 18rem;
  overflow-y: auto;
  padding: 0.35rem;
  border: 1px solid var(--color-border);
  border-radius: 0.65rem;
  background: var(--color-surface);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.feed__more-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  width: 100%;
  padding: 0.45rem 0.6rem;
  border: none;
  border-radius: 0.4rem;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 0.85rem;
  text-align: left;
  cursor: pointer;
}

.feed__more-item:hover {
  background: var(--color-surface-subtle);
  color: var(--color-primary);
}

.feed__more-item--active {
  color: var(--color-primary);
  font-weight: 600;
}

.feed__more-count {
  font-size: 0.75rem;
  opacity: 0.7;
}
```

`--color-surface`, `--color-surface-subtle`, `--color-border` and `--color-primary` are all defined in this stylesheet's theme blocks (lines 92-98 and 180-186) and are the variables the neighbouring `.feed__*` rules already use. Do not introduce new ones.

In the mobile block (line 8387), the horizontal scroll must stay on the source row only, so `.feed__modes` wraps normally. Leave the existing `.feed__filters` rule there as-is and add:

```css
  .feed__more-menu {
    left: auto;
    right: 0;
  }
```

- [ ] **Step 6: Run to verify the tests pass**

Run: `cd frontend && npm test -- NewsEventsPage`
Expected: PASS, 12 tests.

- [ ] **Step 7: Run the full frontend gate**

Run: `cd frontend && npm test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/NewsEventsPage.tsx frontend/src/styles.css \
        frontend/tests/pages/NewsEventsPage.test.tsx
git commit -m "feat: split news filter row and collapse low-volume sources"
```

---

### Task 9: Verify end to end against a local stack

No new code. This is the runbook check from the spec, and it is the only step that proves the scraper works against the real site rather than a fixture.

**Files:** none modified.

---

- [ ] **Step 1: Run both gates**

```bash
cd backend && ../gradlew test
cd ../frontend && npm test
```

Expected: both PASS, Checkstyle clean. Do not continue past a failure.

- [ ] **Step 2: Confirm the site still has the structure the scraper expects**

```bash
curl -sS -A 'Mozilla/5.0' https://ai4jvm.com/ | grep -c 'class="news-bar"'
```

Expected: `1`. If it is `0`, the page has been restyled and Task 1's selectors need revisiting before going further.

- [ ] **Step 3: Boot the local stack**

```bash
docker compose up -d --wait && ./scripts/start-backend.sh
```

Mongock runs on boot, so V018 seeds and backfills AI4JVM and V019 folds the duplicate names. Watch the log for `Seeded AI4JVM content source`, `Found N roundup links on https://ai4jvm.com`, `Backfilling N items from AI4JVM`, `Saved article: …` and `Re-attributed N articles to their known source`.

An `OPENAI_API_KEY` must be present in `backend/.env` or every item falls back to title-as-summary.

- [ ] **Step 4: Check the documents**

```bash
docker compose exec -T mongodb mongosh simonrowe --quiet --eval '
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM"});
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM", imageUrl: null});
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM", publishedDate: null});
  db.aggregated_articles.find({sourceName: "AI4JVM"},
    {title:1, publishedDate:1, originalUrl:1}).sort({publishedDate:-1}).limit(5).pretty();'
```

The first count must be greater than zero; the two trailing counts must both be `0`. A null `imageUrl` means the image download *and* generation both failed — find that article's title in the backend log.

- [ ] **Step 5: Check the folds landed**

```bash
curl -sS 'http://localhost:8080/api/news/sources'
```

`tessl.io`, `anthropic.com` and `code.claude.com` must be absent, `AI4JVM` present, and the list ordered by count descending.

Note: the fold only fires if the local database actually holds those manual imports — restore a production backup first (`prod-data-restore`) if it does not, otherwise this step proves nothing.

- [ ] **Step 6: Check the page**

Open `http://localhost:5173/news-events` and confirm:

- The view modes (`Events`, `Show favourites only`) sit on their own row above the sources.
- Source pills run busiest-first, starting `All`, `Rundown AI`, `Spring Blog`.
- A `More (n)` button appears, opens on click, closes on an outside click, and filters the page when a source inside it is chosen.
- AI4JVM cards carry an image, a plausible date, and an outbound link that lands on the *original publisher* (InfoQ, foojay, GitHub), not on ai4jvm.com.

- [ ] **Step 7: Commit anything the verification changed**

If steps 2-6 required a fix, commit it with a `fix:` message describing what the verification caught. If nothing changed, there is nothing to commit.

---

## Notes for the implementer

**Two known hazards, both already handled above — do not "simplify" them away:**

1. `LinkRoundupScraper` normalises target URLs through `SitemapHtmlScraper.normalizeUrl` (Task 1, Step 1). `scrapeArticlePage` normalises the URLs *it* returns, so if the roundup scraper kept raw hrefs, a trailing slash would make `existsByOriginalUrl` miss and the same article would be saved twice under different URLs.

2. `SourceNameResolver` returns the bare host when two non-event sources share it, rather than picking one. tessl.io really does host two sources in production. An order-dependent pick would mis-attribute silently.

**The AI4JVM source name is one-shot.** `content_sources.name` carries a unique index, and `V012MergeDanVegaSources` exists solely because two change units once seeded the same display name. Get `"AI4JVM"` right the first time.

**One deliberate deviation from the spec.** The spec called for a checked-in fixture of the live news-bar HTML asserting all 29 items extract. This plan uses inline HTML strings per behaviour instead: a snapshot of a hand-maintained page rots, and "29" would become a failing test the day the curator publishes item 30. The intent behind that requirement — proving we extract real headlines and ignore the ~440 links elsewhere on the page — is covered by `extractLinks_ignoresLinksOutsideTheNewsBar` plus Task 9 Step 2, which checks the live page still has the structure the selectors expect.
