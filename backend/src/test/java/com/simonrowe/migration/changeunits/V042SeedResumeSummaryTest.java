package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the CV summary seed against a real MongoDB. Mongock is disabled in tests, so
 * the change unit is driven directly.
 */
class V042SeedResumeSummaryTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "profiles";
  private static final String FIELD = "resumeSummary";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V042SeedResumeSummary changeUnit = new V042SeedResumeSummary();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
  }

  @Test
  void seedsTheSummaryWhenTheFieldIsAbsent() {
    insertProfile(null);

    changeUnit.execution(mongoTemplate);

    assertThat(summary())
        .startsWith("Engineering leader with over two decades")
        .endsWith("across the department.");
  }

  @Test
  void seedsTheSummaryWhenTheFieldIsBlank() {
    insertProfile("   ");

    changeUnit.execution(mongoTemplate);

    assertThat(summary()).contains("Engineering leader");
  }

  @Test
  void neverOverwritesTheSummaryEditedInTheCms() {
    insertProfile("A summary someone wrote by hand.");

    changeUnit.execution(mongoTemplate);

    assertThat(summary()).isEqualTo("A summary someone wrote by hand.");
  }

  @Test
  void isIdempotentOnSecondRun() {
    insertProfile(null);
    changeUnit.execution(mongoTemplate);
    String afterFirstRun = summary();

    changeUnit.execution(mongoTemplate);

    assertThat(summary()).isEqualTo(afterFirstRun);
  }

  @Test
  void rollbackRemovesTheSeededSummaryButNotAnEditedOne() {
    insertProfile(null);
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);
    assertThat(summary()).isNull();

    insertProfile("Hand written, and not ours to remove.");
    changeUnit.rollback(mongoTemplate);
    assertThat(summary()).isEqualTo("Hand written, and not ours to remove.");
  }

  private void insertProfile(final String resumeSummary) {
    mongoTemplate.getCollection(COLLECTION).drop();
    Document profile = new Document("name", "Simon Rowe")
        .append("title", "Software Engineering Leader");
    if (resumeSummary != null) {
      profile.append(FIELD, resumeSummary);
    }
    mongoTemplate.getCollection(COLLECTION).insertOne(profile);
  }

  private String summary() {
    Document profile = mongoTemplate.getCollection(COLLECTION).find().first();
    return profile == null ? null : profile.getString(FIELD);
  }
}
