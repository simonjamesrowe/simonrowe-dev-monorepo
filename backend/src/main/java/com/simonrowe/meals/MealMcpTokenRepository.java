package com.simonrowe.meals;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MealMcpTokenRepository extends MongoRepository<MealMcpToken, String> {

  List<MealMcpToken> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

  Optional<MealMcpToken> findByTokenHash(String tokenHash);
}
