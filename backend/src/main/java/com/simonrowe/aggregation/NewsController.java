package com.simonrowe.aggregation;

import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkService;
import com.simonrowe.summary.ArticleSummaryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news")
public class NewsController {

  private static final Logger LOG = LoggerFactory.getLogger(NewsController.class);

  private final AggregatedArticleRepository articleRepository;
  private final ArticleQueryService articleQueryService;
  private final MongoTemplate mongoTemplate;
  private final ArticleSummaryService summaryService;
  private final ShortLinkService shortLinkService;

  public NewsController(
      final AggregatedArticleRepository articleRepository,
      final ArticleQueryService articleQueryService,
      final MongoTemplate mongoTemplate,
      final ArticleSummaryService summaryService,
      final ShortLinkService shortLinkService
  ) {
    this.articleRepository = articleRepository;
    this.articleQueryService = articleQueryService;
    this.mongoTemplate = mongoTemplate;
    this.summaryService = summaryService;
    this.shortLinkService = shortLinkService;
  }

  /**
   * One page of the visible news feed, newest first.
   *
   * <p>{@code source} repeats — {@code ?source=Claude%20Blog&source=Spring%20Blog} — so the
   * feed's source filter can hold several at once. A single value still works, which is
   * what the filter row sent before it grew checkboxes.
   *
   * @param page zero-based page number
   * @param size articles per page
   * @param q free text matched against the fields a card shows; omitted means no filter
   * @param request read directly for the repeated {@code source} values; see
   *     {@link #sourcesFrom(HttpServletRequest)}
   * @return the page of articles
   */
  @GetMapping
  public Page<ArticleResponse> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final String q,
      final HttpServletRequest request
  ) {
    List<String> source = sourcesFrom(request);
    PageRequest pageRequest = PageRequest.of(page, size);
    Page<AggregatedArticle> articles = articleQueryService.find(source, q, pageRequest);
    LOG.debug("Listing news articles: page={}, size={}, sources={}, q={}, total={}",
        page, size, source, q, articles.getTotalElements());

    // One query for the whole page, not one per card. The page size is 24, so resolving
    // per article would turn a single render into 24 extra round trips.
    Map<String, String> shortUrls = shortLinkService.urlsFor(
        ShortLinkContentType.ARTICLE,
        articles.getContent().stream().map(AggregatedArticle::id).toList());

    return articles.map(article ->
        ArticleResponse.from(article, shortUrls.get(article.id())));
  }

  /**
   * The requested source names, exactly as sent.
   *
   * <p>Deliberately not {@code @RequestParam List<String> source}. Spring resolves a
   * parameter present exactly once to a {@code String} and then converts it to the list by
   * splitting on commas — so a source genuinely named "Smith, Jones &amp; Co" arrives as two
   * source names, neither of which matches anything, and the page reports the source as
   * holding no articles. {@code getParameterValues} returns the values the client actually
   * sent and splits nothing.
   *
   * <p>The cost is that a hand-written {@code ?source=A,B} no longer means two sources. That
   * is the right way round: the feed repeats the parameter, and a comma inside a name is a
   * real thing a scraped publisher can have while a comma-joined list is only a convention.
   *
   * @param request the current request
   * @return the source names, empty when the parameter is absent
   */
  private static List<String> sourcesFrom(final HttpServletRequest request) {
    String[] values = request.getParameterValues("source");
    return values == null ? List.of() : List.of(values);
  }

  /**
   * Every source across the visible articles with its article count, busiest first.
   *
   * <p>Backs the news source filter, which must list every source the site holds rather
   * than only those appearing on the first page of results. The count orders the list by
   * volume and tells a visitor what selecting a source is worth before they select it.
   *
   * <p>Counts are of the whole feed and deliberately do not narrow as the free-text filter
   * is typed: they label the sources, and a list whose numbers move while you read it is
   * harder to choose from than one whose numbers hold still.
   *
   * <p>Declared before the {@code /{id}} mapping for readability only — Spring matches the
   * literal {@code /sources} path ahead of the {@code {id}} template regardless of order.
   *
   * @return the source summaries, empty when there are no visible articles
   */
  @GetMapping("/sources")
  public List<SourceSummary> listSources() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("visible").is(true)),
        Aggregation.group("sourceName").count().as("count"),
        Aggregation.project("count").and("_id").as("name"),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "count")
            .and(Sort.by(Sort.Direction.ASC, "name"))));

    return mongoTemplate
        .aggregate(aggregation, AggregatedArticle.class, SourceSummary.class)
        .getMappedResults()
        .stream()
        .filter(summary -> summary.name() != null)
        .toList();
  }

  /**
   * Every article that already has a completed in-depth summary.
   *
   * <p>Public, because summaries are globally shared. This is what lets a logged-out
   * visitor's card read "Read summary" and open instantly, versus "Summarise" which
   * triggers the login popup — the same way hearts render filled for everyone but only
   * toggle with a session. It mirrors {@code GET /api/favourites/{type}/ids}.
   *
   * <p>Declared before the {@code /{id}} mapping for readability only, for the same reason
   * recorded on {@link #listSources()}: Spring matches the literal {@code /summaries}
   * segment ahead of the {@code {id}} template regardless of declaration order.
   *
   * @return the article ids, empty when nothing has been summarised
   */
  @GetMapping("/summaries/ids")
  public Set<String> listSummarisedArticleIds() {
    return summaryService.summarisedArticleIds();
  }

  @GetMapping("/{id}")
  public ArticleResponse getById(@PathVariable final String id) {
    return articleRepository.findById(id)
        .filter(AggregatedArticle::visible)
        .map(article -> ArticleResponse.from(
            article,
            shortLinkService.urlFor(ShortLinkContentType.ARTICLE, article.id()).orElse(null)))
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Article not found"));
  }
}
