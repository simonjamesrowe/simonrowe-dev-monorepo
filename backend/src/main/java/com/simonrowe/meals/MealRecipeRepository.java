package com.simonrowe.meals;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealRecipeRepository extends MongoRepository<MealRecipe, String> {
}
