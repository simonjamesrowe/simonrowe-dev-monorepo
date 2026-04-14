package com.simonrowe.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.PromptRunner.Creating;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.ScraperFactory;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSource.ScrapeStrategy;
import com.simonrowe.aggregation.ContentSource.SourceType;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.BlogImageGenerationService;
import com.simonrowe.media.ExternalImageDownloader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentAggregationAgentTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private AggregatedArticleRepository articleRepository;
  @Mock private AggregatedEventRepository eventRepository;
  @Mock private ScraperFactory scraperFactory;
  @Mock private SitemapHtmlScraper htmlScraper;
  @Mock private Ai ai;
  @Mock private ContentChangePublisher changePublisher;
  @Mock private ExternalImageDownloader imageDownloader;
  @Mock private BlogImageGenerationService blogImageGenerationService;

  private PromptRunner promptRunner;
  @SuppressWarnings("rawtypes")
  private Creating creating;

  private ContentAggregationAgent agent;

  private static final ContentSource ACTIVE_SOURCE =
      new ContentSource(
          "src1", "Test Blog", "https://example.com",
          "https://example.com/feed", null,
          SourceType.BLOG, ScrapeStrategy.RSS,
          true, null, null);

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    creating = mock(Creating.class);

    lenient().when(ai.withLlm("gpt-4o-mini"))
        .thenReturn(promptRunner);
    lenient().when(promptRunner.creating(
        ContentClassification.class)).thenReturn(creating);

    agent = new ContentAggregationAgent(
        sourceRepository, articleRepository,
        eventRepository, scraperFactory, htmlScraper, ai,
        changePublisher, imageDownloader, blogImageGenerationService);
  }

  @Test
  void runAggregation_processesActiveSourcesOnly() {
    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of());

    agent.runAggregation();

    verify(sourceRepository).findByActiveTrue();
    verify(scraperFactory, never()).scrape(any());
  }

  @Test
  void runAggregation_skipsExistingContent() {
    ScrapedContent existing = new ScrapedContent(
        "Old Post", "https://example.com/old",
        "Some content already seen",
        Instant.now(), "Author", null, false);

    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenReturn(List.of(existing));
    when(articleRepository.existsByOriginalUrl(
        "https://example.com/old")).thenReturn(true);

    agent.runAggregation();

    verify(articleRepository, never()).save(any());
    verify(eventRepository, never()).save(any());
  }

  @Test
  void runAggregation_savesNewArticle() {
    ContentClassification articleClassification =
        new ContentClassification(
            "article", "A great summary of the post.",
            null, null, null, null);
    ScrapedContent content = new ScrapedContent(
        "New Post", "https://example.com/new",
        "This is a long enough content string to pass the "
            + "fifty character threshold for classification.",
        Instant.now(), "Jane Doe",
        "https://example.com/img.jpg", false);
    AggregatedArticle savedArticle = new AggregatedArticle(
        "art1", content.title(), ACTIVE_SOURCE.name(),
        ACTIVE_SOURCE.baseUrl(), content.url(),
        "A great summary of the post.", content.content(),
        content.author(), content.publishedDate(),
        Instant.now(), true, content.imageUrl());

    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url()))
        .thenReturn(false);
    when(eventRepository.existsByOriginalUrl(content.url()))
        .thenReturn(false);
    when(creating.fromPrompt(anyString()))
        .thenReturn(articleClassification);
    when(imageDownloader.downloadAndStore(content.imageUrl()))
        .thenReturn(null);
    when(articleRepository.save(any()))
        .thenReturn(savedArticle);

    agent.runAggregation();

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    assertThat(captor.getValue().title())
        .isEqualTo("New Post");
    assertThat(captor.getValue().originalUrl())
        .isEqualTo("https://example.com/new");
    verify(eventRepository, never()).save(any());
    verify(changePublisher).publishCreated(
        ContentType.AGGREGATED_ARTICLE, "art1");
  }

  @Test
  void runAggregation_savesNewEvent() {
    ContentClassification eventClassification =
        new ContentClassification(
            "event", "An upcoming Java meetup.",
            "2026-06-15T18:00:00Z", "Tech Hub",
            "London", null);
    ScrapedContent content = new ScrapedContent(
        "Java Meetup", "https://example.com/meetup",
        "Join us for an evening of Java talks and "
            + "networking at Tech Hub London.",
        null, null, null, false);
    AggregatedEvent savedEvent = new AggregatedEvent(
        "evt1", content.title(), ACTIVE_SOURCE.name(),
        content.url(), "An upcoming Java meetup.",
        content.content(),
        Instant.parse("2026-06-15T18:00:00Z"),
        null, "Tech Hub", "London",
        Instant.now(), true);

    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url()))
        .thenReturn(false);
    when(eventRepository.existsByOriginalUrl(content.url()))
        .thenReturn(false);
    when(creating.fromPrompt(anyString()))
        .thenReturn(eventClassification);
    when(eventRepository.save(any()))
        .thenReturn(savedEvent);

    agent.runAggregation();

    ArgumentCaptor<AggregatedEvent> captor =
        ArgumentCaptor.forClass(AggregatedEvent.class);
    verify(eventRepository).save(captor.capture());
    assertThat(captor.getValue().title())
        .isEqualTo("Java Meetup");
    assertThat(captor.getValue().venue())
        .isEqualTo("Tech Hub");
    assertThat(captor.getValue().location())
        .isEqualTo("London");
    verify(articleRepository, never()).save(any());
    verify(changePublisher).publishCreated(
        ContentType.AGGREGATED_EVENT, "evt1");
  }

  @Test
  void runAggregation_updatesSourceTimestampOnSuccess() {
    ScrapedContent content = new ScrapedContent(
        "Post", "https://example.com/post",
        "Short", null, null, null, false);

    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenReturn(List.of(content));
    when(articleRepository.existsByOriginalUrl(content.url()))
        .thenReturn(true);

    agent.runAggregation();

    ArgumentCaptor<ContentSource> captor =
        ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    ContentSource saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(ACTIVE_SOURCE.id());
    assertThat(saved.lastFetchedAt()).isNotNull();
    assertThat(saved.lastError()).isNull();
  }

  @Test
  void runAggregation_recordsErrorOnFailure() {
    when(sourceRepository.findByActiveTrue())
        .thenReturn(List.of(ACTIVE_SOURCE));
    when(scraperFactory.scrape(ACTIVE_SOURCE))
        .thenThrow(new RuntimeException("Connection refused"));

    agent.runAggregation();

    ArgumentCaptor<ContentSource> captor =
        ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    ContentSource saved = captor.getValue();
    assertThat(saved.id()).isEqualTo(ACTIVE_SOURCE.id());
    assertThat(saved.lastError())
        .isEqualTo("Connection refused");
    assertThat(saved.lastFetchedAt())
        .isEqualTo(ACTIVE_SOURCE.lastFetchedAt());
  }

  @Test
  void classifyAndSummarize_returnsDefaultsForShortContent() {
    ScrapedContent shortContent = new ScrapedContent(
        "Tiny", "https://example.com/tiny",
        "Too short", null, null, null, false);

    ContentClassification result =
        agent.classifyAndSummarize(shortContent);

    assertThat(result.type()).isEqualTo("article");
    assertThat(result.summary()).isEqualTo("Tiny");
    assertThat(result.eventDate()).isNull();
    assertThat(result.venue()).isNull();
    assertThat(result.location()).isNull();
  }

  @Test
  void classifyAndSummarize_returnsDefaultsForShortEvent() {
    ScrapedContent shortEvent = new ScrapedContent(
        "Tiny Event", "https://example.com/event",
        "Short", null, null, null, true);

    ContentClassification result =
        agent.classifyAndSummarize(shortEvent);

    assertThat(result.type()).isEqualTo("event");
    assertThat(result.summary()).isEqualTo("Tiny Event");
  }

  @Test
  void classifyAndSummarize_returnsDefaultsOnLlmFailure() {
    ScrapedContent content = new ScrapedContent(
        "Failing Post", "https://example.com/fail",
        "This content is definitely long enough to trigger "
            + "the LLM classification path.",
        null, null, null, false);

    when(creating.fromPrompt(anyString()))
        .thenThrow(new RuntimeException("LLM unavailable"));

    ContentClassification result =
        agent.classifyAndSummarize(content);

    assertThat(result.type()).isEqualTo("article");
    assertThat(result.summary()).isEqualTo("Failing Post");
    assertThat(result.eventDate()).isNull();
  }
}
