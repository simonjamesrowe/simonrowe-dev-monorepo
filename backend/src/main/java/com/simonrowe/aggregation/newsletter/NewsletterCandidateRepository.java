package com.simonrowe.aggregation.newsletter;

import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link NewsletterCandidate}. */
public interface NewsletterCandidateRepository
    extends MongoRepository<NewsletterCandidate, String> {

  boolean existsByUrl(String url);

  long countByStatus(Status status);

  Page<NewsletterCandidate> findByStatus(Status status, Pageable pageable);

  Page<NewsletterCandidate> findByStatusAndSourceName(
      Status status, String sourceName, Pageable pageable);
}
