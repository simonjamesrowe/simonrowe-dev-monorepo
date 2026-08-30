package com.simonrowe.factory.cvefix.persistence;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link CveFixRunRecord}, keyed by run id. */
public interface CveFixRunRepository extends MongoRepository<CveFixRunRecord, String> {

  /**
   * The most recently started run other than the one named.
   *
   * <p>The exclusion matters on a Temporal re-drive: records are written at the end of a run, so
   * the newest row is normally the previous run — but a re-driven workflow id may already have
   * written its own row, and reading it back would compare the run against itself.
   *
   * <p>Served by the {@code startedAt} descending index created by
   * {@link CveFixIndexInitializer}.
   *
   * @param id the run record id to exclude, which is the workflow id
   * @return the previous run, or empty when there is none
   */
  Optional<CveFixRunRecord> findFirstByIdNotOrderByStartedAtDesc(String id);
}
