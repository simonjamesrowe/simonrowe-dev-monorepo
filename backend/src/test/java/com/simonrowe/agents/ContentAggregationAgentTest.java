package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSource.ScrapeStrategy;
import com.simonrowe.aggregation.ContentSource.SourceType;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.ScraperFactory;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.ExternalImageDownloader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

@ExtendWith(MockitoExtension.class)
class ContentAggregationAgentTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private AggregatedEventRepository eventRepository;
  @Mock private ScraperFactory scraperFactory;
  @Mock private ChatClient.Builder chatClientBuilder;
  @Mock private ContentChangePublisher changePublisher;
  @Mock private ExternalImageDownloader imageDownloader;

  private ChatClient chatClient;
  private ChatClientRequestSpec promptSpec;
  private CallResponseSpec callResponse;

  private ContentAggregationAgent agent;

  private static final ContentSource ACTIVE_SOURCE = new ContentSource(
      "src1", "Test Blog", "https://example.com", "https://example.com/feed",
      null, SourceType.BLOG, ScrapeStrategy.RSS, true, null, null);

  @BeforeEach
  void setUp() {
    chatClient = mock(ChatClient.class);
    promptSpec = mock(ChatClientRequestSpec.class);
    callResponse = mock(CallResponseSpec.class);

    // Lenient: only tests that reach classifyAndSummarize with long-enough content use this chain.
    lenient().when(chatClientBuilder.build()).thenReturn(chatClient);
    lenient().when(chatClient.prompt()).thenReturn(promptSpec);
    lenient().when(promptSpec.user(anyString())).thenReturn(promptSpec);
    lenient().when(promptSpec.call()).thenReturn(callResponse);

    agent = new ContentAggregationAgent(
        sourceRepository, articleRepository, eventRepository,
        scraperFactory, chatClientBuilder, changePublisher, imageDownloader);
  }

  @Test
  void runAggregation_processesActiveSourcesOnly() {
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of());

    agent.runAggregation();

    verify(sourceRepository).findByActiveTrue();
    verify(scraperFactory, never()).scrape(any());
  }

  @Test
  void runAggregation_skipsExistingContent() {
    ScrapedContent existing = new ScrapedContent(
        "Old Post", "https://example.com/old", "Some content already seen",
        Instant.now(), "Author", null, false);

    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE)).thenReturn(List.of(existing));
    when(articleRepository.existsByOriginalUrl("https://example.com/old")).thenReturn(true);

    agent.runAggregation();

    verify(articleRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void runAggregation_savesNewArticle() {
    String articleJson = """
        {
          "type": "article",
          "summary": "A great summary of the post.",
          "eventDate": null,
          "venue": null,
          "location": null,
          "publishedDate": null
        }
        """;
    ScrapedContent content = new ScrapedContent(
        "New Post", "https://example.com/new",
        "This is a long enough content string for classification by the LLM agent.",
        Instant.now(), "Jane Doe", "https://example.com/img.jpg", false);
    AggregatedArticle savedArticle = new AggregatedArticle(
        "art1", content.title(), ACTIVE_SOURCE.name(), ACTIVE_SOURCE.baseUrl(),
        content.url(), "A great summary of the post.", content.content(),
        content.author(), content.publishedDate(), Instant.now(), true, content.imageUrl());

    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE)).thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url())).thenReturn(false);
    when(eventRepository.existsByOriginalUrl(content.url())).thenReturn(false);
    when(callResponse.content()).thenReturn(articleJson);
    when(imageDownloader.downloadAndStore(content.imageUrl())).thenReturn(null);
    when(articleRepository.save(any())).thenReturn(savedArticle);

    agent.runAggregation();

    ArgumentCaptor<AggregatedArticle> captor = ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    assertThat(captor.getValue().title()).isEqualTo("New Post");
    assertThat(captor.getValue().originalUrl()).isEqualTo("https://example.com/new");
    verify(eventRepository, never()).save(any());
    verify(changePublisher).publishCreated(ContentType.AGGREGATED_ARTICLE, "art1");
  }

  @Test
  void runAggregation_savesNewEvent() {
    String eventJson = """
        {
          "type": "event",
          "summary": "An upcoming Java meetup.",
          "eventDate": "2026-06-15T18:00:00Z",
          "venue": "Tech Hub",
          "location": "London",
          "publishedDate": null
        }
        """;
    ScrapedContent content = new ScrapedContent(
        "Java Meetup", "https://example.com/meetup",
        "Join us for an evening of Java talks and networking at Tech Hub London.",
        null, null, null, false);
    AggregatedEvent savedEvent = new AggregatedEvent(
        "evt1", content.title(), ACTIVE_SOURCE.name(), content.url(),
        "An upcoming Java meetup.", content.content(),
        Instant.parse("2026-06-15T18:00:00Z"), null, "Tech Hub", "London",
        Instant.now(), true);

    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE)).thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url())).thenReturn(false);
    when(eventRepository.existsByOriginalUrl(content.url())).thenReturn(false);
    when(callResponse.content()).thenReturn(eventJson);
    when(eventRepository.save(any())).thenReturn(savedEvent);

    agent.runAggregation();

    ArgumentCaptor<AggregatedEvent> captor = ArgumentCaptor.forClass(AggregatedEvent.class);
    verify(eventRepository).save(captor.capture());
    assertThat(captor.getValue().title()).isEqualTo("Java Meetup");
    assertThat(captor.getValue().venue()).isEqualTo("Tech Hub");
    assertThat(captor.getValue().location()).isEqualTo("London");
    verify(articleRepository, never()).save(any());
    verify(changePublisher).publishCreated(ContentType.AGGREGATED_EVENT, "evt1");
  }

  @Test
  void runAggregation_updatesSourceTimestampOnSuccess() {
    ScrapedContent content = new ScrapedContent(
        "Post", "https://example.com/post", "Short", null, null, null, false);

    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE)).thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url())).thenReturn(true);

    agent.runAggregation();

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    ContentSource saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(ACTIVE_SOURCE.id());
    assertThat(saved.lastFetchedAt()).isNotNull();
    assertThat(saved.lastError()).isNull();
  }

  @Test
  void runAggregation_recordsErrorOnFailure() {
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenThrow(new RuntimeException("Connection refused"));

    agent.runAggregation();

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    ContentSource saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(ACTIVE_SOURCE.id());
    assertThat(saved.lastError()).isEqualTo("Connection refused");
    assertThat(saved.lastFetchedAt()).isEqualTo(ACTIVE_SOURCE.lastFetchedAt());
  }

  @Test
  void classifyAndSummarize_returnsDefaultsForShortContent() {
    ScrapedContent shortContent = new ScrapedContent(
        "Tiny", "https://example.com/tiny", "Too short", null, null, null, false);

    ContentClassification result = agent.classifyAndSummarize(shortContent);

    assertThat(result.type()).isEqualTo("article");
    assertThat(result.summary()).isEqualTo("Tiny");
    assertThat(result.eventDate()).isNull();
    assertThat(result.venue()).isNull();
    assertThat(result.location()).isNull();
  }

  @Test
  void classifyAndSummarize_returnsDefaultsForShortEventContent() {
    ScrapedContent shortEvent = new ScrapedContent(
        "Tiny Event", "https://example.com/event", "Short", null, null, null, true);

    ContentClassification result = agent.classifyAndSummarize(shortEvent);

    assertThat(result.type()).isEqualTo("event");
    assertThat(result.summary()).isEqualTo("Tiny Event");
  }

  @Test
  void classifyAndSummarize_returnsDefaultsOnLlmFailure() {
    ScrapedContent content = new ScrapedContent(
        "Failing Post", "https://example.com/fail",
        "This content is definitely long enough to trigger the LLM classification path.",
        null, null, null, false);

    when(callResponse.content()).thenThrow(new RuntimeException("LLM unavailable"));

    ContentClassification result = agent.classifyAndSummarize(content);

    assertThat(result.type()).isEqualTo("article");
    assertThat(result.summary()).isEqualTo("Failing Post");
    assertThat(result.eventDate()).isNull();
  }
}
