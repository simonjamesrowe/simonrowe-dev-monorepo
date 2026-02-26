package com.simonrowe.admin;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminTagRepository extends MongoRepository<Tag, String> {

  Optional<Tag> findByNameIgnoreCase(String name);

  Optional<Tag> findByLegacyId(String legacyId);
}
