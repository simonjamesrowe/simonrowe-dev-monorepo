package com.simonrowe.admin;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminJobRepository extends MongoRepository<Job, String> {

  Page<Job> findByEducation(boolean education, Pageable pageable);

  Optional<Job> findByLegacyId(String legacyId);
}
