package com.simonrowe.coparent;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Proves product repositories and schema never write into the simonrowe database. */
class CoparentDatabaseIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate coparentMongoTemplate;

  @Autowired
  private FamilyRepository families;

  @AfterEach
  void cleanFamilyCollection() {
    coparentMongoTemplate.dropCollection(V043CreateCoparentCollections.FAMILIES);
    mongoTemplate.dropCollection(V043CreateCoparentCollections.FAMILIES);
  }

  @Test
  void usesSeparateDatabaseOnTheSharedMongoClient() {
    V043CreateCoparentCollections.createIndexes(coparentMongoTemplate);
    assertThat(coparentMongoTemplate.getDb().getName()).isEqualTo("coparent");
    assertThat(mongoTemplate.getDb().getName()).isNotEqualTo("coparent");
    assertThat(coparentMongoTemplate.collectionExists(
        V043CreateCoparentCollections.PARENTS)).isTrue();
    assertThat(mongoTemplate.collectionExists(V043CreateCoparentCollections.PARENTS)).isFalse();

    families.save(new Family(null, "Database boundary", "Europe/London", List.of(), List.of(),
        List.of(), null, Instant.now(), Instant.now()));
    assertThat(coparentMongoTemplate.getCollection(V043CreateCoparentCollections.FAMILIES)
        .countDocuments()).isEqualTo(1);
    assertThat(mongoTemplate.collectionExists(V043CreateCoparentCollections.FAMILIES)).isFalse();
  }
}
