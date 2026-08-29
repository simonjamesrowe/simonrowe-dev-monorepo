package com.simonrowe.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.blog.Blog;
import com.simonrowe.migration.changeunits.V029CreateShortLinksAndBackfill;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Asserts on the actual HTML, not on a redirect.
 *
 * <p>That distinction is the whole endpoint: a crawler follows a {@code 302} to the
 * single-page app, finds no metadata, and the link unfurls as the bare site title with no
 * error anywhere. A status assertion alone would pass for the broken version, so every
 * case here checks the body too.
 */
class ShortLinkControllerTest extends AbstractIntegrationTest {

  private static final String BASE = "https://simonrowe.dev";
  private static final String BROWSER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
          + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
  private static final String SLACKBOT_AGENT =
      "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)";

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShortLinkRepository shortLinkRepository;

  @Autowired
  private ShortLinkService shortLinkService;

  @BeforeEach
  @AfterEach
  void reset() {
    mongoTemplate.getCollection("short_links").drop();
    mongoTemplate.getCollection("blogs").drop();
    mongoTemplate.getCollection("aggregated_articles").drop();
    mongoTemplate.getCollection("aggregated_events").drop();
    V029CreateShortLinksAndBackfill.createIndexes(mongoTemplate);
  }

  // ---------------------------------------------------------------- blog

  @Test
  void servesTheShareDocumentForBlogPosts() throws Exception {
    seedBlog("blog-1", "Exactly-once semantics", "What the guarantee buys you",
        "/uploads/kafka-large.png");
    String slug = shortLinkService.ensureFor(
        ShortLinkContentType.BLOG, "blog-1", "Exactly-once semantics");

    String html = mockMvc.perform(get("/s/" + slug).header("User-Agent", BROWSER_AGENT))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        // Never a redirect: a crawler would follow it to the SPA and find no metadata.
        .andExpect(header().doesNotExist("Location"))
        .andReturn().getResponse().getContentAsString();

    assertThat(html)
        .contains("<meta property=\"og:title\" content=\"Exactly-once semantics\">")
        .contains("<meta property=\"og:description\" "
            + "content=\"What the guarantee buys you\">")
        .contains("<meta property=\"og:image\" content=\""
            + BASE + "/uploads/kafka-large.png\">")
        .contains("<meta property=\"og:url\" content=\"" + BASE + "/blogs/blog-1\">")
        .contains("<meta property=\"og:type\" content=\"article\">")
        .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
        .contains("<link rel=\"canonical\" href=\"" + BASE + "/blogs/blog-1\">");
  }

  // ---------------------------------------------------------------- destinations

  @Test
  void sendsBlogLinksToThePostsOwnPage() throws Exception {
    seedBlog("blog-1", "A post", "About things", null);
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    assertThat(canonicalOf(slug)).isEqualTo(BASE + "/blogs/blog-1");
  }

  @Test
  void sendsAnArticleLinkToTheFirstPartySummaryPanel() throws Exception {
    // Not to the publisher: the summary and the narration audio are the first-party value,
    // and a share that bypassed them would be a share of somebody else's page.
    seedArticle("article-1", "Spring AI goes GA", "A release worth reading", null);
    String slug =
        shortLinkService.ensureFor(ShortLinkContentType.ARTICLE, "article-1", "Spring AI goes GA");

    assertThat(canonicalOf(slug)).isEqualTo(BASE + "/news-events?article=article-1");
  }

  @Test
  void sendsAnEventLinkToTheNewsAndEventsPage() throws Exception {
    seedEvent("event-1", "Devoxx UK 2026", "Three days in London");
    String slug =
        shortLinkService.ensureFor(ShortLinkContentType.EVENT, "event-1", "Devoxx UK 2026");

    assertThat(canonicalOf(slug)).isEqualTo(BASE + "/news-events?event=event-1");
  }

  // ---------------------------------------------------------------- og:image

  @Test
  void makesAnUploadedBlogImageAbsolute() throws Exception {
    seedBlog("blog-1", "A post", "About things", "/uploads/hero.png");
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    assertThat(ogImageOf(slug)).isEqualTo(BASE + "/uploads/hero.png");
  }

  @Test
  void hotlinksAnArticlesPublisherImage() throws Exception {
    seedArticle("article-1", "A story", "About things", "https://cdn.example.com/story.jpg");
    String slug =
        shortLinkService.ensureFor(ShortLinkContentType.ARTICLE, "article-1", "A story");

    assertThat(ogImageOf(slug)).isEqualTo("https://cdn.example.com/story.jpg");
  }

  @Test
  void fallsBackToTheShareCardWhenTheContentHasNoImage() throws Exception {
    seedEvent("event-1", "Devoxx UK 2026", "Three days in London");
    String slug =
        shortLinkService.ensureFor(ShortLinkContentType.EVENT, "event-1", "Devoxx UK 2026");

    assertThat(ogImageOf(slug)).isEqualTo(BASE + "/images/share-card.png");
  }

  @Test
  void alwaysEmitsAnAbsoluteOgImage() throws Exception {
    // The failure this guards is silent — a crawler drops a relative og:image without
    // complaining, and the feature looks broken with nothing in the logs.
    seedBlog("blog-1", "With upload", "x", "/uploads/a.png");
    seedArticle("article-1", "With absolute", "x", "https://cdn.example.com/a.jpg");
    seedEvent("event-1", "With nothing", "x");

    List<String> slugs = List.of(
        shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "With upload"),
        shortLinkService.ensureFor(ShortLinkContentType.ARTICLE, "article-1", "With absolute"),
        shortLinkService.ensureFor(ShortLinkContentType.EVENT, "event-1", "With nothing"));

    for (String slug : slugs) {
      assertThat(ogImageOf(slug)).as("og:image for %s", slug).startsWith("http");
    }
  }

  // ---------------------------------------------------------------- not found

  @Test
  void returnsTheThemedNotFoundPageForAnUnknownSlug() throws Exception {
    String html = mockMvc.perform(get("/s/no-such-slug"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        // Never a redirect to /: a typo that lands somewhere plausible looks like a
        // working link, and whoever shared it never finds out.
        .andExpect(header().doesNotExist("Location"))
        .andReturn().getResponse().getContentAsString();

    assertThat(html)
        .contains("This link doesn't go anywhere")
        .contains("<style>")
        .doesNotContain("og:title");
  }

  @Test
  void returnsNotFoundWhenTheContentHasBeenDeleted() throws Exception {
    // An orphaned link. This feature deliberately does not reclaim slugs, so the link
    // survives the content; a share card describing something gone would be worse.
    seedBlog("blog-1", "A post", "About things", null);
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");
    mongoTemplate.getCollection("blogs").drop();

    mockMvc.perform(get("/s/" + slug))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Location"));
  }

  // ---------------------------------------------------------------- click counting

  @Test
  void countsHumanOpens() throws Exception {
    seedBlog("blog-1", "A post", "About things", null);
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    mockMvc.perform(get("/s/" + slug).header("User-Agent", BROWSER_AGENT))
        .andExpect(status().isOk());

    assertThat(clickCountOf(slug)).isEqualTo(1L);
    assertThat(shortLinkRepository.findById(slug).orElseThrow().lastClickedAt()).isNotNull();
  }

  @Test
  void doesNotCountAnUnfurlerFetch() throws Exception {
    // One paste into a Slack channel fetches this once before any human clicks it, and
    // LinkedIn, WhatsApp and iMessage do the same. Without the filter most of the count
    // would be robots reading metadata.
    seedBlog("blog-1", "A post", "About things", null);
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    mockMvc.perform(get("/s/" + slug).header("User-Agent", SLACKBOT_AGENT))
        .andExpect(status().isOk());
    mockMvc.perform(get("/s/" + slug).header("User-Agent", "facebookexternalhit/1.1"))
        .andExpect(status().isOk());

    assertThat(clickCountOf(slug)).isZero();
  }

  @Test
  void servesAnUnfurlerTheSameDocumentAsPeople() throws Exception {
    // The user agent decides whether to count, never what to return — a missed bot must
    // only inflate a statistic, never break a preview.
    seedBlog("blog-1", "A post", "About things", "/uploads/a.png");
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    String forHuman = bodyOf(slug, BROWSER_AGENT);
    String forBot = bodyOf(slug, SLACKBOT_AGENT);

    assertThat(forBot).isEqualTo(forHuman);
  }

  @Test
  void stillServesTheDocumentWhenTheUserAgentIsAbsent() throws Exception {
    seedBlog("blog-1", "A post", "About things", null);
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");

    mockMvc.perform(get("/s/" + slug))
        .andExpect(status().isOk());

    // No agent counts as a person: more likely a stripped-down browser than a robot.
    assertThat(clickCountOf(slug)).isEqualTo(1L);
  }

  // ---------------------------------------------------------------- helpers

  private String bodyOf(final String slug, final String userAgent) throws Exception {
    return mockMvc.perform(get("/s/" + slug).header("User-Agent", userAgent))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  private String canonicalOf(final String slug) throws Exception {
    return extract(bodyOf(slug, BROWSER_AGENT), "<link rel=\"canonical\" href=\"");
  }

  private String ogImageOf(final String slug) throws Exception {
    return extract(bodyOf(slug, BROWSER_AGENT), "<meta property=\"og:image\" content=\"");
  }

  private static String extract(final String html, final String prefix) {
    int start = html.indexOf(prefix);
    assertThat(start).as("expected to find %s in the document", prefix).isNotNegative();
    int valueStart = start + prefix.length();
    return html.substring(valueStart, html.indexOf('"', valueStart));
  }

  private long clickCountOf(final String slug) {
    return shortLinkRepository.findById(slug).orElseThrow().clickCount();
  }

  private void seedBlog(final String id, final String title, final String description,
      final String imageUrl) {
    mongoTemplate.save(new Blog(id, title, description, "content", true, imageUrl,
        Instant.now(), Instant.now(), List.of(), List.of(), null), "blogs");
  }

  private void seedArticle(final String id, final String title, final String summary,
      final String imageUrl) {
    mongoTemplate.save(new AggregatedArticle(id, title, "Source",
        "https://example.com", "https://example.com/" + id, summary, "full", "Author",
        Instant.now(), Instant.now(), true, imageUrl), "aggregated_articles");
  }

  private void seedEvent(final String id, final String title, final String summary) {
    mongoTemplate.save(new AggregatedEvent(id, title, "Source",
        "https://example.com/" + id, summary, "description", Instant.now(), null,
        "Venue", "London", Instant.now(), true), "aggregated_events");
  }
}
