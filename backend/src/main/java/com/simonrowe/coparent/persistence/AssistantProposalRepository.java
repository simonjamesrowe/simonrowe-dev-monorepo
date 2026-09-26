package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.assistant.AssistantProposalBatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Stores short-lived assistant proposals in the dedicated CoParent database. */
public interface AssistantProposalRepository
    extends MongoRepository<AssistantProposalBatch, ObjectId> {

  List<AssistantProposalBatch>
      findTop20ByFamilyIdAndSubmittedBySubjectAndExpiresAtAfterOrderByCreatedAtDesc(
          ObjectId familyId, String subject, Instant now);

  Optional<AssistantProposalBatch> findByIdAndFamilyIdAndSubmittedBySubjectAndExpiresAtAfter(
      ObjectId id, ObjectId familyId, String subject, Instant now);
}
