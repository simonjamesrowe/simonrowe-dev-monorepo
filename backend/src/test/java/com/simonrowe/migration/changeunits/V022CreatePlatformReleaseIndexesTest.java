package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

class V022CreatePlatformReleaseIndexesTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

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
}
