package com.simonrowe.school.usage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link SchoolUsage}. */
public interface SchoolUsageRepository extends MongoRepository<SchoolUsage, String> {

  /**
   * Every call in a period.
   *
   * @param from inclusive start
   * @param to exclusive end
   * @return the calls
   */
  List<SchoolUsage> findByAtBetween(Instant from, Instant to);
}
