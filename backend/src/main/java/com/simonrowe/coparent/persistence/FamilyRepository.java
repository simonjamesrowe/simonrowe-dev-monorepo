package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Family;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for active CoParent families. */
public interface FamilyRepository extends MongoRepository<Family, ObjectId> {
  Optional<Family> findByIdAndDeletedAtIsNull(ObjectId id);

  List<Family> findByIdInAndDeletedAtIsNull(Collection<ObjectId> ids);
}
