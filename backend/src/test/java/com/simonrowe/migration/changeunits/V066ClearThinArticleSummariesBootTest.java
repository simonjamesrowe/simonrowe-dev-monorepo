package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.agents.scrapers.ScraperFactory;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves V066 is picked up and runs at boot with its {@code NarrationService} injected: the
 * suite otherwise runs with {@code mongock.enabled=false}, and a destructive unit that never
 * ran would leave every starved summary failing for ever with nothing to say so.
 *
 * <p>Same properties and bean overrides as {@code V011SeedAndBackfillDanVegaBlogIntegrationTest},
 * so the two share one Mongock-enabled context and the network is never touched.
 */
@TestPropertySource(properties = "mongock.enabled=true")
class V066ClearThinArticleSummariesBootTest extends AbstractIntegrationTest {

  @MockitoBean
  private ScraperFactory scraperFactory;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Test
  void runsAtBoot() {
    Document entry = mongoTemplate.getCollection("mongockChangeLog")
        .find(new Document("changeId", "clear-thin-article-summaries")).first();

    assertThat(entry).isNotNull();
    assertThat(entry.getString("state")).isEqualTo("EXECUTED");
  }
}
