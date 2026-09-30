package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V048CreatePortfolioProjectsTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V048CreatePortfolioProjects changeUnit = new V048CreatePortfolioProjects();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private com.mongodb.client.MongoCollection<Document> collection() {
    return mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  @Test
  void seedsTheFourLaunchProjectsAsPublishedComingSoonInOrder() {
    changeUnit.execution(mongoTemplate);

    var rows = collection().find().sort(new Document("displayOrder", 1))
        .into(new java.util.ArrayList<>());
    assertThat(rows).extracting(row -> row.getString("slug"))
        .containsExactly("software-factory", "term-time", "co-parents", "clinicians-veil");
    assertThat(rows).allSatisfy(row -> {
      assertThat(row.getString("status")).isEqualTo("COMING_SOON");
      assertThat(row.getBoolean("published")).isTrue();
    });
  }

  @Test
  void reRunNeitherDuplicatesNorOverwritesEdits() {
    changeUnit.execution(mongoTemplate);
    collection().updateOne(new Document("slug", "term-time"),
        new Document("$set",
            new Document("tagline", "Edited in the CMS").append("status", "LIVE")));

    changeUnit.execution(mongoTemplate);

    assertThat(collection().countDocuments()).isEqualTo(4);
    Document termTime = collection().find(new Document("slug", "term-time")).first();
    assertThat(termTime.getString("tagline")).isEqualTo("Edited in the CMS");
    assertThat(termTime.getString("status")).isEqualTo("LIVE");
  }

  @Test
  void slugIndexRefusesDuplicatesAndAllowsDistinctRows() {
    changeUnit.execution(mongoTemplate);

    collection().insertOne(new Document("slug", "a-new-one"));
    assertThat(collection().countDocuments()).isEqualTo(5);
    assertThatThrownBy(() -> collection().insertOne(new Document("slug", "term-time")))
        .hasMessageContaining("duplicate key");
  }

  @Test
  void createIndexesAloneRestoresTheUniqueIndexAfterDrop() {
    changeUnit.execution(mongoTemplate);
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);

    V048CreatePortfolioProjects.createIndexes(mongoTemplate);

    assertThat(mongoTemplate.indexOps(V048CreatePortfolioProjects.COLLECTION).getIndexInfo())
        .anySatisfy(index -> {
          assertThat(index.getName()).isEqualTo(V048CreatePortfolioProjects.SLUG_INDEX);
          assertThat(index.isUnique()).isTrue();
        });
  }

  @Test
  void rollbackRemovesOnlyRowsStillAsSeeded() {
    changeUnit.execution(mongoTemplate);
    collection().updateOne(new Document("slug", "co-parents"),
        new Document("$set", new Document("tagline", "Edited")));

    changeUnit.rollback(mongoTemplate);

    assertThat(collection().find().into(new java.util.ArrayList<>()))
        .extracting(row -> row.getString("slug")).containsExactly("co-parents");
  }
}
