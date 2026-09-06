package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Covers the fresh-database timing pass which follows the initial default step seeding. */
class V035BackfillSeededTourStepTimingsTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V035BackfillSeededTourStepTimings changeUnit =
      new V035BackfillSeededTourStepTimings();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void assignsExplicitTimingsToStepsInsertedAfterTheInitialBackfill() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).insertMany(List.of(
        new Document("legacyId", "default-home-chat"),
        new Document("legacyId", "default-ask-ai")));

    changeUnit.execution(mongoTemplate);

    assertThat(durationFor("default-home-chat"))
        .isEqualTo(V032BackfillTourStepTimings.DEFAULT_DELAY_MS);
    assertThat(durationFor("default-ask-ai"))
        .isEqualTo(V032BackfillTourStepTimings.ASK_AI_DELAY_MS);
  }

  private Integer durationFor(final String legacyId) {
    return mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION)
        .find(new Document("legacyId", legacyId))
        .first()
        .getInteger("autoAdvanceMs");
  }
}
