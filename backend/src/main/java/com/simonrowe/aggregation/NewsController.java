package com.simonrowe.aggregation;

import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkService;
import com.simonrowe.summary.ArticleSummaryService;
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
  private final MongoTemplate mongoTemplate;
  private final ArticleSummaryService summaryService;
  private final ShortLinkService shortLinkService;

  public NewsController(
      final AggregatedArticleRepository articleRepository,
      final MongoTemplate mongoTemplate,
      final ArticleSummaryService summaryService,
      final ShortLinkService shortLinkService
  ) {
    this.articleRepository = articleRepository;
    this.mongoTemplate = mongoTemplate;
    this.summaryService = summaryService;
    this.shortLinkService = shortLinkService;
  }

  @GetMapping
  public Page<ArticleResponse> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final String source
  ) {
    PageRequest pageRequest = PageRequest.of(page, size);
    Page<AggregatedArticle> articles;
    if (source != null && !source.isBlank()) {
      articles = articleRepository
          .findByVisibleTrueAndSourceNameOrderByPublishedDateDesc(source, pageRequest);
    } else {
      articles = articleRepository
          .findByVisibleTrueOrderByPublishedDateDesc(pageRequest);
    }
    LOG.debug("Listing news articles: page={}, size={}, source={}, total={}",
        page, size, source, articles.getTotalElements());

    // One query for the whole page, not one per card. The page size is 24, so resolving
    // per article would turn a single render into 24 extra round trips.
    Map<String, String> shortUrls = shortLinkService.urlsFor(
        ShortLinkContentType.ARTICLE,
        articles.getContent().stream().map(AggregatedArticle::id).toList());

    return articles.map(article ->
        ArticleResponse.from(article, shortUrls.get(article.id())));
  }

  /**
   * Every source across the visible articles with its article count, busiest first.
   *
   * <p>Backs the news filter pills, which must list every source the site holds rather
   * than only those appearing on the first page of results. The count lets the page sort
   * by volume and collapse low-volume sources into a "More" overflow, which is what keeps
   * one-off manual imports from crowding the row.
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
