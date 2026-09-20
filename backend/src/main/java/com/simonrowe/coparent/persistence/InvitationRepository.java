package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Invitation;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for single-use CoParent invitations. */
public interface InvitationRepository extends MongoRepository<Invitation, ObjectId> {
  List<Invitation> findByFamilyIdOrderBySentAtDesc(ObjectId familyId);

  Optional<Invitation> findByIdAndFamilyId(ObjectId id, ObjectId familyId);

  Optional<Invitation> findByToken(String token);

  boolean existsByFamilyIdAndEmailAndStatus(ObjectId familyId, String email, String status);
}
