package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.OnboardingState;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for family onboarding progress. */
public interface OnboardingStateRepository extends MongoRepository<OnboardingState, ObjectId> {
  Optional<OnboardingState> findByFamilyId(ObjectId familyId);
}
