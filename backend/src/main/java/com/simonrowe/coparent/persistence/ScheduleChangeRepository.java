package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.ScheduleChangeRequest;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for family schedule-change decisions. */
public interface ScheduleChangeRepository
    extends MongoRepository<ScheduleChangeRequest, ObjectId> {
  List<ScheduleChangeRequest> findByFamilyIdAndDeletedAtIsNullOrderByRequestedAtDesc(
      ObjectId familyId);

  Optional<ScheduleChangeRequest> findByIdAndFamilyIdAndDeletedAtIsNull(
      ObjectId id, ObjectId familyId);

  Optional<ScheduleChangeRequest> findByAssistantActionId(ObjectId assistantActionId);
}
