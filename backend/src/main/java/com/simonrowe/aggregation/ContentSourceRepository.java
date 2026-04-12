package com.simonrowe.aggregation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ContentSourceRepository
    extends MongoRepository<ContentSource, String> {

  List<ContentSource> findByActiveTrue();

  Optional<ContentSource> findByName(String name);
}
