package com.simonrowe.snippet;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SnippetRepository extends MongoRepository<Snippet, String> {

  List<Snippet> findBySourceTypeAndContentIdOrderByCreatedAtAsc(
      SnippetSourceType sourceType, String contentId);
}
