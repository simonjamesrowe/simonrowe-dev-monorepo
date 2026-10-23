package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoCollection;
import com.simonrowe.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the unit directly because Mongock is disabled in tests. */
class V065MarkPortfolioProjectsLiveTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V065MarkPortfolioProjectsLive changeUnit = new V065MarkPortfolioProjectsLive();

  @BeforeEach
  @AfterEach
  void drop() {
    mongoTemplate.dropCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private MongoCollection<Document> projects() {
    return mongoTemplate.getCollection(V048CreatePortfolioProjects.COLLECTION);
  }

  private void row(final String slug, final String status) {
    projects().insertOne(new Document("slug", slug).append("status", status));
  }

  private String status(final String slug) {
    return projects().find(new Document("slug", slug)).first().getString("status");
  }

  @Test
  void marksEachSeededProjectLive() {
    row("software-factory", "LIVE");
    row("term-time", "BETA");
    row("co-parents", "IN_DEVELOPMENT");
    row("clinicians-veil", "IN_DEVELOPMENT");

    changeUnit.execution(mongoTemplate);

    for (String slug : new String[] {"software-factory", "term-time", "co-parents",
        "clinicians-veil"}) {
      assertThat(status(slug)).as(slug).isEqualTo("LIVE");
    }
  }

  /** A status chosen in the CMS since the seed is the owner's decision, and stays. */
  @Test
  void leavesStatusesChangedInTheCmsAlone() {
    row("term-time", "IN_DEVELOPMENT");
    row("co-parents", "COMING_SOON");
    row("clinicians-veil", "IN_DEVELOPMENT");

    changeUnit.execution(mongoTemplate);

    assertThat(status("term-time")).isEqualTo("IN_DEVELOPMENT");
    assertThat(status("co-parents")).isEqualTo("COMING_SOON");
    assertThat(status("clinicians-veil")).isEqualTo("LIVE");
  }

  @Test
  void runningTwiceChangesNothingMore() {
    row("term-time", "BETA");
    changeUnit.execution(mongoTemplate);
    Document first = projects().find(new Document("slug", "term-time")).first();

    changeUnit.execution(mongoTemplate);

    assertThat(projects().find(new Document("slug", "term-time")).first()).isEqualTo(first);
  }

  @Test
  void rollbackRestoresTheSeededStatuses() {
    row("term-time", "BETA");
    row("co-parents", "IN_DEVELOPMENT");
    changeUnit.execution(mongoTemplate);

    changeUnit.rollback(mongoTemplate);

    assertThat(status("term-time")).isEqualTo("BETA");
    assertThat(status("co-parents")).isEqualTo("IN_DEVELOPMENT");
  }
}
