package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Parent;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for Auth0-backed family memberships. */
public interface ParentRepository extends MongoRepository<Parent, ObjectId> {
  List<Parent> findByAuth0IdAndStatus(String auth0Id, String status);

  Optional<Parent> findFirstByAuth0IdAndFamilyIdIsNull(String auth0Id);

  Optional<Parent> findByFamilyIdAndAuth0IdAndStatus(
      ObjectId familyId, String auth0Id, String status);

  Optional<Parent> findByIdAndFamilyIdAndStatus(ObjectId id, ObjectId familyId, String status);

  List<Parent> findByFamilyIdAndStatus(ObjectId familyId, String status);

  long countByFamilyIdAndRoleAndStatus(ObjectId familyId, String role, String status);

  boolean existsByFamilyIdAndEmailIgnoreCaseAndStatus(
      ObjectId familyId, String email, String status);

  long countByIdInAndFamilyIdAndStatus(List<ObjectId> ids, ObjectId familyId, String status);
}
