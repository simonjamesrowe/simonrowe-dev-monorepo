package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArticleSectionWriterTest {

  private static final String MODEL = "gpt-5.6-luna";

  @Mock private SitemapHtmlScraper scraper;
  @Mock private Ai ai;

  private PromptRunner promptRunner;
  private AssistantMessage assistantMessage;
  private ArticleSectionWriter writer;

  private static final AggregatedArticle ARTICLE = new AggregatedArticle(
      "art-1", "Spring Boot 4 Released", "InfoQ",
      "https://infoq.com", "https://infoq.com/spring-boot-4",
      "Stored summary.", "Stored full content that is long enough to use.",
      "Jane Doe", Instant.now(), Instant.now(), true, null);

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    assistantMessage = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(MODEL)).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(assistantMessage);
    lenient().when(assistantMessage.getContent()).thenReturn("Generated prose.");
    writer = new ArticleSectionWriter(scraper, ai, MODEL);
  }

  @Test
  void usesFreshlyScrapedContentWhenScrapeSucceeds() {
    when(scraper.scrapeArticlePagePublic("https://infoq.com/spring-boot-4"))
        .thenReturn(new ScrapedContent(
            "Spring Boot 4 Released", "https://infoq.com/spring-boot-4",
            "Freshly scraped body text.", Instant.now(), "Jane Doe", null, false));

    DigestSection section = writer.write(ARTICLE);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Freshly scraped body text.");
    assertThat(section.body()).isEqualTo("Generated prose.");
    assertThat(section.fallback()).isFalse();
    assertThat(section.articleId()).isEqualTo("art-1");
    assertThat(section.title()).isEqualTo("Spring Boot 4 Released");
    assertThat(section.url()).isEqualTo("https://infoq.com/spring-boot-4");
  }

  @Test
  void fallsBackToStoredFullContentWhenScrapeReturnsNull() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);

    DigestSection section = writer.write(ARTICLE);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Stored full content that is long enough to use.");
    assertThat(section.fallback()).isFalse();
  }

  @Test
  void fallsBackToStoredSummaryWhenScrapeAndFullContentAreEmpty() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    AggregatedArticle noContent = new AggregatedArticle(
        "art-2", "Thin Article", "RSS Source",
        "https://rss.com", "https://rss.com/thin",
        "Only a stored summary.", "", null,
        Instant.now(), Instant.now(), true, null);

    DigestSection section = writer.write(noContent);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent())
        .contains("Only a stored summary.");
    assertThat(section.fallback()).isFalse();
  }

  @Test
  void fallsBackToStoredSummaryWhenLlmThrows() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    when(promptRunner.respond(anyList()))
        .thenThrow(new RuntimeException("upstream 500"));

    DigestSection section = writer.write(ARTICLE);

    assertThat(section.body()).isEqualTo("Stored summary.");
    assertThat(section.fallback()).isTrue();
  }

  @Test
  void truncatesSourceTextToTwelveThousandCharacters() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);
    AggregatedArticle huge = new AggregatedArticle(
        "art-3", "Huge", "Source", "https://src.com", "https://src.com/huge",
        "Summary.", "x".repeat(20_000), null,
        Instant.now(), Instant.now(), true, null);

    writer.write(huge);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(promptRunner).respond(captor.capture());
    assertThat(captor.getValue().get(0).getContent()).doesNotContain("x".repeat(12_001));
  }
}
