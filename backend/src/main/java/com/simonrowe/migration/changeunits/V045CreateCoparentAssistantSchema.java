package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.assistant.AssistantProposalBatch;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the short-lived CoParent assistant proposal collection and its ownership and expiry
 * indexes. Index creation is idempotent, so this unit is also safe to call after a restore.
 */
@ChangeUnit(id = "create-coparent-assistant-schema", order = "045", author = "simonrowe")
public class V045CreateCoparentAssistantSchema {

  private static final Logger log =
      LoggerFactory.getLogger(V045CreateCoparentAssistantSchema.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    createIndexes(operations.template());
    log.info("Ensured ephemeral CoParent assistant proposal schema");
  }

  /** Recreates the ephemeral collection and indexes after a restore. */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    final String collection = AssistantProposalBatch.COLLECTION;
    if (!mongoTemplate.collectionExists(collection)) {
      mongoTemplate.createCollection(collection);
    }
    mongoTemplate.indexOps(collection).createIndex(new Index()
        .named("idx_coparent_assistant_owner_recent")
        .on("familyId", Sort.Direction.ASC)
        .on("submittedBySubject", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC));
    mongoTemplate.indexOps(collection).createIndex(new Index()
        .named("idx_coparent_assistant_expiry")
        .on("expiresAt", Sort.Direction.ASC)
        .expire(Duration.ZERO));
    mongoTemplate.indexOps("audits").createIndex(new Index()
        .named("idx_coparent_assistant_audit_receipt")
        .on("assistantActionId", Sort.Direction.ASC)
        .unique()
        .sparse());
  }

  @RollbackExecution
  public void rollback() {
    // Proposal data is private and short-lived; disabling the feature is the safe rollback.
  }
}
