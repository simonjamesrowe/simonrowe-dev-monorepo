package com.simonrowe.aggregation;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AggregatedArticleRepository
    extends MongoRepository<AggregatedArticle, String> {

  Page<AggregatedArticle> findByVisibleTrueOrderByPublishedDateDesc(
      Pageable pageable);

  List<AggregatedArticle> findByVisibleTrueOrderByPublishedDateDesc();

  Page<AggregatedArticle> findByVisibleTrueAndSourceNameOrderByPublishedDateDesc(
      String sourceName, Pageable pageable);

  boolean existsByOriginalUrl(String originalUrl);
}
