package com.simonrowe.homepage;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface HomePageRepository extends MongoRepository<HomePage, String> {
}
