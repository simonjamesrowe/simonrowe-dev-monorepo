package com.simonrowe.admin;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminBlogRepository extends MongoRepository<Blog, String> {

  Page<Blog> findByPublished(boolean published, Pageable pageable);

  Optional<Blog> findByLegacyId(String legacyId);
}
