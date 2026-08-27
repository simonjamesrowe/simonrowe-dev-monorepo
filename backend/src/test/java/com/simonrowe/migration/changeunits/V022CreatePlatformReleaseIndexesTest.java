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
import org.springframework.data.mongodb.core.index.IndexInfo;

class V022CreatePlatformReleaseIndexesTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V022CreatePlatformReleaseIndexes changeUnit =
      new V022CreatePlatformReleaseIndexes();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(V022CreatePlatformReleaseIndexes.COLLECTION).drop();
  }

  @Test
  void createsTheChangelogIndexes() {
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);

    assertThat(
        mongoTemplate.indexOps(V022CreatePlatformReleaseIndexes.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .contains(
            V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX,
            V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX);
  }

  @Test
  void isIdempotent() {
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);

    assertThat(
        mongoTemplate.indexOps(V022CreatePlatformReleaseIndexes.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .filteredOn(name -> name.equals(V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX))
        .hasSize(1);
  }

  @Test
  void indexesCoverTheFieldsTheSweepActuallyQueriesOn() {
    V022CreatePlatformReleaseIndexes.createIndexes(mongoTemplate);

    // The changelog read: sort by commitTime descending, no filter.
    assertThat(indexKeys(V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX))
        .containsExactly("commitTime");
    assertThat(indexDirection(V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX, "commitTime"))
        .isEqualTo(-1);
    // The sweep's claim query: filter on summaryStatus, sort by commitTime descending, so the
    // compound order matters: summaryStatus first.
    assertThat(indexKeys(V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX))
        .containsExactly("summaryStatus", "commitTime");
    assertThat(
        indexDirection(V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX, "summaryStatus"))
        .isEqualTo(1);
    assertThat(
        indexDirection(V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX, "commitTime"))
        .isEqualTo(-1);
  }

  @Test
  void rollbackDropsBothIndexes() {
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    assertThat(
        mongoTemplate.indexOps(V022CreatePlatformReleaseIndexes.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .doesNotContain(
            V022CreatePlatformReleaseIndexes.COMMIT_TIME_INDEX,
            V022CreatePlatformReleaseIndexes.SUMMARY_STATUS_INDEX);
  }

  private List<String> indexKeys(final String indexName) {
    for (Document index :
        mongoTemplate.getCollection(V022CreatePlatformReleaseIndexes.COLLECTION).listIndexes()) {
      if (indexName.equals(index.getString("name"))) {
        return List.copyOf(index.get("key", Document.class).keySet());
      }
    }
    throw new AssertionError("Index not found: " + indexName);
  }

  private int indexDirection(final String indexName, final String field) {
    for (Document index :
        mongoTemplate.getCollection(V022CreatePlatformReleaseIndexes.COLLECTION).listIndexes()) {
      if (indexName.equals(index.getString("name"))) {
        return index.get("key", Document.class).getInteger(field);
      }
    }
    throw new AssertionError("Index not found: " + indexName);
  }
}
