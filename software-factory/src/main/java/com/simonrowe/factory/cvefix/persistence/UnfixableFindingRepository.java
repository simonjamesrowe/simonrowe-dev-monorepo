package com.simonrowe.factory.cvefix.persistence;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link UnfixableFindingRecord}, keyed by component PURL. */
public interface UnfixableFindingRepository
    extends MongoRepository<UnfixableFindingRecord, String> {

  /** The recorded give-up for a component, if there is one. */
  Optional<UnfixableFindingRecord> findByPurl(String purl);
}
