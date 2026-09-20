package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Durable progress through the CoParent setup journey. */
@Document("onboardingstates")
public record OnboardingState(
    @Id ObjectId id,
    ObjectId familyId,
    String currentStep,
    List<String> completedSteps,
    @Field("isComplete") boolean complete,
    Instant lastUpdated,
    Instant createdAt,
    Instant updatedAt
) {
  public OnboardingState {
    completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
  }
}
