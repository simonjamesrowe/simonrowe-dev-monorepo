package com.simonrowe.skills;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SkillGroupRepository extends MongoRepository<SkillGroup, String> {

  List<SkillGroup> findAllByOrderByDisplayOrderAsc();
}
