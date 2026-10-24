package com.simonrowe.school.news;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for {@link NewsletterSummary}.
 *
 * <p>Read only by id, a few at a time, so it needs no index beyond {@code _id} and no Mongock
 * change unit.
 */
public interface NewsletterSummaryRepository extends MongoRepository<NewsletterSummary, String> {
}
