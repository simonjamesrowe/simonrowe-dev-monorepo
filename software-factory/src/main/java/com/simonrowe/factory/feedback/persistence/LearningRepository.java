package com.simonrowe.factory.feedback.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link LearningRecord}, keyed by its deterministic PR id. */
public interface LearningRepository extends MongoRepository<LearningRecord, String> {
}
