package com.simonrowe.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

  public NewsController(final AggregatedArticleRepository articleRepository) {
    this.articleRepository = articleRepository;
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

  @GetMapping("/{id}")
  public ArticleResponse getById(@PathVariable final String id) {
    return articleRepository.findById(id)
        .filter(AggregatedArticle::visible)
        .map(ArticleResponse::from)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Article not found"));
  }
}
