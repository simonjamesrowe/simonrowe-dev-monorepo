package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.AuditRecord;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Append-only audit persistence. */
public interface AuditRepository extends MongoRepository<AuditRecord, ObjectId> {

  boolean existsByAssistantActionId(ObjectId assistantActionId);
}
