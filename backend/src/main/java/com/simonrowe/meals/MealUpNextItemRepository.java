package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealUpNextItemRepository extends MongoRepository<MealUpNextItem, String> {

  List<MealUpNextItem> findByOwnerIdOrderByAddedAtAsc(String ownerId);
}
