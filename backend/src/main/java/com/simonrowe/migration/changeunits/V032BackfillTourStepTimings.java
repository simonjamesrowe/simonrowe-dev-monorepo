package com.simonrowe.migration.changeunits;

import com.mongodb.client.result.UpdateResult;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Gives existing tour steps an explicit auto-advance duration so the admin console can manage
 * their pacing. The seeded Ask AI step gets ten seconds after the first visible answer; all
 * other previously-unconfigured steps retain the existing seven-second behaviour. This is
 * idempotent because only null or missing durations are changed.
 */
@ChangeUnit(id = "backfill-tour-step-timings", order = "032", author = "simonrowe")
public class V032BackfillTourStepTimings {

  static final String COLLECTION = "tourSteps";
  static final int DEFAULT_DELAY_MS = 7000;
  static final int ASK_AI_DELAY_MS = 10000;
  static final String ASK_AI_LEGACY_ID = "default-ask-ai";

  private static final Logger LOG =
      LoggerFactory.getLogger(V032BackfillTourStepTimings.class);

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    UpdateResult askAi = mongoTemplate.updateMulti(
        missingDuration().addCriteria(Criteria.where("legacyId").is(ASK_AI_LEGACY_ID)),
        Update.update("autoAdvanceMs", ASK_AI_DELAY_MS),
        COLLECTION);
    UpdateResult remaining = mongoTemplate.updateMulti(
        missingDuration().addCriteria(Criteria.where("legacyId").ne(ASK_AI_LEGACY_ID)),
        Update.update("autoAdvanceMs", DEFAULT_DELAY_MS),
        COLLECTION);
    LOG.info("Backfilled explicit timings for {} Ask AI and {} other tour steps",
        askAi.getModifiedCount(), remaining.getModifiedCount());
  }

  @RollbackExecution
  public void rollback() {
    // Additive timing backfill; a rollback must not erase operator edits made after this ran.
  }

  private Query missingDuration() {
    return new Query(new Criteria().orOperator(
        Criteria.where("autoAdvanceMs").exists(false),
        Criteria.where("autoAdvanceMs").is(null)));
  }
}
