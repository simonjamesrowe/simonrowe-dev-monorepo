package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.SchoolProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which pages the crawl actually reaches.
 *
 * <p>The bug these guard against was invisible for the whole life of the feature: the school's
 * sitemap lists 218 URLs and not one of them is a year-group page, so Term Time never read who
 * teaches Year 6 or which day each class does PE, and reported — accurately — that it could not
 * find them. Nothing failed, nothing was logged, and every test passed.
 */
class SchoolWebsiteCrawlerPagesTest {

  private static final String BASE = "https://www.kilmorieschool.co.uk";
  private static final String SITEMAP = BASE + "/googlesitemap.asp";

  /** A crawler whose fetches are answered from a fixture rather than the network. */
  private static final class StubCrawler extends SchoolWebsiteCrawler {

    private final String sitemapBody;
    private final java.util.Map<String, String> pages = new java.util.HashMap<>();
    private final List<String> fetched = new ArrayList<>();

    private StubCrawler(final SchoolProperties properties, final String sitemapBody) {
      super(properties);
      this.sitemapBody = sitemapBody;
    }

    private StubCrawler serving(final String url, final String body) {
      pages.put(url, body);
      return this;
    }

    @Override
    String fetchText(final String url) {
      fetched.add(url);
      return SITEMAP.equals(url) ? sitemapBody : pages.get(url);
    }
  }

  private static SchoolProperties propertiesWith(
      final String baseUrl, final List<String> extraPages) {
    return new SchoolProperties(
        true, null, List.of(), List.of(), null, baseUrl, null, 0, null, null, 0L, null,
        extraPages);
  }

  private static String sitemapOf(final List<String> urls) {
    return "<urlset>"
        + urls.stream().map(u -> "<loc>" + u + "</loc>").reduce("", String::concat)
        + "</urlset>";
  }

  @Test
  @DisplayName("the year-group pages are crawled even though the sitemap omits them")
  void yearGroupPagesAreCrawled() {
    // The exact shape of the live sitemap: plenty of pages, no year groups.
    final StubCrawler crawler = new StubCrawler(
        propertiesWith(BASE, null),
        sitemapOf(List.of(BASE + "/curriculum/subjects/pe", BASE + "/key-information/term-dates")));

    assertThat(crawler.listPages())
        .contains(
            BASE + "/year-one",
            BASE + "/year-two",
            BASE + "/year-three",
            BASE + "/year-4",
            BASE + "/year-five",
            BASE + "/year-six",
            BASE + "/year-group-pages")
        // and has not lost what it already read
        .contains(BASE + "/curriculum/subjects/pe", BASE + "/key-information/term-dates");
  }

  @Test
  @DisplayName("extras come first, so the page cap can never drop them")
  void extrasSurviveThePageCap() {
    // 300 sitemap entries against a MAX_PAGES of 250. Whatever gets dropped, it must not be the
    // configured pages — the sitemap's tail is years of old sports reports and the extras are
    // where the timetable lives.
    final List<String> many = IntStream.range(0, 300)
        .mapToObj(i -> BASE + "/school-news/item-" + i)
        .toList();
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), sitemapOf(many));

    final List<String> pages = crawler.listPages();

    assertThat(pages).hasSize(250);
    assertThat(pages).contains(BASE + "/year-six");
    assertThat(pages.subList(0, 7)).containsExactly(
        BASE + "/year-group-pages",
        BASE + "/year-one",
        BASE + "/year-two",
        BASE + "/year-three",
        BASE + "/year-4",
        BASE + "/year-five",
        BASE + "/year-six");
  }

  @Test
  @DisplayName("a page in both the sitemap and the extras is crawled once")
  void noDuplicates() {
    // The school could add the year pages to its sitemap at any time, and that must be an
    // improvement rather than a doubling of the crawl's most expensive fetches.
    final StubCrawler crawler = new StubCrawler(
        propertiesWith(BASE, null),
        sitemapOf(List.of(BASE + "/year-six", BASE + "/curriculum")));

    assertThat(crawler.listPages())
        .filteredOn(url -> (BASE + "/year-six").equals(url))
        .hasSize(1);
  }

  @Test
  @DisplayName("an unreadable sitemap yields nothing, even with extras configured")
  void sitemapFailureIsNotMaskedByExtras() {
    // SchoolIngestService reads an empty list as a source failure and records it. Returning the
    // seven extras instead would turn a visible outage into a crawl that reports success while
    // silently skipping 96% of the site.
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), null);

    assertThat(crawler.listPages()).isEmpty();
  }

  @Test
  @DisplayName("calendar URLs are still excluded, and extras are not subject to that filter")
  void calendarStillExcluded() {
    final StubCrawler crawler = new StubCrawler(
        propertiesWith(BASE, List.of("/calendar/staff-only")),
        sitemapOf(List.of(BASE + "/calendar/?calid=2", BASE + "/curriculum")));

    assertThat(crawler.listPages())
        .doesNotContain(BASE + "/calendar/?calid=2")
        // An explicitly configured page is a decision, not a discovery, so the heuristic that
        // keeps the crawl out of the calendar's infinite URL space does not veto it.
        .contains(BASE + "/calendar/staff-only");
  }

  @Test
  @DisplayName("configured extras replace the defaults rather than adding to them")
  void configuredExtrasReplaceDefaults() {
    final StubCrawler crawler = new StubCrawler(
        propertiesWith(BASE, List.of("/year-six")), sitemapOf(List.of(BASE + "/curriculum")));

    assertThat(crawler.listPages())
        .contains(BASE + "/year-six")
        .doesNotContain(BASE + "/year-one");
  }

  @Test
  @DisplayName("a page reports the canonical URL it declares, not the one it was fetched from")
  void canonicalIsRead() {
    // This CMS serves every page twice: /year-six-home-learning and
    // /page/?title=Home+Learning&pid=158 return byte-identical text, and both declare the
    // friendly form as canonical. Without reading it, following a link stores the same page a
    // second time under a second id and embeds it twice.
    final String ugly = BASE + "/page/?title=Home+Learning&pid=158";
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), sitemapOf(List.of()))
        .serving(ugly, "<html><head><title>Home Learning</title>"
            + "<link rel=\"canonical\" href=\"" + BASE + "/year-six-home-learning\">"
            + "</head><body><main>Spellings are given out on a Monday.</main></body></html>");

    final SchoolWebsiteCrawler.CrawledPage page = crawler.fetchPage(ugly);

    assertThat(page.url()).isEqualTo(ugly);
    assertThat(page.canonicalUrl()).isEqualTo(BASE + "/year-six-home-learning");
  }

  @Test
  @DisplayName("a page with no canonical falls back to the URL it was fetched from")
  void canonicalFallsBackToTheFetchedUrl() {
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), sitemapOf(List.of()))
        .serving(BASE + "/year-six", "<html><body><main>Year 6</main></body></html>");

    assertThat(crawler.fetchPage(BASE + "/year-six").canonicalUrl())
        .isEqualTo(BASE + "/year-six");
  }

  @Test
  @DisplayName("links come from the content, with navigation, header and footer excluded")
  void linksExcludeSiteFurniture() {
    // The single most effective filter in the discovery path. This CMS emits semantic
    // <nav>/<header>/<footer> and repeats ~60 same-host links on every page; measured on the
    // real /year-six, 68 same-host links in the raw markup become 12 once they are gone.
    // Without this, one hop from any page is a crawl of the whole site.
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), sitemapOf(List.of()))
        .serving(BASE + "/year-six", "<html><body>"
            + "<header><a href=\"/school-uniform\">Uniform</a></header>"
            + "<nav><a href=\"/admissions\">Admissions</a></nav>"
            + "<main><a href=\"/year-six-home-learning\">Home Learning</a>"
            + "<a href=\"https://www.natgeokids.com/greece\">Ancient Greece</a></main>"
            + "<footer><a href=\"/privacy-cookies\">Privacy</a></footer>"
            + "</body></html>");

    assertThat(crawler.fetchPage(BASE + "/year-six").links()).containsExactly(
        BASE + "/year-six-home-learning", "https://www.natgeokids.com/greece");
  }

  @Test
  @DisplayName("relative links are resolved to absolute, and unresolvable ones are dropped")
  void linksAreAbsolute() {
    final StubCrawler crawler = new StubCrawler(propertiesWith(BASE, null), sitemapOf(List.of()))
        .serving(BASE + "/year-six/index", "<html><body><main>"
            + "<a href=\"../year-five\">Five</a>"
            + "<a href=\"mailto:office@example.test\">Mail</a>"
            + "<a href=\"#top\">Top</a>"
            + "</main></body></html>");

    assertThat(crawler.fetchPage(BASE + "/year-six/index").links())
        .containsExactly(BASE + "/year-five");
  }
}
