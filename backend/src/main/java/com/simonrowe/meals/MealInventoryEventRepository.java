package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealInventoryEventRepository extends MongoRepository<MealInventoryEvent, String> {

  List<MealInventoryEvent> findByOwnerIdOrderByAtDesc(String ownerId, Pageable page);
}
