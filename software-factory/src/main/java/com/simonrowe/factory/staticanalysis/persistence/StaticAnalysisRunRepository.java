package com.simonrowe.factory.staticanalysis.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link StaticAnalysisRunRecord}, keyed by Temporal run id. */
public interface StaticAnalysisRunRepository
    extends MongoRepository<StaticAnalysisRunRecord, String> {
}
