package com.simonrowe.aggregation;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.WeeklyDigestAgent;
import com.simonrowe.embedding.EmbeddingService;
import com.simonrowe.search.IndexService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminAggregationController {

  private static final Logger LOG = LoggerFactory.getLogger(AdminAggregationController.class);

  /** The largest page an admin listing serves; the page-size picker offers up to 100. */
  static final int MAX_PAGE_SIZE = 200;

  private final ContentSourceRepository sourceRepository;
  private final ContentAggregationAgent aggregationAgent;
  private final WeeklyDigestAgent digestAgent;
  private final IndexService indexService;
  private final EmbeddingService embeddingService;
  private final ArticleQueryService articleQueryService;
  private final EventQueryService eventQueryService;
  private final AggregatedContentAdminService adminService;

  public AdminAggregationController(
      ContentSourceRepository sourceRepository,
      ContentAggregationAgent aggregationAgent,
      WeeklyDigestAgent digestAgent,
      IndexService indexService,
      EmbeddingService embeddingService,
      ArticleQueryService articleQueryService,
      EventQueryService eventQueryService,
      AggregatedContentAdminService adminService) {
    this.sourceRepository = sourceRepository;
    this.aggregationAgent = aggregationAgent;
    this.digestAgent = digestAgent;
    this.indexService = indexService;
    this.embeddingService = embeddingService;
    this.articleQueryService = articleQueryService;
    this.eventQueryService = eventQueryService;
    this.adminService = adminService;
  }

  /**
   * One page of articles, hidden ones included by default.
   *
   * <p>{@code source} repeats, read the same way as the public feed reads it; see
   * {@link NewsController#sourcesFrom(HttpServletRequest)}.
   *
   * @param page zero-based page number
   * @param size articles per page, clamped to 1..{@value #MAX_PAGE_SIZE}
   * @param q free text matched against title, summary, author and source name
   * @param visibility {@code all} (default), {@code visible} or {@code hidden}
   * @param sort {@code publishedDate} (default), {@code fetchedAt}, {@code title} or
   *     {@code sourceName}; anything else is a 400
   * @param direction {@code desc} (default) or {@code asc}; anything else is a 400
   * @param request read for the repeated {@code source} values
   * @return the page of articles
   */
  @GetMapping("/news")
  public Page<ArticleResponse> listAllArticles(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final String q,
      @RequestParam(required = false) final String visibility,
      @RequestParam(required = false) final String sort,
      @RequestParam(required = false) final String direction,
      final HttpServletRequest request) {
    AdminListing listing = badRequestOnInvalid(() -> new AdminListing(
        q,
        NewsController.sourcesFrom(request),
        VisibilityFilter.parse(visibility),
        AdminSort.of(sort, direction,
            ArticleQueryService.ADMIN_SORT_FIELDS, ArticleQueryService.ADMIN_DEFAULT_SORT)));
    return articleQueryService.findForAdmin(listing, pageRequest(page, size))
        .map(ArticleResponse::from);
  }

  /**
   * Every source across all articles, hidden ones included, with its count, for the admin
   * source filter.
   *
   * @return the source summaries, busiest first
   */
  @GetMapping("/news/sources")
  public List<SourceSummary> listArticleSources() {
    return articleQueryService.allSources();
  }

  @PutMapping("/news/{id}/visibility")
  public ArticleResponse updateArticleVisibility(
      @PathVariable String id, @RequestBody Map<String, Boolean> body) {
    boolean visible = body.getOrDefault("visible", true);
    return adminService.setArticleVisibility(id, visible)
        .map(ArticleResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @DeleteMapping("/news/{id}")
  public ResponseEntity<Void> deleteArticle(@PathVariable String id) {
    adminService.deleteArticle(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Hides, shows or deletes many articles at once.
   *
   * @param body the ids (at most {@value AggregatedContentAdminService#MAX_BULK_IDS}) and
   *     the action
   * @return how many were changed and which ids matched nothing
   */
  @PostMapping("/news/bulk")
  public BulkActionResult bulkArticles(@RequestBody final BulkActionRequest body) {
    return badRequestOnInvalid(() -> adminService.bulkArticles(body));
  }

  /**
   * One page of events, hidden ones included by default.
   *
   * @param page zero-based page number
   * @param size events per page, clamped to 1..{@value #MAX_PAGE_SIZE}
   * @param q free text matched against title, summary, venue, location and source name
   * @param visibility {@code all} (default), {@code visible} or {@code hidden}
   * @param sort {@code eventDate} (default), {@code title} or {@code sourceName}; anything
   *     else is a 400
   * @param direction {@code desc} (default) or {@code asc}; anything else is a 400
   * @param request read for the repeated {@code source} values
   * @return the page of events
   */
  @GetMapping("/events")
  public Page<EventResponse> listAllEvents(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final String q,
      @RequestParam(required = false) final String visibility,
      @RequestParam(required = false) final String sort,
      @RequestParam(required = false) final String direction,
      final HttpServletRequest request) {
    AdminListing listing = badRequestOnInvalid(() -> new AdminListing(
        q,
        NewsController.sourcesFrom(request),
        VisibilityFilter.parse(visibility),
        AdminSort.of(sort, direction,
            EventQueryService.ADMIN_SORT_FIELDS, EventQueryService.ADMIN_DEFAULT_SORT)));
    return eventQueryService.findForAdmin(listing, pageRequest(page, size))
        .map(EventResponse::from);
  }

  @PutMapping("/events/{id}/visibility")
  public EventResponse updateEventVisibility(
      @PathVariable String id, @RequestBody Map<String, Boolean> body) {
    boolean visible = body.getOrDefault("visible", true);
    return adminService.setEventVisibility(id, visible)
        .map(EventResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @DeleteMapping("/events/{id}")
  public ResponseEntity<Void> deleteEvent(@PathVariable String id) {
    adminService.deleteEvent(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Hides, shows or deletes many events at once.
   *
   * @param body the ids (at most {@value AggregatedContentAdminService#MAX_BULK_IDS}) and
   *     the action
   * @return how many were changed and which ids matched nothing
   */
  @PostMapping("/events/bulk")
  public BulkActionResult bulkEvents(@RequestBody final BulkActionRequest body) {
    return badRequestOnInvalid(() -> adminService.bulkEvents(body));
  }

  /**
   * A page request that cannot be refused: a negative page or an absurd size is clamped
   * rather than surfacing as a 500 from {@link PageRequest#of(int, int)}.
   */
  private static PageRequest pageRequest(final int page, final int size) {
    return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
  }

  /**
   * Runs the parsing or validation step, turning an {@link IllegalArgumentException} into a
   * 400 that carries its message.
   */
  private static <T> T badRequestOnInvalid(final Supplier<T> step) {
    try {
      return step.get();
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
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
    String categoryFilter = body.containsKey("categoryFilter")
        ? (String) body.get("categoryFilter") : source.categoryFilter();
    ContentSource updated = new ContentSource(
        source.id(), source.name(), source.baseUrl(), feedUrl,
        sitemapUrl, source.sourceType(), source.scrapeStrategy(),
        active, source.lastFetchedAt(), source.lastError(),
        categoryFilter);
    return sourceRepository.save(updated);
  }

  @PostMapping("/aggregation/import")
  public ResponseEntity<Map<String, String>> importUrl(
      @RequestBody Map<String, String> body) {
    String url = body.get("url");
    if (url == null || url.isBlank()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "URL is required"));
    }
    try {
      String result = aggregationAgent.importFromUrl(url);
      return ResponseEntity.ok()
          .body(Map.of("message", result));
    } catch (Exception e) {
      LOG.error("Import failed for URL: {}", url, e);
      return ResponseEntity.internalServerError()
          .body(Map.of("message", "Import failed: " + e.getMessage()));
    }
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
