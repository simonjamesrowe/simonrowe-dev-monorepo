package com.simonrowe.meals;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealFavouriteRepository extends MongoRepository<MealFavourite, String> {

  List<MealFavourite> findByOwnerIdOrderByAddedAtDesc(String ownerId);
}
