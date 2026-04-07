package com.simonrowe.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminCodeExampleRepository
    extends MongoRepository<CodeExample, String> {

  Page<CodeExample> findByLanguage(String language, Pageable pageable);

  Page<CodeExample> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
      String title, String description, Pageable pageable);

  Page<CodeExample> findBySkillsId(String skillId, Pageable pageable);
}
