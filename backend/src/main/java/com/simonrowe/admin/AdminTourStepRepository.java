package com.simonrowe.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminTourStepRepository
    extends MongoRepository<TourStep, String> {

  List<TourStep> findAllByOrderByOrderAsc();

  Optional<TourStep> findByLegacyId(String legacyId);
}
