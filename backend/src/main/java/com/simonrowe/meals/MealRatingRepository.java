package com.simonrowe.meals;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealRatingRepository extends MongoRepository<MealRating, String> {

  List<MealRating> findByOwnerIdOrderByAtDesc(String ownerId);

  Optional<MealRating> findByIdAndOwnerId(String id, String ownerId);
}
