package com.simonrowe.blog;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TagRepository extends MongoRepository<Tag, String> {

  Optional<Tag> findByName(String name);
}
