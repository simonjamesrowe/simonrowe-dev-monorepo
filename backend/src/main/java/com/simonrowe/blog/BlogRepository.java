package com.simonrowe.blog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BlogRepository extends MongoRepository<Blog, String> {

  List<Blog> findByPublishedTrueOrderByCreatedDateDesc();

  Optional<Blog> findByIdAndPublishedTrue(String id);
}
