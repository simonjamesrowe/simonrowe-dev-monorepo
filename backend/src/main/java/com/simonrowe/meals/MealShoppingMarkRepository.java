package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealShoppingMarkRepository extends MongoRepository<MealShoppingMark, String> {

  List<MealShoppingMark> findByOwnerId(String ownerId);
}
