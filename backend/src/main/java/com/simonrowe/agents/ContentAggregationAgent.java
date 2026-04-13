package com.simonrowe.agents;

import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.ScraperFactory;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.ExternalImageDownloader;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service
public class ContentAggregationAgent {

  private static final Logger log =
      LoggerFactory.getLogger(ContentAggregationAgent.class);

  private static final String CLASSIFY_PROMPT = """
      Analyze the following content and respond with JSON matching the schema below.

      1. Classify whether this is a news/blog "article" or an "event" \
      (conference, meetup, workshop, webinar, talk).
      2. Write a concise 2-3 sentence summary.
      3. If it is an event, extract the event date (ISO-8601), venue name, and location/city.
      4. Extract the published date (ISO-8601 format, e.g. "2025-07-23") from the content \
      if mentioned (look for dates near the author name or article header).

      Title: %s

      Content:
      %s

      %s
      """;

  private final ContentSourceRepository sourceRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final ScraperFactory scraperFactory;
  private final ChatClient.Builder chatClientBuilder;
  private final ContentChangePublisher changePublisher;
  private final ExternalImageDownloader imageDownloader;

  public ContentAggregationAgent(
      final ContentSourceRepository sourceRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository,
      final ScraperFactory scraperFactory,
      final ChatClient.Builder chatClientBuilder,
      final ContentChangePublisher changePublisher,
      final ExternalImageDownloader imageDownloader) {
    this.sourceRepository = sourceRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.scraperFactory = scraperFactory;
    this.chatClientBuilder = chatClientBuilder;
    this.changePublisher = changePublisher;
    this.imageDownloader = imageDownloader;
  }

  public void runAggregation() {
    List<ContentSource> sources = sourceRepository.findByActiveTrue();
    log.info("Starting content aggregation for {} active sources", sources.size());

    for (ContentSource source : sources) {
      try {
        processSource(source);
        sourceRepository.save(new ContentSource(
            source.id(), source.name(), source.baseUrl(),
            source.feedUrl(), source.sitemapUrl(), source.sourceType(),
            source.scrapeStrategy(), source.active(),
            Instant.now(), null));
      } catch (Exception e) {
        log.error("Failed to process source: {}", source.name(), e);
        sourceRepository.save(new ContentSource(
            source.id(), source.name(), source.baseUrl(),
            source.feedUrl(), source.sitemapUrl(), source.sourceType(),
            source.scrapeStrategy(), source.active(),
            source.lastFetchedAt(), e.getMessage()));
      }
    }
    log.info("Content aggregation complete");
  }

  private void processSource(final ContentSource source) {
    List<ScrapedContent> scraped = scraperFactory.scrape(source);
    log.info("Fetched {} items from {}", scraped.size(), source.name());

    for (ScrapedContent content : scraped) {
      boolean alreadyExists = articleRepository.existsByOriginalUrl(content.url())
          || eventRepository.existsByOriginalUrl(content.url());
      if (alreadyExists) {
        continue;
      }

      ContentClassification classification = classifyAndSummarize(content);

      if (classification.isEvent()
          || source.sourceType() == ContentSource.SourceType.EVENTS
          || content.isEvent()) {
        processEvent(source, content, classification);
      } else {
        processArticle(source, content, classification);
      }
    }
  }

  private void processArticle(final ContentSource source,
      final ScrapedContent content, final ContentClassification classification) {
    String localImageUrl = imageDownloader.downloadAndStore(content.imageUrl());
    Instant publishedDate = content.publishedDate();
    if (publishedDate == null) {
      publishedDate = parseEventDate(classification.publishedDate());
    }
    AggregatedArticle article = new AggregatedArticle(
        null, content.title(), source.name(), source.baseUrl(),
        content.url(), classification.summary(), content.content(), content.author(),
        publishedDate, Instant.now(), true,
        localImageUrl != null ? localImageUrl : content.imageUrl());

    AggregatedArticle saved = articleRepository.save(article);
    changePublisher.publishCreated(ContentType.AGGREGATED_ARTICLE, saved.id());
    log.info("Saved article: {}", saved.title());
  }

  private void processEvent(final ContentSource source,
      final ScrapedContent content, final ContentClassification classification) {
    Instant eventDate = parseEventDate(classification.eventDate());
    if (eventDate == null) {
      eventDate = content.publishedDate() != null ? content.publishedDate() : Instant.now();
    }

    String venue = content.venue() != null ? content.venue() : classification.venue();
    String location = content.location() != null ? content.location() : classification.location();

    AggregatedEvent event = new AggregatedEvent(
        null, content.title(), source.name(), content.url(),
        classification.summary(), content.content(),
        eventDate, null, venue, location, Instant.now(), true);

    AggregatedEvent saved = eventRepository.save(event);
    changePublisher.publishCreated(ContentType.AGGREGATED_EVENT, saved.id());
    log.info("Saved event: {}", saved.title());
  }

  ContentClassification classifyAndSummarize(final ScrapedContent content) {
    if (content.content() == null || content.content().length() < 50) {
      return new ContentClassification(
          content.isEvent() ? "event" : "article", content.title(), null, null, null, null);
    }
    try {
      String truncated = content.content().length() > 3000
          ? content.content().substring(0, 3000) : content.content();
      BeanOutputConverter<ContentClassification> converter =
          new BeanOutputConverter<>(ContentClassification.class);
      ChatClient client = chatClientBuilder.build();
      String response = client.prompt()
          .user(String.format(CLASSIFY_PROMPT,
              content.title(), truncated, converter.getFormat()))
          .call()
          .content();
      ContentClassification result = converter.convert(response);
      if (result != null) {
        return result;
      }
    } catch (Exception e) {
      log.warn("Classification failed for: {}. Using defaults.", content.title(), e);
    }
    return new ContentClassification(
        content.isEvent() ? "event" : "article", content.title(), null, null, null, null);
  }

  private Instant parseEventDate(final String dateStr) {
    if (dateStr == null || dateStr.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(dateStr);
    } catch (Exception e) {
      try {
        return java.time.LocalDate.parse(dateStr.substring(0, 10))
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
      } catch (Exception ignored) {
        return null;
      }
    }
  }
}
