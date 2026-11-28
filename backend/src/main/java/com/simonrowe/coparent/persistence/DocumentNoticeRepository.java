package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.DocumentNotice;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for the batched document emails waiting to be sent. */
public interface DocumentNoticeRepository extends MongoRepository<DocumentNotice, ObjectId> {

  Optional<DocumentNotice> findByFamilyIdAndRecipientParentId(
      ObjectId familyId, ObjectId recipientParentId);
}
