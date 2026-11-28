package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.FamilyDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for the family document library. */
public interface FamilyDocumentRepository extends MongoRepository<FamilyDocument, ObjectId> {

  List<FamilyDocument> findByFamilyIdAndArchivedAtIsNullOrderByDatedOnDescCreatedAtDesc(
      ObjectId familyId);

  List<FamilyDocument> findByFamilyIdAndArchivedAtIsNotNullOrderByDatedOnDescCreatedAtDesc(
      ObjectId familyId);

  Optional<FamilyDocument> findByIdAndFamilyId(ObjectId id, ObjectId familyId);
}
