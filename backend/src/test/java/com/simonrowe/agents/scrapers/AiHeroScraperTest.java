package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.rometools.rome.io.SyndFeedInput;
import com.simonrowe.agents.scrapers.AiHeroScraper.FeedItem;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Item shapes are those of aihero.dev's feed and markdown twins on 2026-10-23. Bodies are
 * invented: the repository is public, so it carries none of the site's writing.
 */
@ExtendWith(MockitoExtension.class)
class AiHeroScraperTest {

  private static final String FEED = "https://www.aihero.dev/rss.xml";
  private static final Instant AFTER_FLOOR = Instant.parse("2026-03-16T10:00:00Z");

  private static final String POST_TWIN = """
      ---
      title: "Tracer Bullets: Keeping AI Slop Under Control"
      slug: "tracer-bullets"
      type: "post"
      description: "A post about building end-to-end slices."
      tags:
        - level-up-your-workflow
      updatedAt: "2026-01-22T13:21:37.803Z"
      ---

      Build the thinnest slice that runs end to end, then widen it.

      ## Why it works

      Feedback arrives before the slop does.
      """;

  @Mock private AggregatedArticleRepository articleRepository;

  @Test
  void feedItems_readsTitleLinkAndDateFromTheFeed() throws Exception {
    var feed = new SyndFeedInput().build(new StringReader("""
        <?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>AI Hero RSS Feed</title><link>https://www.aihero.dev</link>
          <description>d</description>
          <item>
            <title><![CDATA[Tracer Bullets: Keeping AI Slop Under Control]]></title>
            <link>https://www.aihero.dev/tracer-bullets</link>
            <guid>https://www.aihero.dev/tracer-bullets</guid>
            <pubDate>Thu, 22 Jan 2026 13:21:37 GMT</pubDate>
            <description><![CDATA[Short description.]]></description>
          </item>
        </channel></rss>
        """));

    List<FeedItem> items = AiHeroScraper.feedItems(feed);

    assertThat(items).containsExactly(new FeedItem(
        "Tracer Bullets: Keeping AI Slop Under Control",
        "https://www.aihero.dev/tracer-bullets",
        Instant.parse("2026-01-22T13:21:37Z")));
  }

  @Test
  void isCandidate_keepsSingleSegmentPostAfterTheFloor() {
    assertThat(AiHeroScraper.isCandidate(FEED, item("/tracer-bullets", AFTER_FLOOR))).isTrue();
  }

  @Test
  void isCandidate_dropsNonPostsWithoutFetchingThem() {
    // Dictionary entries, the /skills/ copy of a changelog and workshop lessons are all
    // more than one segment deep.
    assertThat(AiHeroScraper.isCandidate(FEED, item("/ai-coding-dictionary/turn", AFTER_FLOOR)))
        .isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED,
        item("/skills/skills-changelog-v13-implement-spec-pr-retro-and-glossary-md", AFTER_FLOOR)))
        .isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED, item("/workshops/ai-coding-crash-course",
        AFTER_FLOOR))).isFalse();
    // Skill reference pages say type: post in their twin, so only the slug can exclude them.
    assertThat(AiHeroScraper.isCandidate(FEED, item("/skills-retro", AFTER_FLOOR))).isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED, item("/", AFTER_FLOOR))).isFalse();
  }

  @Test
  void isCandidate_holdsTheFloorOnEveryRun() {
    // runAggregation has no cutoff, so without this the first scheduled run would ingest
    // every post back to 2024.
    assertThat(AiHeroScraper.isCandidate(FEED,
        item("/what-is-an-agent", Instant.parse("2025-09-30T00:00:00Z")))).isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED,
        item("/getting-started-with-ralph", AiHeroScraper.PUBLISHED_FLOOR))).isTrue();
    assertThat(AiHeroScraper.isCandidate(FEED, item("/undated", null))).isFalse();
  }

  @Test
  void isCandidate_comparesOriginsByParsingNotByPrefix() {
    assertThat(AiHeroScraper.isCandidate(FEED, new FeedItem("t",
        "https://www.aihero.dev.attacker.example/post", AFTER_FLOOR))).isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED, new FeedItem("t",
        "http://www.aihero.dev/post", AFTER_FLOOR))).isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED, new FeedItem("t",
        "https://www.aihero.dev/post?ref=feed", AFTER_FLOOR))).isFalse();
    assertThat(AiHeroScraper.isCandidate(FEED, new FeedItem("t",
        "not a url", AFTER_FLOOR))).isFalse();
  }

  @Test
  void postBody_returnsTheMarkdownBodyOfPost() {
    assertThat(AiHeroScraper.postBody(POST_TWIN))
        .startsWith("Build the thinnest slice")
        .contains("## Why it works")
        .doesNotContain("updatedAt")
        .doesNotContain("---");
  }

  @Test
  void postBody_acceptsArticleAndCrlf() {
    String article = POST_TWIN.replace("type: \"post\"", "type: \"article\"")
        .replace("\n", "\r\n");

    assertThat(AiHeroScraper.postBody(article)).startsWith("Build the thinnest slice");
  }

  @Test
  void postBody_rejectsTwinsThatAreNotPosts() {
    String list = POST_TWIN.replace("type: \"post\"", "type: \"list\"");
    String workshop = POST_TWIN.replace("type: \"post\"", "type: \"workshop\"");
    // The /skills catalogue's twin has frontmatter but no type at all.
    String untyped = """
        ---
        title: "AI Skills for Real Engineers"
        url: "https://www.aihero.dev/skills"
        page: 1
        ---

        # AI Skills for Real Engineers
        """;

    assertThat(AiHeroScraper.postBody(list)).isNull();
    assertThat(AiHeroScraper.postBody(workshop)).isNull();
    assertThat(AiHeroScraper.postBody(untyped)).isNull();
  }

  @Test
  void postBody_rejectsAnythingThatIsNotTwin() {
    assertThat(AiHeroScraper.postBody(null)).isNull();
    assertThat(AiHeroScraper.postBody("Not Found")).isNull();
    assertThat(AiHeroScraper.postBody("---\ntype: \"post\"\nno closing fence")).isNull();
    assertThat(AiHeroScraper.postBody("---\ntype: \"post\"\n---\n   \n")).isNull();
    // A nested key that happens to be called type is not the page's type.
    assertThat(AiHeroScraper.postBody("---\nmeta:\n  type: \"post\"\n---\nBody")).isNull();
  }

  @Test
  void ogImage_readsTheAbsoluteCardUrl() {
    var doc = Jsoup.parse("""
        <html><head><meta property="og:image"
          content="/api/og?resource=tracer-bullets&amp;updatedAt=2026-01-22T13:21:37.803Z"/>
        </head></html>
        """, "https://www.aihero.dev/tracer-bullets");

    assertThat(AiHeroScraper.ogImage(doc)).isEqualTo(
        "https://www.aihero.dev/api/og?resource=tracer-bullets&updatedAt=2026-01-22T13:21:37.803Z");
    assertThat(AiHeroScraper.ogImage(Jsoup.parse("<html></html>"))).isNull();
  }

  @Test
  void collect_keepsOnlyPostsAndAttributesThemToMattPocock() {
    StubbedScraper scraper = new StubbedScraper(Map.of(
        "https://www.aihero.dev/tracer-bullets.md", POST_TWIN,
        "https://www.aihero.dev/ai-engineer-roadmap.md",
        POST_TWIN.replace("type: \"post\"", "type: \"list\"")));

    List<ScrapedContent> results = scraper.collect(FEED, List.of(
        item("/tracer-bullets", AFTER_FLOOR),
        item("/ai-engineer-roadmap", AFTER_FLOOR),
        item("/newsletter", AFTER_FLOOR),
        item("/ai-coding-dictionary/turn", AFTER_FLOOR)));

    assertThat(results).singleElement().satisfies(post -> {
      assertThat(post.url()).isEqualTo("https://www.aihero.dev/tracer-bullets");
      assertThat(post.title()).isEqualTo("title for /tracer-bullets");
      assertThat(post.content()).startsWith("Build the thinnest slice");
      assertThat(post.author()).isEqualTo("Matt Pocock");
      assertThat(post.publishedDate()).isEqualTo(AFTER_FLOOR);
      assertThat(post.imageUrl()).isEqualTo("https://www.aihero.dev/tracer-bullets.png");
      assertThat(post.isEvent()).isFalse();
    });
    // The dictionary entry was never fetched; /newsletter has no twin.
    assertThat(scraper.twinRequests).containsExactly(
        "https://www.aihero.dev/tracer-bullets.md",
        "https://www.aihero.dev/ai-engineer-roadmap.md",
        "https://www.aihero.dev/newsletter.md");
    assertThat(scraper.imageRequests).containsExactly("https://www.aihero.dev/tracer-bullets");
  }

  @Test
  void collect_fetchesNothingForPostAlreadyHeldOrListedTwice() {
    when(articleRepository.existsByOriginalUrl("https://www.aihero.dev/tracer-bullets"))
        .thenReturn(true);
    StubbedScraper scraper = new StubbedScraper(Map.of(
        "https://www.aihero.dev/what-are-tokens.md", POST_TWIN));

    List<ScrapedContent> results = scraper.collect(FEED, List.of(
        item("/tracer-bullets", AFTER_FLOOR),
        item("/what-are-tokens", AFTER_FLOOR),
        item("/what-are-tokens", AFTER_FLOOR.plusSeconds(60))));

    assertThat(results).hasSize(1);
    assertThat(scraper.twinRequests).containsExactly("https://www.aihero.dev/what-are-tokens.md");
  }

  private static FeedItem item(final String path, final Instant published) {
    return new FeedItem("title for " + path, "https://www.aihero.dev" + path, published);
  }

  /** Answers twin and page requests from memory and records what was asked for. */
  private final class StubbedScraper extends AiHeroScraper {

    private final Map<String, String> twins;
    private final List<String> twinRequests = new ArrayList<>();
    private final List<String> imageRequests = new ArrayList<>();

    StubbedScraper(final Map<String, String> twins) {
      super(articleRepository, 0);
      this.twins = twins;
    }

    @Override
    String fetchTwin(final String url) {
      twinRequests.add(url);
      return twins.get(url);
    }

    @Override
    String fetchOgImage(final String url) {
      imageRequests.add(url);
      return url + ".png";
    }
  }
}
