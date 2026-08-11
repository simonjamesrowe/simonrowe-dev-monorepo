package com.simonrowe.factory.cvefix.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link CveFixRunRecord}, keyed by run id. */
public interface CveFixRunRepository extends MongoRepository<CveFixRunRecord, String> {
}
