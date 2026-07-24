package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoWriteException;
import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the per-user → global migration against a real MongoDB. Mongock is disabled in
 * tests, so the change unit is driven directly.
 */
class V014MakeFavouritesGlobalTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "favourites";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V014MakeFavouritesGlobal changeUnit = new V014MakeFavouritesGlobal();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
  }

  @Test
  void deduplicatesAcrossUsersKeepingEarliestAndUnsetsUserId() {
    mongoTemplate.getCollection(COLLECTION).insertMany(List.of(
        favourite("auth0|a", "NEWS", "a-1", Instant.parse("2026-07-02T10:00:00Z")),
        favourite("auth0|b", "NEWS", "a-1", Instant.parse("2026-07-01T10:00:00Z")),
        favourite("auth0|a", "EVENT", "e-1", Instant.parse("2026-07-03T10:00:00Z"))));

    changeUnit.execution(mongoTemplate);

    final List<Document> remaining =
        mongoTemplate.getCollection(COLLECTION).find().into(new ArrayList<>());
    assertThat(remaining).hasSize(2);
    assertThat(remaining).allSatisfy(doc -> assertThat(doc.get("userId")).isNull());

    final Document news = remaining.stream()
        .filter(doc -> "a-1".equals(doc.getString("contentId")))
        .findFirst()
        .orElseThrow();
    assertThat(news.getDate("createdAt"))
        .isEqualTo(Date.from(Instant.parse("2026-07-01T10:00:00Z")));
  }

  @Test
  void createsGlobalIndexesEnforcingUniqueTypeAndContent() {
    changeUnit.execution(mongoTemplate);

    mongoTemplate.getCollection(COLLECTION)
        .insertOne(favourite(null, "NEWS", "a-1", Instant.parse("2026-07-01T10:00:00Z")));

    assertThatThrownBy(() -> mongoTemplate.getCollection(COLLECTION)
        .insertOne(favourite(null, "NEWS", "a-1", Instant.parse("2026-07-05T10:00:00Z"))))
        .isInstanceOf(MongoWriteException.class);

    final List<String> indexNames = mongoTemplate.indexOps(COLLECTION).getIndexInfo().stream()
        .map(info -> info.getName())
        .toList();
    assertThat(indexNames).contains("idx_type_content", "idx_type_created");
  }

  private Document favourite(
      final String userId, final String type, final String contentId, final Instant createdAt) {
    return new Document("userId", userId)
        .append("type", type)
        .append("contentId", contentId)
        .append("createdAt", Date.from(createdAt));
  }
}
