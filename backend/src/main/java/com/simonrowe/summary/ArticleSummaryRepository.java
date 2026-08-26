package com.simonrowe.summary;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArticleSummaryRepository
    extends MongoRepository<ArticleSummary, String> {

  Optional<ArticleSummary> findByArticleId(String articleId);

  List<ArticleSummary> findByStatus(SummaryStatus status);
}
