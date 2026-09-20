package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises CoParent schema creation against real MongoDB, including replay. */
class V043CreateCoparentCollectionsTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
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

  private final V043CreateCoparentCollections changeUnit =
      new V043CreateCoparentCollections();

  @BeforeEach
  @AfterEach
  void dropCollections() {
    COLLECTIONS.stream()
        .filter(mongoTemplate::collectionExists)
        .forEach(collection -> mongoTemplate.getCollection(collection).drop());
  }

  @Test
  void createsEveryCollectionAndRequiredIndexes() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(COLLECTIONS).allMatch(mongoTemplate::collectionExists);
    assertThat(indexNames(V043CreateCoparentCollections.PARENTS))
        .contains("idx_coparent_parent_subject", "idx_coparent_parent_family_subject");
    assertThat(indexNames(V043CreateCoparentCollections.INVITATIONS))
        .contains("idx_coparent_invitation_token", "idx_coparent_invitation_family_status");
    assertThat(indexNames(V043CreateCoparentCollections.CONVERSATIONS))
        .contains("idx_coparent_conversation_family_recent", "idx_coparent_permission_id");
  }

  @Test
  void replayLeavesSchemaUnchanged() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));
    final List<String> before = indexNames(V043CreateCoparentCollections.EVENTS);

    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(indexNames(V043CreateCoparentCollections.EVENTS))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  private List<String> indexNames(final String collection) {
    return mongoTemplate.indexOps(collection).getIndexInfo().stream()
        .map(index -> index.getName())
        .toList();
  }
}
