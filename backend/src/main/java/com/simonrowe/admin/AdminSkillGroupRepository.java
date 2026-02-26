package com.simonrowe.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminSkillGroupRepository
    extends MongoRepository<SkillGroup, String> {

  List<SkillGroup> findAllByOrderByOrderAsc();

  Page<SkillGroup> findAllByOrderByOrderAsc(Pageable pageable);

  Optional<SkillGroup> findByLegacyId(String legacyId);
}
