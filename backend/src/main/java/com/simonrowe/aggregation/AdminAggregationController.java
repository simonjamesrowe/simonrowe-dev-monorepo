package com.simonrowe.aggregation;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.WeeklyDigestAgent;
import com.simonrowe.embedding.EmbeddingService;
import com.simonrowe.search.IndexService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminAggregationController {

  private static final Logger LOG = LoggerFactory.getLogger(AdminAggregationController.class);

  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;
  private final ContentSourceRepository sourceRepository;
  private final ContentAggregationAgent aggregationAgent;
  private final WeeklyDigestAgent digestAgent;
  private final IndexService indexService;
  private final EmbeddingService embeddingService;

  public AdminAggregationController(
      AggregatedArticleRepository articleRepository,
      AggregatedEventRepository eventRepository,
      ContentSourceRepository sourceRepository,
      ContentAggregationAgent aggregationAgent,
      WeeklyDigestAgent digestAgent,
      IndexService indexService,
      EmbeddingService embeddingService) {
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
    this.sourceRepository = sourceRepository;
    this.aggregationAgent = aggregationAgent;
    this.digestAgent = digestAgent;
    this.indexService = indexService;
    this.embeddingService = embeddingService;
  }

  @GetMapping("/news")
  public List<ArticleResponse> listAllArticles() {
    return articleRepository.findAll().stream()
        .map(ArticleResponse::from)
        .toList();
  }

  @PutMapping("/news/{id}/visibility")
  public ArticleResponse updateArticleVisibility(
      @PathVariable String id, @RequestBody Map<String, Boolean> body) {
    AggregatedArticle article = articleRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    boolean visible = body.getOrDefault("visible", true);
    AggregatedArticle updated = new AggregatedArticle(
        article.id(), article.title(), article.sourceName(),
        article.sourceUrl(), article.originalUrl(), article.summary(),
        article.fullContent(), article.author(), article.publishedDate(),
        article.fetchedAt(), visible, article.imageUrl());
    return ArticleResponse.from(articleRepository.save(updated));
  }

  @DeleteMapping("/news/{id}")
  public ResponseEntity<Void> deleteArticle(@PathVariable String id) {
    articleRepository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/events")
  public List<EventResponse> listAllEvents() {
    return eventRepository.findAll().stream()
        .map(EventResponse::from)
        .toList();
  }

  @PutMapping("/events/{id}/visibility")
  public EventResponse updateEventVisibility(
      @PathVariable String id, @RequestBody Map<String, Boolean> body) {
    AggregatedEvent event = eventRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    boolean visible = body.getOrDefault("visible", true);
    AggregatedEvent updated = new AggregatedEvent(
        event.id(), event.title(), event.sourceName(),
        event.originalUrl(), event.summary(), event.description(),
        event.eventDate(), event.eventEndDate(), event.venue(),
        event.location(), event.fetchedAt(), visible);
    return EventResponse.from(eventRepository.save(updated));
  }

  @DeleteMapping("/events/{id}")
  public ResponseEntity<Void> deleteEvent(@PathVariable String id) {
    eventRepository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/content-sources")
  public List<ContentSource> listSources() {
    return sourceRepository.findAll();
  }

  @PutMapping("/content-sources/{id}")
  public ContentSource updateSource(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    ContentSource source = sourceRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    boolean active = body.containsKey("active")
        ? (Boolean) body.get("active") : source.active();
    String feedUrl = body.containsKey("feedUrl")
        ? (String) body.get("feedUrl") : source.feedUrl();
    String sitemapUrl = body.containsKey("sitemapUrl")
        ? (String) body.get("sitemapUrl") : source.sitemapUrl();
    ContentSource updated = new ContentSource(
        source.id(), source.name(), source.baseUrl(), feedUrl,
        sitemapUrl, source.sourceType(), source.scrapeStrategy(),
        active, source.lastFetchedAt(), source.lastError());
    return sourceRepository.save(updated);
  }

  @PostMapping("/aggregation/trigger")
  public ResponseEntity<Map<String, String>> triggerAggregation() {
    Thread.ofVirtual().start(aggregationAgent::runAggregation);
    return ResponseEntity.accepted()
        .body(Map.of("message", "Content aggregation triggered"));
  }

  @PostMapping("/digest/trigger")
  public ResponseEntity<Map<String, String>> triggerDigest() {
    Thread.ofVirtual().start(digestAgent::generateDigest);
    return ResponseEntity.accepted()
        .body(Map.of("message", "Weekly digest generation triggered"));
  }

  @PostMapping("/search/full-sync")
  public ResponseEntity<Map<String, String>> triggerFullSearchSync() {
    Thread.ofVirtual().start(() -> {
      try {
        indexService.fullSyncSiteIndex();
      } catch (Exception e) {
        LOG.error("Full search sync failed", e);
      }
    });
    return ResponseEntity.accepted()
        .body(Map.of("message", "Full search index sync triggered"));
  }

  @PostMapping("/embedding/full-sync")
  public ResponseEntity<Map<String, String>> triggerFullEmbeddingSync() {
    Thread.ofVirtual().start(() -> embeddingService.fullVectorSync());
    return ResponseEntity.accepted()
        .body(Map.of("message", "Full embedding sync triggered"));
  }
}
