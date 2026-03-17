package com.simonrowe.admin;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminProfileRepository
    extends MongoRepository<Profile, String> {
}
