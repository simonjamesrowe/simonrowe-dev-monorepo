package com.simonrowe.profile;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProfileRepository extends MongoRepository<Profile, String> {

  Optional<Profile> findFirstBy();
}
