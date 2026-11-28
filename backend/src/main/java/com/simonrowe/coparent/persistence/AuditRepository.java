package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.AuditRecord;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Append-only audit persistence. */
public interface AuditRepository extends MongoRepository<AuditRecord, ObjectId> {

  boolean existsByAssistantActionId(ObjectId assistantActionId);

  /** One entity's audit trail, oldest first. Read for the documents drawer's history. */
  List<AuditRecord> findByFamilyIdAndEntityTypeAndEntityIdOrderByTimestampAsc(
      ObjectId familyId, String entityType, String entityId);
}
