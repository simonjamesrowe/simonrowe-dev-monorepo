package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.time.Instant;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LinkRoundupScraperTest {

  @Mock private SitemapHtmlScraper htmlScraper;
  @Mock private AggregatedArticleRepository articleRepository;
  @InjectMocks private LinkRoundupScraper scraper;

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
  void extractLinks_keepsQueryStringsThatIdentifyTheTarget() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li><a href="https://www.youtube.com/watch?v=A">Talk One</a> &mdash; first talk</li>
          <li><a href="https://www.youtube.com/watch?v=B">Talk Two</a> &mdash; second talk</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(2);
    assertThat(links.get(0).url()).isEqualTo("https://www.youtube.com/watch?v=A");
    assertThat(links.get(1).url()).isEqualTo("https://www.youtube.com/watch?v=B");
  }

  @Test
  void extractLinks_stillNormalisesQuerylessTargets() {
    Document doc = Jsoup.parse("""
        <html><body><div class="news-bar"><ul>
          <li><a href="https://spring.io/blog/spring-ai-2-0-0/">Spring AI 2.0.0</a> \
        &mdash; the release announcement</li>
        </ul></div></body></html>
        """, "https://ai4jvm.com");

    List<LinkRoundupScraper.RoundupLink> links = scraper.extractLinks(doc);

    assertThat(links).hasSize(1);
    assertThat(links.get(0).url()).isEqualTo("https://spring.io/blog/spring-ai-2-0-0");
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

    ScrapedContent result = scraper.toContent(EMBABEL);

    assertThat(result.title()).isEqualTo("Release v1.0.0 · embabel/embabel-agent");
    assertThat(result.content()).isEqualTo("Full release notes body text");
    assertThat(result.publishedDate()).isEqualTo(Instant.parse("2026-07-20T00:00:00Z"));
    assertThat(result.author()).isEqualTo("embabel");
    assertThat(result.imageUrl()).isEqualTo("https://opengraph.githubassets.com/card.png");
    assertThat(result.isEvent()).isFalse();
    assertThat(result.url()).isEqualTo(EMBABEL.url());
  }

  /**
   * The detail's own URL is discarded even when it differs: scrapeArticlePage normalises
   * again internally, so keeping it would leave the stored originalUrl out of step with
   * the dedup key and the target would be re-fetched on every run.
   */
  @Test
  void toContent_keepsTheLinkUrlWhenTheTargetPageReportsAnother() {
    LinkRoundupScraper.RoundupLink talk = new LinkRoundupScraper.RoundupLink(
        "A Conference Talk", "https://www.youtube.com/watch?v=A", "a talk about agents");
    when(htmlScraper.scrapeArticlePagePublic(talk.url())).thenReturn(new ScrapedContent(
        "A Conference Talk - YouTube", "https://www.youtube.com/watch", "transcript",
        null, null, null, false));

    assertThat(scraper.toContent(talk).url()).isEqualTo("https://www.youtube.com/watch?v=A");
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
}
