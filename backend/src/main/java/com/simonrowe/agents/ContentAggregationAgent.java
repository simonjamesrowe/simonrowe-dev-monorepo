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
import org.springframework.stereotype.Service;

@Service
public class ContentAggregationAgent {

  private static final Logger log =
      LoggerFactory.getLogger(ContentAggregationAgent.class);

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
      if (source.sourceType() == ContentSource.SourceType.EVENTS
          || content.isEvent()) {
        processEvent(source, content);
      } else {
        processArticle(source, content);
      }
    }
  }

  private void processArticle(final ContentSource source, final ScrapedContent content) {
    if (articleRepository.existsByOriginalUrl(content.url())) {
      return;
    }

    String summary = summarize(content.title(), content.content());
    String localImageUrl = imageDownloader.downloadAndStore(content.imageUrl());
    AggregatedArticle article = new AggregatedArticle(
        null, content.title(), source.name(), source.baseUrl(),
        content.url(), summary, content.content(), content.author(),
        content.publishedDate(), Instant.now(), true,
        localImageUrl != null ? localImageUrl : content.imageUrl());

    AggregatedArticle saved = articleRepository.save(article);
    changePublisher.publishCreated(ContentType.AGGREGATED_ARTICLE, saved.id());
    log.info("Saved article: {}", saved.title());
  }

  private void processEvent(final ContentSource source, final ScrapedContent content) {
    if (eventRepository.existsByOriginalUrl(content.url())) {
      return;
    }

    String summary = summarize(content.title(), content.content());
    AggregatedEvent event = new AggregatedEvent(
        null, content.title(), source.name(), content.url(),
        summary, content.content(),
        content.publishedDate() != null ? content.publishedDate() : Instant.now(),
        null, null, null, Instant.now(), true);

    AggregatedEvent saved = eventRepository.save(event);
    changePublisher.publishCreated(ContentType.AGGREGATED_EVENT, saved.id());
    log.info("Saved event: {}", saved.title());
  }

  private String summarize(final String title, final String content) {
    if (content == null || content.length() < 50) {
      return title;
    }
    try {
      String truncated = content.length() > 3000
          ? content.substring(0, 3000) : content;
      ChatClient client = chatClientBuilder.build();
      return client.prompt()
          .user("Summarize this article in 2-3 sentences. Be concise and factual. "
              + "Article title: " + title + "\n\nArticle content: " + truncated)
          .call()
          .content();
    } catch (Exception e) {
      log.warn("Summarization failed for: {}. Using title as summary.", title, e);
      return title;
    }
  }
}
