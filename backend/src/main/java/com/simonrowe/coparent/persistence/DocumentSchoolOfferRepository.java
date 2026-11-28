package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.DocumentSchoolOffer;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for the school letters and report prompts offered to families. */
public interface DocumentSchoolOfferRepository
    extends MongoRepository<DocumentSchoolOffer, ObjectId> {

  Optional<DocumentSchoolOffer> findByIdAndFamilyId(ObjectId id, ObjectId familyId);

  List<DocumentSchoolOffer> findByFamilyIdAndStatusOrderBySentAtDesc(
      ObjectId familyId, DocumentSchoolOffer.Status status);
}
