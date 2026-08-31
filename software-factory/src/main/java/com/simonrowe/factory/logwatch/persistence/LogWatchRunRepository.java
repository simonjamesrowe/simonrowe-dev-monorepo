package com.simonrowe.factory.logwatch.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link LogWatchRunRecord}, keyed on the Temporal run id. */
public interface LogWatchRunRepository extends MongoRepository<LogWatchRunRecord, String> {
}
