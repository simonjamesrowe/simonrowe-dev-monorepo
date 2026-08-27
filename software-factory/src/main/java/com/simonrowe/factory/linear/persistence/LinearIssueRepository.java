package com.simonrowe.factory.linear.persistence;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link LinearIssueRecord}, keyed by fingerprint. */
public interface LinearIssueRepository extends MongoRepository<LinearIssueRecord, String> {

  /**
   * Everything a producer has filed, newest occurrence first.
   *
   * @param producer the producer key
   * @return that producer's records, ordered by most recently seen
   */
  List<LinearIssueRecord> findByProducerOrderByLastSeenAtDesc(String producer);
}
