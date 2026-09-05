package com.simonrowe.migration.changeunits;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Repeats the timing backfill after the initial seeder has had a chance to create its records.
 *
 * <p>Mongock runs before application runners, so V032 correctly repairs an existing production
 * tour but cannot see a brand-new database's seeded rows during its own execution.
 */
@ChangeUnit(id = "backfill-seeded-tour-step-timings", order = "035", author = "simonrowe")
public class V035BackfillSeededTourStepTimings {

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    new V032BackfillTourStepTimings().execution(mongoTemplate);
  }

  @RollbackExecution
  public void rollback() {
    // Preserve operator-configured timing values.
  }
}
