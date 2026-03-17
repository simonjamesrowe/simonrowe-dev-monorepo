package com.simonrowe.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminSkillRepository extends MongoRepository<Skill, String> {

  List<Skill> findAllByOrderByOrderAsc();

  Page<Skill> findAllByOrderByOrderAsc(Pageable pageable);

  Optional<Skill> findByName(String name);

  Optional<Skill> findByLegacyId(String legacyId);
}
