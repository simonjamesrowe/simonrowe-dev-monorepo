package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.ExpenseSettlement;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for a family's settle-ups and the payments in each. */
public interface ExpenseSettlementRepository extends MongoRepository<ExpenseSettlement, ObjectId> {
  List<ExpenseSettlement> findByFamilyIdOrderByCreatedAtDesc(ObjectId familyId);

  Optional<ExpenseSettlement> findByIdAndFamilyId(ObjectId id, ObjectId familyId);
}
