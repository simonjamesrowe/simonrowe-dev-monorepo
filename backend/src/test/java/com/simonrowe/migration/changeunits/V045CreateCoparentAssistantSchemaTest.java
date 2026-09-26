package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.assistant.AssistantProposalBatch;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the ephemeral assistant schema against real MongoDB, including replay. */
class V045CreateCoparentAssistantSchemaTest extends AbstractIntegrationTest {

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  private final V045CreateCoparentAssistantSchema changeUnit =
      new V045CreateCoparentAssistantSchema();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.dropCollection(AssistantProposalBatch.COLLECTION);
  }

  @Test
  void createsOwnershipAndExpiryIndexes() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    final List<String> names = mongoTemplate.indexOps(AssistantProposalBatch.COLLECTION)
        .getIndexInfo().stream().map(index -> index.getName()).toList();
    assertThat(names).contains(
        "idx_coparent_assistant_owner_recent", "idx_coparent_assistant_expiry");
    assertThat(mongoTemplate.indexOps(AssistantProposalBatch.COLLECTION).getIndexInfo())
        .anySatisfy(index -> {
          if ("idx_coparent_assistant_expiry".equals(index.getName())) {
            assertThat(index.getExpireAfter()).isEqualTo(java.time.Duration.ZERO);
          }
        });
    assertThat(mongoTemplate.indexOps("audits").getIndexInfo())
        .filteredOn(index -> "idx_coparent_assistant_audit_receipt".equals(index.getName()))
        .singleElement()
        .satisfies(index -> {
          assertThat(index.isUnique()).isTrue();
          assertThat(index.isSparse()).isTrue();
        });
  }

  @Test
  void replayLeavesSchemaUnchanged() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(mongoTemplate.indexOps(AssistantProposalBatch.COLLECTION).getIndexInfo())
        .hasSize(3);
  }
}
