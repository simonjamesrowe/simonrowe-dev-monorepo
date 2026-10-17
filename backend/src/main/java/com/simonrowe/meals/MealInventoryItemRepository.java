package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealInventoryItemRepository extends MongoRepository<MealInventoryItem, String> {

  List<MealInventoryItem> findByOwnerId(String ownerId);
}
