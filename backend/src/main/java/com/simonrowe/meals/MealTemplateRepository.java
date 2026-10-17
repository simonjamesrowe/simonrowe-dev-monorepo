package com.simonrowe.meals;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealTemplateRepository extends MongoRepository<MealTemplate, String> {
}
