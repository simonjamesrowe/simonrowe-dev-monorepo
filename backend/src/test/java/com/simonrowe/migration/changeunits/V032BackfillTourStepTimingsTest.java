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

/** Exercises the additive tour-timing migration directly because Mongock is off in tests. */
class V032BackfillTourStepTimingsTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V032BackfillTourStepTimings changeUnit = new V032BackfillTourStepTimings();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void assignsAskAiAndDefaultDurationsWithoutOverwritingConfiguredValues() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).insertMany(List.of(
        new Document("legacyId", "default-ask-ai"),
        new Document("legacyId", "default-site-search"),
        new Document("legacyId", "custom-step").append("autoAdvanceMs", 15000)));

    changeUnit.execution(mongoTemplate);

    assertThat(durationFor("default-ask-ai"))
        .isEqualTo(V032BackfillTourStepTimings.ASK_AI_DELAY_MS);
    assertThat(durationFor("default-site-search"))
        .isEqualTo(V032BackfillTourStepTimings.DEFAULT_DELAY_MS);
    assertThat(durationFor("custom-step")).isEqualTo(15000);
  }

  @Test
  void isNoOpWhenEveryStepAlreadyHasDurations() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).insertOne(
        new Document("legacyId", "default-ask-ai").append("autoAdvanceMs", 12000));

    changeUnit.execution(mongoTemplate);
    changeUnit.execution(mongoTemplate);

    assertThat(durationFor("default-ask-ai")).isEqualTo(12000);
  }

  private Integer durationFor(final String legacyId) {
    return mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION)
        .find(new Document("legacyId", legacyId))
        .first()
        .getInteger("autoAdvanceMs");
  }
}
