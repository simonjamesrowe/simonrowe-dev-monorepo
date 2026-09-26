package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.EventCategory;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for active family event categories. */
public interface EventCategoryRepository extends MongoRepository<EventCategory, ObjectId> {
  List<EventCategory> findByFamilyIdAndDeletedAtIsNullOrderByNameAsc(ObjectId familyId);

  Optional<EventCategory> findByIdAndFamilyIdAndDeletedAtIsNull(
      ObjectId id, ObjectId familyId);

  Optional<EventCategory> findByAssistantActionId(ObjectId assistantActionId);
}
