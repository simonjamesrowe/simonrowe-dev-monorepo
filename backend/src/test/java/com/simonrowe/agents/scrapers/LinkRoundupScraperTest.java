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
