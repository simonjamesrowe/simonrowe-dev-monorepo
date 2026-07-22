package com.simonrowe.agents;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.ScraperFactory;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.media.BlogImageGenerationService;
import com.simonrowe.media.ExternalImageDownloader;
import com.simonrowe.media.MediaVariantResolver;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Agent(
    name = "ContentAggregation",
    description = "Scrapes external content sources, classifies items "
        + "using an LLM, and stores articles and events locally"
)
public class ContentAggregationAgent {

  private static final Logger log =
      LoggerFactory.getLogger(ContentAggregationAgent.class);

  // Fix 4: include URL in prompt; truncation limit raised to 5000
  private static final String CLASSIFY_PROMPT = """
      Analyze the following content and respond with JSON matching \
      the schema below.

      1. Classify whether this is a news/blog "article" or an "event" \
      (conference, meetup, workshop, webinar, talk).
      2. Write a concise 2-3 sentence summary.
      3. If it is an event, extract the event date (ISO-8601), venue \
      name, and location/city.
      4. Extract the published date (ISO-8601 format, e.g. \
      "2025-07-23") from the content if mentioned (look for dates \
      near the author name or article header).

      URL: %s
      Title: %s

      Content:
      %s
      """;

  private final ContentSourceRepository sourceRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final ScraperFactory scraperFactory;
  private final SitemapHtmlScraper htmlScraper;
  private final Ai ai;
  private final ContentChangePublisher changePublisher;
  private final ExternalImageDownloader imageDownloader;
  private final BlogImageGenerationService blogImageGenerationService;
  private final MediaVariantResolver mediaVariantResolver;

  public ContentAggregationAgent(
      final ContentSourceRepository sourceRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository,
      final ScraperFactory scraperFactory,
      final SitemapHtmlScraper htmlScraper,
      final Ai ai,
      final ContentChangePublisher changePublisher,
      final ExternalImageDownloader imageDownloader,
      final BlogImageGenerationService blogImageGenerationService,
      final MediaVariantResolver mediaVariantResolver) {
    this.sourceRepository = sourceRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.scraperFactory = scraperFactory;
    this.htmlScraper = htmlScraper;
    this.ai = ai;
    this.changePublisher = changePublisher;
    this.imageDownloader = imageDownloader;
    this.blogImageGenerationService = blogImageGenerationService;
    this.mediaVariantResolver = mediaVariantResolver;
  }

  @Action(description = "Import a single article or event from a URL")
  public String importFromUrl(final String url) {
    String normalizedUrl = normalizeUrl(url);
    boolean alreadyExists =
        articleRepository.existsByOriginalUrl(normalizedUrl)
            || eventRepository.existsByOriginalUrl(normalizedUrl);
    if (alreadyExists) {
      log.info("URL already imported: {}", normalizedUrl);
      return "Already imported: " + normalizedUrl;
    }

    ScrapedContent content =
        htmlScraper.scrapeArticlePagePublic(normalizedUrl);
    if (content == null) {
      log.warn("Failed to scrape URL: {}", normalizedUrl);
      return "Failed to scrape URL (site may block automated access): "
          + normalizedUrl;
    }

    ContentClassification classification =
        classifyAndSummarize(content);

    String sourceName = extractHostName(normalizedUrl);
    ContentSource manualSource = new ContentSource(
        null, sourceName, normalizedUrl, null, null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.HTML_LISTING,
        false, null, null);

    if (classification.isEvent() || content.isEvent()) {
      processEvent(manualSource, content, classification);
    } else {
      processArticle(manualSource, content, classification);
    }
    log.info("Imported from URL: {}", normalizedUrl);
    return "Imported: " + content.title();
  }

  // Fix 6: URL normalization matching the scraper's normalizeUrl logic
  private String normalizeUrl(String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = URI.create(url);
      return new URI(
          uri.getScheme(),
          uri.getHost().toLowerCase(),
          uri.getPath().replaceAll("/+$", ""),
          null, null).toString();
    } catch (Exception e) {
      return url.replaceAll("[?#].*$", "").replaceAll("/+$", "");
    }
  }

  private String extractHostName(final String url) {
    try {
      return java.net.URI.create(url).getHost()
          .replaceFirst("^www\\.", "");
    } catch (Exception e) {
      return "Manual Import";
    }
  }

  @Action(description = "Aggregate content from all active sources")
  public void runAggregation() {
    List<ContentSource> sources = sourceRepository.findByActiveTrue();
    log.info(
        "Starting content aggregation for {} active sources",
        sources.size());

    for (ContentSource source : sources) {
      try {
        processSource(source);
        sourceRepository.save(new ContentSource(
            source.id(), source.name(), source.baseUrl(),
            source.feedUrl(), source.sitemapUrl(),
            source.sourceType(), source.scrapeStrategy(),
            source.active(), Instant.now(), null));
      } catch (Exception e) {
        log.error("Failed to process source: {}", source.name(), e);
        sourceRepository.save(new ContentSource(
            source.id(), source.name(), source.baseUrl(),
            source.feedUrl(), source.sitemapUrl(),
            source.sourceType(), source.scrapeStrategy(),
            source.active(), source.lastFetchedAt(),
            e.getMessage()));
      }
    }
    log.info("Content aggregation complete");
  }

  private void processSource(final ContentSource source) {
    List<ScrapedContent> scraped = scraperFactory.scrape(source);
    log.info("Fetched {} items from {}", scraped.size(), source.name());

    for (ScrapedContent content : scraped) {
      boolean alreadyExists =
          articleRepository.existsByOriginalUrl(content.url())
              || eventRepository.existsByOriginalUrl(content.url());
      if (alreadyExists) {
        continue;
      }

      ContentClassification classification =
          classifyAndSummarize(content);

      if (classification.isEvent()
          || source.sourceType() == ContentSource.SourceType.EVENTS
          || content.isEvent()) {
        processEvent(source, content, classification);
      } else {
        processArticle(source, content, classification);
      }
    }
  }

  private void processArticle(
      final ContentSource source,
      final ScrapedContent content,
      final ContentClassification classification) {
    String localImageUrl =
        imageDownloader.downloadAndStore(content.imageUrl());
    if (localImageUrl == null && content.imageUrl() == null) {
      localImageUrl = blogImageGenerationService.generateAndStore(
          content.title(), classification.summary());
    }
    if (localImageUrl != null) {
      localImageUrl = mediaVariantResolver.resolvePath(
          localImageUrl, "large", "medium", "small", "thumbnail");
    }
    Instant fetchedAt = Instant.now();
    Instant publishedDate = content.publishedDate();
    if (publishedDate == null) {
      publishedDate =
          parseEventDate(classification.publishedDate());
    }
    if (publishedDate == null) {
      log.info("No date from scraper or LLM for '{}', "
          + "fetching detail page", content.title());
      publishedDate =
          htmlScraper.extractPublishedDateFromUrl(content.url());
    }
    if (publishedDate == null) {
      // No date could be discovered; default to the date the article
      // was added so it still surfaces (sorted by publishedDate) on the
      // news page instead of being buried behind dated articles.
      log.info("No date found for '{}'; defaulting to fetch date",
          content.title());
      publishedDate = fetchedAt;
    }
    AggregatedArticle article = new AggregatedArticle(
        null, content.title(), source.name(), source.baseUrl(),
        content.url(), classification.summary(), content.content(),
        content.author(), publishedDate, fetchedAt, true,
        localImageUrl != null
            ? localImageUrl : content.imageUrl());

    AggregatedArticle saved = articleRepository.save(article);
    changePublisher.publishCreated(
        ContentType.AGGREGATED_ARTICLE, saved.id());
    log.info("Saved article: {}", saved.title());
  }

  private void processEvent(
      final ContentSource source,
      final ScrapedContent content,
      final ContentClassification classification) {
    Instant eventDate =
        parseEventDate(classification.eventDate());
    if (eventDate == null) {
      eventDate = content.publishedDate();
    }
    if (eventDate == null) {
      log.info("No date from scraper or LLM for event '{}', "
          + "fetching detail page", content.title());
      eventDate =
          htmlScraper.extractPublishedDateFromUrl(content.url());
    }
    if (eventDate == null) {
      eventDate = Instant.now();
    }

    String venue = content.venue() != null
        ? content.venue() : classification.venue();
    String location = content.location() != null
        ? content.location() : classification.location();

    AggregatedEvent event = new AggregatedEvent(
        null, content.title(), source.name(), content.url(),
        classification.summary(), content.content(),
        eventDate, null, venue, location, Instant.now(), true);

    AggregatedEvent saved = eventRepository.save(event);
    changePublisher.publishCreated(
        ContentType.AGGREGATED_EVENT, saved.id());
    log.info("Saved event: {}", saved.title());
  }

  ContentClassification classifyAndSummarize(
      final ScrapedContent content) {
    if (content.content() == null
        || content.content().length() < 50) {
      return new ContentClassification(
          content.isEvent() ? "event" : "article",
          content.title(), null, null, null, null);
    }
    try {
      // Fix 4: raise truncation limit from 3000 to 5000
      String truncated = content.content().length() > 5000
          ? content.content().substring(0, 5000)
          : content.content();
      // Fix 4: include URL as first format argument
      String prompt = String.format(
          CLASSIFY_PROMPT, content.url(), content.title(), truncated);
      return ai.withLlm("gpt-4o-mini")
          .creating(ContentClassification.class)
          .fromPrompt(prompt);
    } catch (Exception e) {
      log.warn(
          "Classification failed for: {}. Using defaults.",
          content.title(), e);
    }
    return new ContentClassification(
        content.isEvent() ? "event" : "article",
        content.title(), null, null, null, null);
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
