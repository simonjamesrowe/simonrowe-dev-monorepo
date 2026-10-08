package com.simonrowe.coparent.persistence;

import com.simonrowe.coparent.model.Expense;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for family-scoped shared expenses. */
public interface ExpenseRepository extends MongoRepository<Expense, ObjectId> {
  List<Expense> findByFamilyIdAndDeletedAtIsNullOrderByDateDescCreatedAtDesc(ObjectId familyId);

  Optional<Expense> findByIdAndFamilyIdAndDeletedAtIsNull(ObjectId id, ObjectId familyId);

  Optional<Expense> findByAssistantActionId(ObjectId assistantActionId);
}
