package com.simonrowe.admin;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminSocialMediaRepository
    extends MongoRepository<SocialMediaLink, String> {

  Optional<SocialMediaLink> findByLegacyId(String legacyId);
}
