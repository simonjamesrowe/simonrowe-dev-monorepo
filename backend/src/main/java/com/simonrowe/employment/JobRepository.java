package com.simonrowe.employment;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface JobRepository extends MongoRepository<Job, String> {

    List<Job> findAllByOrderByStartDateDesc();

    List<Job> findBySkillsIn(List<String> skillIds);
}
