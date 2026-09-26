package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises recurrence canonicalization against real MongoDB, including replay. */
class V046CanonicalizeCoparentRecurrenceTest extends AbstractIntegrationTest {

  private static final String EVENTS = V043CreateCoparentCollections.EVENTS;

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  private final V046CanonicalizeCoparentRecurrence changeUnit =
      new V046CanonicalizeCoparentRecurrence();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.dropCollection(EVENTS);
  }

  @Test
  void rewritesUnrenderableSpellingsAndLeavesOtherEventsAlone() {
    final ObjectId abbreviated = insert(recurring("WEEKLY", "TUE", "TU", "Thursday"));
    final ObjectId unknown = insert(recurring("weekly", "someday"));
    final ObjectId canonical = insert(recurring("weekly", "saturday"));
    final ObjectId single = insert(new Document("title", "One-off").append("recurring", null));

    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(recurrenceOf(abbreviated))
        .isEqualTo(recurring("weekly", "tuesday", "thursday").get("recurring"));
    assertThat(recurrenceOf(unknown)).isEqualTo(recurring("weekly").get("recurring"));
    assertThat(recurrenceOf(canonical)).isEqualTo(recurring("weekly", "saturday").get("recurring"));
    assertThat(mongoTemplate.findById(single, Document.class, EVENTS))
        .containsEntry("recurring", null);
    assertThat(mongoTemplate.getCollection(EVENTS).countDocuments()).isEqualTo(4);
  }

  @Test
  void replayChangesNothing() {
    final ObjectId id = insert(recurring("Weekly", "Mon"));
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));
    final Document once = mongoTemplate.findById(id, Document.class, EVENTS);

    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(mongoTemplate.findById(id, Document.class, EVENTS)).isEqualTo(once);
    assertThat(recurrenceOf(id)).isEqualTo(recurring("weekly", "monday").get("recurring"));
  }

  private ObjectId insert(final Document event) {
    final ObjectId id = new ObjectId();
    mongoTemplate.insert(event.append("_id", id), EVENTS);
    return id;
  }

  private Document recurrenceOf(final ObjectId id) {
    return mongoTemplate.findById(id, Document.class, EVENTS).get("recurring", Document.class);
  }

  private static Document recurring(final String frequency, final String... days) {
    return new Document("title", "Series").append("recurring",
        new Document("frequency", frequency).append("days", List.copyOf(Arrays.asList(days))));
  }
}
