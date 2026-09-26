package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the guarded legacy copy against real source and target databases. */
class V044MigrateCoparentDataTest extends AbstractIntegrationTest {

  private static final String SOURCE_DATABASE = "coparent_migration_test";
  private static final List<String> TARGET_COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.EVENTS,
      V043CreateCoparentCollections.CATEGORIES,
      V043CreateCoparentCollections.SCHEDULE_CHANGES,
      V043CreateCoparentCollections.CONVERSATIONS,
      V043CreateCoparentCollections.AUDITS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private MongoClient mongoClient;

  private final V044MigrateCoparentData changeUnit = new V044MigrateCoparentData();

  @BeforeEach
  @AfterEach
  void cleanDatabases() {
    mongoClient.getDatabase(SOURCE_DATABASE).drop();
    TARGET_COLLECTIONS.stream()
        .filter(mongoTemplate::collectionExists)
        .forEach(collection -> mongoTemplate.getCollection(collection).drop());
  }

  @Test
  void preservesIdentifiersDatesAndRelationshipsAndCanReplay() {
    final ObjectId familyId = new ObjectId();
    final ObjectId parentId = new ObjectId();
    final Instant createdAt = Instant.parse("2026-09-19T10:00:00Z");
    final MongoDatabase source = mongoClient.getDatabase(SOURCE_DATABASE);
    source.getCollection("families").insertOne(new Document("_id", familyId)
        .append("name", "The Example Family")
        .append("timeZone", "Europe/London")
        .append("parentIds", List.of(parentId))
        .append("createdAt", java.util.Date.from(createdAt)));
    source.getCollection("parents").insertOne(new Document("_id", parentId)
        .append("auth0Id", "auth0|parent")
        .append("familyId", familyId)
        .append("email", "parent@example.com"));

    execute();
    execute();

    final Document family = mongoTemplate.getCollection(V043CreateCoparentCollections.FAMILIES)
        .find(new Document("_id", familyId)).first();
    assertThat(family).isNotNull();
    assertThat(family.get("parentIds", List.class)).containsExactly(parentId);
    assertThat(family.getDate("createdAt").toInstant()).isEqualTo(createdAt);
    assertThat(mongoTemplate.getCollection(V043CreateCoparentCollections.PARENTS).countDocuments())
        .isEqualTo(1);
  }

  @Test
  void refusesToOverwriteConflictingTargetDocument() {
    final ObjectId familyId = new ObjectId();
    mongoClient.getDatabase(SOURCE_DATABASE).getCollection("families")
        .insertOne(new Document("_id", familyId).append("name", "Source"));
    mongoTemplate.getCollection(V043CreateCoparentCollections.FAMILIES)
        .insertOne(new Document("_id", familyId).append("name", "Target"));

    assertThatThrownBy(this::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(familyId.toHexString());
  }

  @Test
  void noLegacyCollectionsDoesNothing() {
    execute();

    assertThat(TARGET_COLLECTIONS)
        .noneMatch(mongoTemplate::collectionExists);
  }

  private void execute() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate), mongoClient,
        new CoparentProperties(true, "coparent", "http://localhost", "email",
            "noreply@example.com", false, SOURCE_DATABASE,
            new CoparentProperties.Assistant(false, "gpt-5.4-nano")));
  }
}
