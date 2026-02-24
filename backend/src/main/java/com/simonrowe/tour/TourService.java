package com.simonrowe.tour;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TourService {

  private final TourStepRepository tourStepRepository;

  public TourService(final TourStepRepository tourStepRepository) {
    this.tourStepRepository = tourStepRepository;
  }

  public List<TourStep> getAllStepsOrdered() {
    return tourStepRepository.findAllByOrderByOrderAsc();
  }
}
