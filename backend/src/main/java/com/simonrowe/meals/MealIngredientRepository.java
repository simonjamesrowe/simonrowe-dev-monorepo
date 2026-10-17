package com.simonrowe.meals;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealIngredientRepository extends MongoRepository<MealIngredient, String> {
}
