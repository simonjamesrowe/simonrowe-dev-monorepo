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
import org.springframework.data.mongodb.core.index.IndexInfo;

/** The expenses collection and indexes exist in the CoParent database and survive a replay. */
class V052CreateCoparentExpensesTest extends AbstractIntegrationTest {

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  private final V052CreateCoparentExpenses changeUnit = new V052CreateCoparentExpenses();

  @BeforeEach
  @AfterEach
  void dropCollection() {
    mongoTemplate.dropCollection(V052CreateCoparentExpenses.EXPENSES);
  }

  @Test
  void createsTheCollectionAndItsIndexes() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(mongoTemplate.collectionExists(V052CreateCoparentExpenses.EXPENSES)).isTrue();
    assertThat(names()).contains("idx_coparent_expense_family_date",
        "idx_coparent_expense_family_agreement", "idx_coparent_expense_assistant_action");
    assertThat(indexes()).filteredOn(index ->
            "idx_coparent_expense_assistant_action".equals(index.getName()))
        .singleElement()
        .satisfies(index -> {
          assertThat(index.isUnique()).isTrue();
          assertThat(index.isSparse()).isTrue();
        });
  }

  @Test
  void replayLeavesTheSchemaUnchanged() {
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));
    final List<String> first = names();
    changeUnit.execution(new CoparentMongoOperations(mongoTemplate));

    assertThat(names()).containsExactlyInAnyOrderElementsOf(first);
  }

  private List<IndexInfo> indexes() {
    return mongoTemplate.indexOps(V052CreateCoparentExpenses.EXPENSES).getIndexInfo();
  }

  private List<String> names() {
    return indexes().stream().map(IndexInfo::getName).toList();
  }
}
