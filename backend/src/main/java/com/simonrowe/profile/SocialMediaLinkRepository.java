package com.simonrowe.profile;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface SocialMediaLinkRepository
    extends MongoRepository<SocialMediaLink, String> {
}
