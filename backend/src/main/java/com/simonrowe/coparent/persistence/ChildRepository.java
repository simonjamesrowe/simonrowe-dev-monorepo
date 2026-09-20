package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Child;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for family-scoped child profiles. */
public interface ChildRepository extends MongoRepository<Child, ObjectId> {
  List<Child> findByFamilyIdAndDeletedAtIsNull(ObjectId familyId);

  Optional<Child> findByIdAndFamilyIdAndDeletedAtIsNull(ObjectId id, ObjectId familyId);

  long countByIdInAndFamilyIdAndDeletedAtIsNull(List<ObjectId> ids, ObjectId familyId);
}
