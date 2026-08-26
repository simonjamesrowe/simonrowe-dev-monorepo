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

/**
 * Exercises the index creation against a real MongoDB. Mongock is disabled in tests, so the
 * change unit is driven directly.
 */
class V020CreateArticleSummaryIndexesTest extends AbstractIntegrationTest {

  private static final String COLLECTION = "article_summaries";

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V020CreateArticleSummaryIndexes changeUnit =
      new V020CreateArticleSummaryIndexes();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.getCollection(COLLECTION).drop();
  }

  @Test
  void createsBothIndexes() {
    changeUnit.execution(mongoTemplate);

    assertThat(indexNames()).contains(
        "idx_article_summary_article", "idx_article_summary_status_article");
  }

  @Test
  void isIdempotentSoRerunningChangesNothing() {
    changeUnit.execution(mongoTemplate);
    List<String> afterFirst = indexNames();

    changeUnit.execution(mongoTemplate);

    assertThat(indexNames()).containsExactlyInAnyOrderElementsOf(afterFirst);
  }

  @Test
  void indexesCoverTheFieldsTheApiActuallyQueriesOn() {
    changeUnit.execution(mongoTemplate);

    assertThat(indexKeys("idx_article_summary_article"))
        .containsExactly("articleId");
    // GET /api/news/summaries/ids filters on status and projects articleId, so the
    // compound order matters: status first.
    assertThat(indexKeys("idx_article_summary_status_article"))
        .containsExactly("status", "articleId");
  }

  @Test
  void rollbackDropsBothIndexes() {
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    assertThat(indexNames()).doesNotContain(
        "idx_article_summary_article", "idx_article_summary_status_article");
  }

  private List<String> indexNames() {
    return mongoTemplate.getCollection(COLLECTION).listIndexes()
        .map(index -> index.getString("name"))
        .into(new java.util.ArrayList<>());
  }

  private List<String> indexKeys(final String indexName) {
    for (Document index : mongoTemplate.getCollection(COLLECTION).listIndexes()) {
      if (indexName.equals(index.getString("name"))) {
        return List.copyOf(index.get("key", Document.class).keySet());
      }
    }
    throw new AssertionError("Index not found: " + indexName);
  }
}
