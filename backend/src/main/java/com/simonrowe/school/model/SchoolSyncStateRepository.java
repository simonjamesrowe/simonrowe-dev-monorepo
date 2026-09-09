package com.simonrowe.school.model;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for {@link SchoolSyncState}. */
public interface SchoolSyncStateRepository extends MongoRepository<SchoolSyncState, String> {
}
