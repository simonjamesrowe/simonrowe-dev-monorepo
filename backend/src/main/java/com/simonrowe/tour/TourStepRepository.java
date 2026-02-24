package com.simonrowe.tour;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TourStepRepository extends MongoRepository<TourStep, String> {

  List<TourStep> findAllByOrderByOrderAsc();
}
