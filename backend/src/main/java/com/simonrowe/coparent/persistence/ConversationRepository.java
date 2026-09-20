package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Conversation;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for embedded-message family conversations. */
public interface ConversationRepository extends MongoRepository<Conversation, ObjectId> {
  List<Conversation> findByFamilyIdAndDeletedAtIsNullOrderByLastMessageAtDesc(ObjectId familyId);

  Optional<Conversation> findByIdAndFamilyIdAndDeletedAtIsNull(ObjectId id, ObjectId familyId);

  Optional<Conversation> findByPermissionRequestIdAndDeletedAtIsNull(ObjectId permissionId);
}
