package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.CalendarEvent;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for active family calendar events. */
public interface EventRepository extends MongoRepository<CalendarEvent, ObjectId> {
  List<CalendarEvent> findByFamilyIdAndDeletedAtIsNullOrderByStartDateAsc(ObjectId familyId);

  Optional<CalendarEvent> findByIdAndFamilyIdAndDeletedAtIsNull(ObjectId id, ObjectId familyId);

  Optional<CalendarEvent> findByAssistantActionId(ObjectId assistantActionId);
}
