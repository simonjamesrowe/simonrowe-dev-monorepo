package com.simonrowe.narration;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class NarrationRestoreValidator {

  private static final Logger LOG =
      LoggerFactory.getLogger(NarrationRestoreValidator.class);

  private final NarrationRepository repository;
  private final NarrationStorage storage;
  private final MongoTemplate mongoTemplate;

  public NarrationRestoreValidator(
      final NarrationRepository repository,
      final NarrationStorage storage,
      final MongoTemplate mongoTemplate
  ) {
    this.repository = repository;
    this.storage = storage;
    this.mongoTemplate = mongoTemplate;
  }

  public ReconciliationResult reconcile() {
    ensureIndexes();
    int ready = 0;
    int retryable = 0;
    int unchanged = 0;
    for (Narration narration : repository.findAll()) {
      if (narration.status() == NarrationStatus.READY) {
        if (storage.isValid(narration)) {
          ready++;
        } else {
          storage.delete(narration);
          narration.markFailed("AUDIO_MISSING_OR_INVALID", true, Instant.now());
          repository.save(narration);
          retryable++;
        }
      } else if (isSafelyRetryableAfterRestore(narration)) {
        narration.markFailed("RESTORED_INCOMPLETE", true, Instant.now());
        repository.save(narration);
        retryable++;
      } else {
        unchanged++;
      }
    }
    LOG.info("Reconciled restored narrations: ready={}, retryable={}, unchanged={}",
        ready, retryable, unchanged);
    return new ReconciliationResult(ready, retryable, unchanged);
  }

  void ensureIndexes() {
    var indexes = mongoTemplate.indexOps(Narration.class);
    indexes.createIndex(new Index().named("fingerprint")
        .on("fingerprint", Sort.Direction.ASC).unique());
    indexes.createIndex(new Index().named("blogId")
        .on("blogId", Sort.Direction.ASC));
    indexes.createIndex(new Index().named("idx_narration_blog_updated")
        .on("blogId", Sort.Direction.ASC)
        .on("updatedAt", Sort.Direction.DESC));
    indexes.createIndex(new Index().named("idx_narration_status_lease")
        .on("status", Sort.Direction.ASC)
        .on("leaseUntil", Sort.Direction.ASC));
  }

  private static boolean isSafelyRetryableAfterRestore(final Narration narration) {
    if (narration.status() == NarrationStatus.QUEUED) {
      return true;
    }
    if (narration.status() != NarrationStatus.PROCESSING) {
      return false;
    }
    return !narration.providerRequestStarted()
        || narration.providerOperationName() != null;
  }

  public record ReconciliationResult(int ready, int retryable, int unchanged) {
  }
}
