package com.simonrowe.aggregation;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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

  public NewsController(
      final AggregatedArticleRepository articleRepository,
      final MongoTemplate mongoTemplate
  ) {
    this.articleRepository = articleRepository;
    this.mongoTemplate = mongoTemplate;
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
    return articles.map(ArticleResponse::from);
  }

  /**
   * Every distinct source name across the visible articles, alphabetically sorted.
   *
   * <p>Backs the news filter chips, which must list every source the site holds rather than
   * only those appearing on the first page of results. Uses {@code findDistinct} because
   * Spring Data has no derived-query projection for a distinct scalar.
   *
   * <p>Declared before the {@code /{id}} mapping for readability only — Spring matches the
   * literal {@code /sources} path ahead of the {@code {id}} template regardless of order.
   *
   * @return the distinct source names, empty when there are no visible articles
   */
  @GetMapping("/sources")
  public List<String> listSources() {
    return mongoTemplate.findDistinct(
            new Query(Criteria.where("visible").is(true)),
            "sourceName",
            AggregatedArticle.class,
            String.class)
        .stream()
        .filter(Objects::nonNull)
        .sorted()
        .toList();
  }

  @GetMapping("/{id}")
  public ArticleResponse getById(@PathVariable final String id) {
    return articleRepository.findById(id)
        .filter(AggregatedArticle::visible)
        .map(ArticleResponse::from)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Article not found"));
  }
}
